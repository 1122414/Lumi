package com.lumi.camera.vision

import com.lumi.camera.guidance.Lens
import com.lumi.camera.guidance.Observation
import com.lumi.camera.guidance.RotationProjection
import com.lumi.camera.guidance.Uv
import kotlin.math.abs
import kotlin.math.hypot

internal data class TrackedShift(
    val dx: Double,
    val dy: Double,
    val lens: Lens,
    val timeNs: Long,
    val matched: Boolean,
    val scale: Double = 1.0,
)

// Lightweight IMU-assisted 2D translation + apparent-scale tracker (~1-2ms/frame, zero TFLite).
// Compensates close-up parallax, distance change (e.g. stepping back after 2x zoom), and AE shifts.
internal class SubjectTracker private constructor(
    private val source: Observation,
    private val samples: List<Sample>,
    private val center: Uv,
) {
    private data class Sample(val p: Uv, val color: Int, val weight: Double, val interior: Boolean)
    private data class ProjectedSample(val ox: Double, val oy: Double, val color: Int, val weight: Double, val interior: Boolean)
    private data class EvalResult(val cost: Double, val interiorError: Double, val totalError: Double)

    private var lastDx = 0.0
    private var lastDy = 0.0
    private var lastScale = 1.0
    private var lastLensFx = source.lens.fx
    private var lastLensFy = source.lens.fy
    private var lastTimeNs = source.timeNs

    fun track(current: Observation, image: SubjectPixels): TrackedShift {
        val projection = RotationProjection(source, current)
        val projectedCenter = projection.project(center)
        val initDx = lastDx * (current.lens.fx / lastLensFx)
        val initDy = lastDy * (current.lens.fy / lastLensFy)
        val initScale = lastScale.coerceIn(MIN_SCALE, MAX_SCALE)
        if (projectedCenter == null) {
            return TrackedShift(initDx, initDy, current.lens, current.timeNs, matched = false, scale = initScale)
        }
        val projected = ArrayList<ProjectedSample>(samples.size)
        var projectedInterior = 0
        for (s in samples) {
            val p = projection.project(s.p) ?: continue
            projected.add(ProjectedSample(p.x - projectedCenter.x, p.y - projectedCenter.y, s.color, s.weight, s.interior))
            if (s.interior) projectedInterior++
        }
        if (projected.size < 6 || projectedInterior < 4) {
            return TrackedShift(initDx, initDy, current.lens, current.timeNs, matched = false, scale = initScale)
        }

        val scaleRatio = current.lens.fx / source.lens.fx
        val minInterior = maxOf(4, projectedInterior / 4)
        var bestCost = Double.POSITIVE_INFINITY
        var bestInteriorError = Double.POSITIVE_INFINITY
        var bestTotalError = Double.POSITIVE_INFINITY
        var bestDx = initDx
        var bestDy = initDy
        var bestScale = initScale

        fun scoreAt(
            dx: Double,
            dy: Double,
            relScale: Double,
            withMotionPenalty: Boolean,
            minVisRatio: Double = 0.25,
        ): EvalResult? {
            val cx = projectedCenter.x + dx
            val cy = projectedCenter.y + dy
            if (cx !in -0.18..1.18 || cy !in -0.18..1.18) return null
            val requiredInterior = maxOf(minInterior, (projectedInterior * minVisRatio).toInt())

            // Pass 1: estimate bounded exposure shift (deltaY) over visible interior samples.
            var interiorVisible = 0
            var yDiffSum = 0.0
            for (s in projected) {
                if (!s.interior) continue
                val color = image.at(cx + s.ox * relScale, cy + s.oy * relScale) ?: continue
                interiorVisible++
                yDiffSum += ((color shr 16) - (s.color shr 16)).toDouble()
            }
            if (interiorVisible < requiredInterior) return null
            val deltaY = if (withMotionPenalty) {
                (yDiffSum / interiorVisible).coerceIn(-MAX_EXPOSURE_SHIFT, MAX_EXPOSURE_SHIFT)
            } else {
                (yDiffSum / interiorVisible).coerceIn(-12.0, 12.0)
            }

            // Pass 2: compute exposure-compensated color & chroma match across interior + boundary ring.
            var visibleWeight = 0.0
            var error = 0.0
            var interiorError = 0.0
            for (s in projected) {
                val color = image.at(cx + s.ox * relScale, cy + s.oy * relScale) ?: continue
                val diff = colorError(color, s.color, deltaY).coerceAtMost(90.0)
                visibleWeight += s.weight
                error += s.weight * diff
                if (s.interior) interiorError += diff
            }
            if (visibleWeight <= 0.0) return null
            val meanError = error / visibleWeight
            val meanInterior = interiorError / interiorVisible
            val visibilityPenalty = 24.0 * (1.0 - interiorVisible.toDouble() / projectedInterior)
            val exposurePenalty = (if (withMotionPenalty) 0.14 else 0.35) * abs(deltaY)
            val scalePenalty = if (withMotionPenalty) {
                10.0 * abs(relScale - 1.0) + 6.0 * abs(relScale - initScale)
            } else {
                8.0 * abs(relScale - 1.0)
            }
            val motionPenalty = if (withMotionPenalty) {
                10.0 * hypot(dx - initDx, dy - initDy) + 2.5 * hypot(dx, dy) / scaleRatio.coerceAtLeast(0.5)
            } else 0.0
            val cost = meanError + visibilityPenalty + exposurePenalty + scalePenalty + motionPenalty
            return EvalResult(cost, meanInterior, meanError)
        }

        fun evaluate(
            dx: Double,
            dy: Double,
            relScale: Double,
            withMotionPenalty: Boolean,
            minVisRatio: Double = 0.25,
        ) {
            val res = scoreAt(dx, dy, relScale, withMotionPenalty, minVisRatio) ?: return
            if (res.cost < bestCost) {
                bestCost = res.cost
                bestInteriorError = res.interiorError
                bestTotalError = res.totalError
                bestDx = dx
                bestDy = dy
                bestScale = relScale
            }
        }

        val baseline = scoreAt(initDx, initDy, initScale, withMotionPenalty = true)
        if (baseline != null) {
            bestCost = baseline.cost
            bestInteriorError = baseline.interiorError
            bestTotalError = baseline.totalError
        }

        fun search(
            aroundDx: Double,
            aroundDy: Double,
            scales: List<Double>,
            reach: Int,
            step: Double,
            withMotionPenalty: Boolean = true,
        ) {
            for (relScale in scales) {
                for (iy in -reach..reach) {
                    val dy = aroundDy + iy * step
                    for (ix in -reach..reach) {
                        evaluate(aroundDx + ix * step, dy, relScale, withMotionPenalty)
                    }
                }
            }
        }

        val coarseScales = buildList {
            add(initScale)
            val smaller = (initScale * 0.84).coerceIn(MIN_SCALE, MAX_SCALE)
            val larger = (initScale * 1.18).coerceIn(MIN_SCALE, MAX_SCALE)
            if (abs(smaller - initScale) > 0.03) add(smaller)
            if (abs(larger - initScale) > 0.03) add(larger)
            if (abs(1.0 - initScale) > 0.08) add(1.0)
        }

        // Local coarse pass around previous shift & scale, plus a quick anchor check around zero.
        search(initDx, initDy, coarseScales, reach = 5, step = 0.025)
        if (hypot(initDx, initDy) > 0.045) search(0.0, 0.0, listOf(1.0, initScale), reach = 3, step = 0.025)

        var reacquired = false
        if (!bestCost.isFinite() || bestInteriorError > MAX_INTERIOR_ERROR) {
            // Full-frame coarse re-acquisition scan for fast subject moves or subject re-entry.
            bestCost = Double.POSITIVE_INFINITY
            bestInteriorError = Double.POSITIVE_INFINITY
            bestTotalError = Double.POSITIVE_INFINITY
            val reacquireScales = if (scaleRatio > 1.35) listOf(1.0, 0.76) else listOf(1.0)
            for (relScale in reacquireScales) {
                var cy = 0.06
                while (cy <= 0.94) {
                    var cx = 0.06
                    while (cx <= 0.94) {
                        evaluate(cx - projectedCenter.x, cy - projectedCenter.y, relScale, withMotionPenalty = false, minVisRatio = 0.55)
                        cx += 0.06
                    }
                    cy += 0.06
                }
            }
            reacquired = true
        }
        val acceptInterior = if (reacquired) REACQUIRE_INTERIOR_ERROR else MAX_INTERIOR_ERROR
        val acceptTotal = if (reacquired) REACQUIRE_TOTAL_ERROR else MAX_TOTAL_ERROR
        if (!bestCost.isFinite() || bestInteriorError > acceptInterior || bestTotalError > acceptTotal) {
            return TrackedShift(initDx, initDy, current.lens, current.timeNs, matched = false, scale = initScale)
        }

        // Fine pass around coarse optimum (translation + fine scale).
        val coarseDx = bestDx
        val coarseDy = bestDy
        val coarseScale = bestScale
        val fineScales = buildList {
            add(coarseScale)
            val lo = (coarseScale - 0.06).coerceIn(MIN_SCALE, MAX_SCALE)
            val hi = (coarseScale + 0.06).coerceIn(MIN_SCALE, MAX_SCALE)
            if (abs(lo - coarseScale) > 0.01) add(lo)
            if (abs(hi - coarseScale) > 0.01) add(hi)
        }
        search(coarseDx, coarseDy, fineScales, reach = 3, step = 0.006, withMotionPenalty = !reacquired)
        if (!bestCost.isFinite() || bestInteriorError > acceptInterior || bestTotalError > acceptTotal) {
            return TrackedShift(initDx, initDy, current.lens, current.timeNs, matched = false, scale = initScale)
        }

        // Anti-drift deadband: on smooth/low-texture objects, ignore negligible noisy moves
        // when staying at the current anchor is practically just as good.
        val moveDist = hypot(bestDx - initDx, bestDy - initDy)
        val scaleDist = abs(bestScale - initScale)
        if (!reacquired && baseline != null &&
            baseline.interiorError <= MAX_INTERIOR_ERROR &&
            moveDist < 0.028 && scaleDist < 0.05 &&
            (baseline.cost - bestCost) < 1.8
        ) {
            bestDx = initDx
            bestDy = initDy
            bestScale = initScale
        }

        val dtMs = ((current.timeNs - lastTimeNs) / 1_000_000.0).coerceIn(1.0, 250.0)
        val alpha = if (reacquired || moveDist > 0.14) 1.0
            else (1.0 - kotlin.math.exp(-dtMs / 30.0)).coerceIn(0.35, 0.90)
        val scaleAlpha = if (reacquired) 0.75 else (alpha * 0.65).coerceIn(0.25, 0.65)
        lastDx = initDx + alpha * (bestDx - initDx)
        lastDy = initDy + alpha * (bestDy - initDy)
        lastScale = (initScale + scaleAlpha * (bestScale - initScale)).coerceIn(MIN_SCALE, MAX_SCALE)
        lastLensFx = current.lens.fx
        lastLensFy = current.lens.fy
        lastTimeNs = current.timeNs
        return TrackedShift(lastDx, lastDy, current.lens, current.timeNs, matched = true, scale = lastScale)
    }

    companion object {
        private const val MIN_SCALE = 0.62
        private const val MAX_SCALE = 1.45
        private const val MAX_EXPOSURE_SHIFT = 28.0
        private const val MAX_INTERIOR_ERROR = 36.0
        private const val MAX_TOTAL_ERROR = 44.0
        private const val REACQUIRE_INTERIOR_ERROR = 28.0
        private const val REACQUIRE_TOTAL_ERROR = 34.0

        private fun colorError(live: Int, template: Int, deltaY: Double): Double {
            val yDiff = abs(((live shr 16) - (template shr 16)) - deltaY)
            val uDiff = abs((live shr 8 and 255) - (template shr 8 and 255))
            val vDiff = abs((live and 255) - (template and 255))
            return 0.55 * yDiff + uDiff + vDiff
        }

        fun from(outline: SubjectOutline, image: SubjectPixels): SubjectTracker? {
            val b = outline.bounds
            val padX = ((b.right - b.left) * 0.14).coerceIn(0.02, 0.06)
            val padY = ((b.bottom - b.top) * 0.14).coerceIn(0.02, 0.06)
            val left = (b.left - padX).coerceAtLeast(0.01)
            val right = (b.right + padX).coerceAtMost(0.99)
            val top = (b.top - padY).coerceAtLeast(0.01)
            val bottom = (b.bottom + padY).coerceAtMost(0.99)
            val samples = ArrayList<Sample>(49)
            var interiorCount = 0
            for (row in 0 until 7) {
                for (col in 0 until 7) {
                    val p = Uv(
                        left + (col + 0.5) * (right - left) / 7.0,
                        top + (row + 0.5) * (bottom - top) / 7.0,
                    )
                    val inside = outline.containsAtCapture(p)
                    val color = image.at(p.x, p.y) ?: continue
                    if (inside) {
                        interiorCount++
                        samples.add(Sample(p, color, 1.0, interior = true))
                    } else {
                        samples.add(Sample(p, color, 0.45, interior = false))
                    }
                }
            }
            if (interiorCount < 5 || samples.size < 8) return null
            val center = Uv((b.left + b.right) / 2.0, (b.top + b.bottom) / 2.0)
            return SubjectTracker(outline.sourceObservation, samples, center)
        }
    }
}
