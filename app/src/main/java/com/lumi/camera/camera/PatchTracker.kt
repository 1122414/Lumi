package com.lumi.camera.camera

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class Luma(private val bytes: ByteBuffer, private val rowStride: Int, private val pixelStride: Int) {
    private val start = bytes.position()
    operator fun get(x: Int, y: Int): Int =
        bytes.get(start + y * rowStride + x * pixelStride).toInt() and 0xff
}

// M1 tap verifier: an isolated, textured patch in the Y plane, not a semantic object tracker.
enum class PatchFailure { EDGE, OUTSIDE, LOW_TEXTURE, WEAK_MATCH, AMBIGUOUS }

class PatchTracker {
    var failure: PatchFailure? = null
        private set
    var matchedFrames = 0
        private set
    private val template = DoubleArray(COUNT)
    private val sample = DoubleArray(COUNT)
    private var cx = 0
    private var cy = 0
    private var active = false
    private val scores = DoubleArray((SEARCH * 2 + 1) * (SEARCH * 2 + 1))
    private val tile = IntArray((2 * (SEARCH + RADIUS) + 1) * (2 * (SEARCH + RADIUS) + 1))

    fun select(y: Luma, c: Crop, p: Point): Point? {
        active = false
        matchedFrames = 0
        failure = null
        cx = p.x.toInt()
        cy = p.y.toInt()
        if (cx - RADIUS < c.left || cy - RADIUS < c.top ||
            cx + RADIUS >= c.right || cy + RADIUS >= c.bottom) {
            failure = PatchFailure.EDGE
            return null
        }
        if (!samplePatch(y)) {
            failure = PatchFailure.LOW_TEXTURE
            return null
        }
        sample.copyInto(template)
        active = true
        // Reject repetitive texture even on the selection frame.
        return follow(y, c)
    }

    private fun samplePatch(y: Luma): Boolean {
        var k = 0
        for (j in -RADIUS..RADIUS step 2) for (i in -RADIUS..RADIUS step 2) {
            sample[k++] = y[cx + i, cy + j].toDouble()
        }
        val mean = sample.average()
        for (n in sample.indices) sample[n] -= mean
        val norm = sqrt(sample.sumOf { it * it })
        if (norm < MIN_STD * sqrt(COUNT.toDouble())) return false
        for (n in sample.indices) sample[n] /= norm
        return true
    }

    fun follow(y: Luma, c: Crop): Point? {
        if (!active) return null
        val x0 = max(c.left + RADIUS, cx - SEARCH)
        val x1 = min(c.right - 1 - RADIUS, cx + SEARCH)
        val y0 = max(c.top + RADIUS, cy - SEARCH)
        val y1 = min(c.bottom - 1 - RADIUS, cy + SEARCH)
        val width = x1 - x0 + 1
        val stride = width + 2 * RADIUS
        var at = 0
        for (j in y0 - RADIUS..y1 + RADIUS) for (i in x0 - RADIUS..x1 + RADIUS) {
            tile[at++] = y[i, j]
        }
        var best = -1.0
        var bx = cx
        var by = cy
        at = 0
        for (j in y0..y1) for (i in x0..x1) {
            var sum = 0
            var squares = 0
            var dot = 0.0
            var k = 0
            val base = (j - y0) * stride + i - x0
            for (v in 0..2 * RADIUS step 2) for (u in 0..2 * RADIUS step 2) {
                val value = tile[base + v * stride + u]
                sum += value
                squares += value * value
                dot += template[k++] * value
            }
            val energy = squares - sum.toDouble() * sum / COUNT
            val score = if (energy >= COUNT * MIN_STD * MIN_STD) dot / sqrt(energy) else -1.0
            scores[at++] = score
            if (score > best) { best = score; bx = i; by = j }
        }
        var second = -1.0
        at = 0
        for (j in y0..y1) for (i in x0..x1) {
            if (abs(i - bx) > 4 || abs(j - by) > 4) second = max(second, scores[at])
            at++
        }
        active = best >= 0.82 && best - second >= 0.06
        if (!active) {
            failure = if (best < 0.82) PatchFailure.WEAK_MATCH else PatchFailure.AMBIGUOUS
            return null
        }
        matchedFrames++
        cx = bx
        cy = by
        // Follow gradual appearance changes only after a strong, isolated match.
        if (best >= 0.90 && best - second >= 0.12 && samplePatch(y)) {
            for (i in template.indices) template[i] = 0.25 * template[i] + 0.75 * sample[i]
            val norm = sqrt(template.sumOf { it * it })
            for (i in template.indices) template[i] /= norm
        }
        return Point(cx + 0.5f, cy + 0.5f)
    }

    private companion object {
        const val RADIUS = 8
        const val COUNT = 81
        const val SEARCH = 18
        const val MIN_STD = 7.0
    }
}
