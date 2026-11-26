package com.lumi.camera.vision

import com.lumi.camera.guidance.Lens
import com.lumi.camera.guidance.Observation
import com.lumi.camera.guidance.RotationProjection
import com.lumi.camera.guidance.Uv
import kotlin.math.abs
import kotlin.math.hypot

internal data class TrackedShift(val dx: Double, val dy: Double, val lens: Lens, val timeNs: Long, val matched: Boolean)

// Lightweight IMU-assisted 2D visual translation tracker (~1ms/frame, zero TFLite).
// Compensates close-up parallax and handheld subject motion on top of RotationProjection.
internal class SubjectTracker private constructor(
    private val source: Observation,
    private val samples: List<Sample>,
    private val center: Uv,
) {
    private data class Sample(val p: Uv, val color: Int, val weight: Double, val interior: Boolean)

    private var lastDx = 0.0
    private var lastDy = 0.0
    private var lastLensFx = source.lens.fx
    private var lastLensFy = source.lens.fy
    private var lastTimeNs = source.timeNs

    fun track(current: Observation, image: SubjectPixels): TrackedShift {
        val projection = RotationProjection(source, current)
        val projectedCenter = projection.project(center)
        val initDx = lastDx * (current.lens.fx / lastLensFx)
        val initDy = lastDy * (current.lens.fy / lastLensFy)
        if (projectedCenter == null) {
            return TrackedShift(initDx, initDy, current.lens, current.timeNs, matched = false)
        }
        val projected = ArrayList<Sample>(samples.size)
        var projectedInterior = 0
        for (s in samples) {
            val p = projection.project(s.p) ?: continue
            projected.add(Sample(p, s.color, s.weight, s.interior))
            if (s.interior) projectedInterior++
        }
        if (projected.size < 6 || projectedInterior < 4) {
            return TrackedShift(initDx, initDy, current.lens, current.timeNs, matched = false)
        }

        val scaleRatio = current.lens.fx / source.lens.fx
        val minInterior = maxOf(4, projectedInterior / 4)
        var bestCost = Double.POSITIVE_INFINITY
        var bestInteriorError = Double.POSITIVE_INFINITY
        var bestDx = initDx
        var bestDy = initDy

        fun evaluate(dx: Double, dy: Double, withMotionPenalty: Boolean) {
            val cx = projectedCenter.x + dx
            val cy = projectedCenter.y + dy
            if (cx !in -0.18..1.18 || cy !in -0.18..1.18) return
            var visibleWeight = 0.0
            var interiorVisible = 0
            var error = 0.0
            var interiorError = 0.0
            for (s in projected) {
                val color = image.at(s.p.x + dx, s.p.y + dy) ?: continue
                val diff = colorError(color, s.color).coerceAtMost(90.0)
                visibleWeight += s.weight
                error += s.weight * diff
                if (s.interior) {
                    interiorVisible++
                    interiorError += diff
                }
            }
            if (interiorVisible < minInterior || visibleWeight <= 0.0) return
            val meanError = error / visibleWeight
            val meanInterior = interiorError / interiorVisible
            val visibilityPenalty = 12.0 * (1.0 - interiorVisible.toDouble() / projectedInterior)
            val penalty = if (withMotionPenalty) {
                8.0 * hypot(dx - initDx, dy - initDy) + 2.0 * hypot(dx, dy) / scaleRatio.coerceAtLeast(0.5)
            } else 0.0
            val cost = meanError + visibilityPenalty + penalty
            if (cost < bestCost) {
                bestCost = cost
                bestInteriorError = meanInterior
                bestDx = dx
                bestDy = dy
            }
        }

        fun search(aroundDx: Double, aroundDy: Double, reach: Int, step: Double, withMotionPenalty: Boolean = true) {
            for (iy in -reach..reach) {
                val dy = aroundDy + iy * step
                for (ix in -reach..reach) {
                    evaluate(aroundDx + ix * step, dy, withMotionPenalty)
                }
            }
        }

        // Local coarse pass around previous shift, plus a quick check around zero if drifted far.
        search(initDx, initDy, reach = 6, step = 0.024)
        if (hypot(initDx, initDy) > 0.05) search(0.0, 0.0, reach = 3, step = 0.024)

        var reacquired = false
        if (!bestCost.isFinite() || bestInteriorError > MAX_INTERIOR_ERROR) {
            // Full-frame coarse re-acquisition scan for fast subject moves or subject re-entry.
            bestCost = Double.POSITIVE_INFINITY
            bestInteriorError = Double.POSITIVE_INFINITY
            var cy = 0.06
            while (cy <= 0.94) {
                var cx = 0.06
                while (cx <= 0.94) {
                    evaluate(cx - projectedCenter.x, cy - projectedCenter.y, withMotionPenalty = false)
                    cx += 0.06
                }
                cy += 0.06
            }
            reacquired = true
        }
        val acceptLimit = if (reacquired) REACQUIRE_INTERIOR_ERROR else MAX_INTERIOR_ERROR
        if (!bestCost.isFinite() || bestInteriorError > acceptLimit) {
            return TrackedShift(initDx, initDy, current.lens, current.timeNs, matched = false)
        }

        // Fine pass around coarse optimum.
        val coarseDx = bestDx
        val coarseDy = bestDy
        search(coarseDx, coarseDy, reach = 3, step = 0.006, withMotionPenalty = !reacquired)
        if (!bestCost.isFinite() || bestInteriorError > acceptLimit) {
            return TrackedShift(initDx, initDy, current.lens, current.timeNs, matched = false)
        }

        val dtMs = ((current.timeNs - lastTimeNs) / 1_000_000.0).coerceIn(1.0, 250.0)
        val alpha = if (reacquired || hypot(bestDx - initDx, bestDy - initDy) > 0.14) 1.0
            else (1.0 - kotlin.math.exp(-dtMs / 28.0)).coerceIn(0.35, 0.90)
        lastDx = initDx + alpha * (bestDx - initDx)
        lastDy = initDy + alpha * (bestDy - initDy)
        lastLensFx = current.lens.fx
        lastLensFy = current.lens.fy
        lastTimeNs = current.timeNs
        return TrackedShift(lastDx, lastDy, current.lens, current.timeNs, matched = true)
    }

    companion object {
        private const val MAX_INTERIOR_ERROR = 38.0
        private const val REACQUIRE_INTERIOR_ERROR = 32.0

        private fun colorError(a: Int, b: Int): Double =
            0.55 * abs((a shr 16) - (b shr 16)) +
                abs((a shr 8 and 255) - (b shr 8 and 255)) +
                abs((a and 255) - (b and 255))

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
                        samples.add(Sample(p, color, 0.35, interior = false))
                    }
                }
            }
            if (interiorCount < 5 || samples.size < 8) return null
            val center = Uv((b.left + b.right) / 2.0, (b.top + b.bottom) / 2.0)
            return SubjectTracker(outline.sourceObservation, samples, center)
        }
    }
}
