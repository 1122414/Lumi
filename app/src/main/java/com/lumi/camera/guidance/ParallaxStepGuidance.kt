package com.lumi.camera.guidance

import com.lumi.camera.vision.FaceBox
import com.lumi.camera.vision.SubjectOutline
import com.lumi.camera.vision.SubjectPixels
import com.lumi.camera.vision.TrackedShift
import kotlin.math.abs

internal enum class StepDirection(val wire: String, val label: String, val arrow: String) {
    LEFT("left", "向左挪半步", "←"),
    RIGHT("right", "向右挪半步", "→"),
    CLOSER("closer", "向前靠近半步", "↑"),
    BACK("back", "向后退半步", "↓");

    companion object {
        fun parse(raw: Any?): StepDirection? {
            val key = (raw as? String)?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wire == key }
        }
    }
}

internal data class StepProgress(
    val direction: StepDirection,
    val progress: Float,
    val satisfied: Boolean,
)

// M4 monocular parallax step tracker: compares foreground subject displacement/scale
// against distant background patches outside the subject box to verify lateral or depth steps.
internal class ParallaxStepTracker private constructor(
    val direction: StepDirection,
    private val source: Observation,
    private val bgSamples: List<BgSample>,
) {
    private data class BgSample(val p: Uv, val luma: Int)

    private var smoothedProgress = 0f
    private var latched = false

    fun update(current: Observation, pixels: SubjectPixels?, shift: TrackedShift?): StepProgress {
        if (latched) return StepProgress(direction, 1f, satisfied = true)
        if (shift == null || !shift.matched) {
            return StepProgress(direction, smoothedProgress, satisfied = false)
        }
        val zoomScale = (current.lens.fx / source.lens.fx).coerceAtLeast(0.5)
        val rawProgress = when (direction) {
            StepDirection.LEFT, StepDirection.RIGHT -> {
                val bgDx = if (pixels != null) estimateBackgroundDx(current, pixels) else 0.0
                val subjectNormDx = (shift.dx * (current.lens.fx / shift.lens.fx)) / zoomScale
                val bgNormDx = bgDx / zoomScale
                // Stepping LEFT makes the closer foreground subject move RIGHT relative to distant background (relParallax > 0).
                val relParallaxX = subjectNormDx - bgNormDx
                val directed = if (direction == StepDirection.LEFT) relParallaxX else -relParallaxX
                (directed / TARGET_LATERAL_PARALLAX).toFloat().coerceIn(0f, 1f)
            }
            StepDirection.CLOSER -> {
                ((shift.scale - 1.0) / TARGET_DEPTH_SCALE).toFloat().coerceIn(0f, 1f)
            }
            StepDirection.BACK -> {
                ((1.0 - shift.scale) / TARGET_DEPTH_SCALE).toFloat().coerceIn(0f, 1f)
            }
        }
        smoothedProgress = (smoothedProgress + 0.35f * (rawProgress - smoothedProgress)).coerceIn(0f, 1f)
        if (smoothedProgress >= SATISFIED_THRESHOLD) {
            latched = true
            smoothedProgress = 1f
        }
        return StepProgress(direction, smoothedProgress, latched)
    }

    private fun estimateBackgroundDx(current: Observation, pixels: SubjectPixels): Double {
        if (bgSamples.size < 4) return 0.0
        val projection = RotationProjection(source, current)
        val projected = bgSamples.mapNotNull { s ->
            projection.project(s.p)?.let { p -> p to s.luma }
        }
        if (projected.size < 4) return 0.0

        var bestDx = 0.0
        var bestErr = Double.POSITIVE_INFINITY
        var step = -8
        while (step <= 8) {
            val dx = step * 0.015
            var count = 0
            var err = 0.0
            for ((p, luma) in projected) {
                val c = pixels.at(p.x + dx, p.y) ?: continue
                err += abs((c shr 16) - luma)
                count++
            }
            if (count >= 4) {
                val mean = err / count + 8.0 * abs(dx)
                if (mean < bestErr) {
                    bestErr = mean
                    bestDx = dx
                }
            }
            step++
        }
        return bestDx
    }

    companion object {
        const val TARGET_LATERAL_PARALLAX = 0.065
        const val TARGET_DEPTH_SCALE = 0.15
        const val SATISFIED_THRESHOLD = 0.90f

        fun from(direction: StepDirection, outline: SubjectOutline, pixels: SubjectPixels): ParallaxStepTracker {
            return fromBounds(direction, outline.sourceObservation, outline.bounds, pixels)
        }

        internal fun fromBounds(
            direction: StepDirection,
            source: Observation,
            subjectBox: FaceBox,
            pixels: SubjectPixels,
        ): ParallaxStepTracker {
            val padX = 0.06
            val padY = 0.05
            val samples = mutableListOf<BgSample>()
            for (row in 1..5) {
                val y = row / 6.0
                for (col in 1..5) {
                    val x = col / 6.0
                    val insideSubject = x in (subjectBox.left - padX)..(subjectBox.right + padX) &&
                        y in (subjectBox.top - padY)..(subjectBox.bottom + padY)
                    if (insideSubject) continue
                    val color = pixels.at(x, y) ?: continue
                    samples += BgSample(Uv(x, y), color shr 16)
                }
            }
            return ParallaxStepTracker(direction, source, samples)
        }
    }
}
