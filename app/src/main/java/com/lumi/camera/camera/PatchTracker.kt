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
    private val tile = IntArray(MAX_SIDE * MAX_SIDE)
    private val horizontal = IntArray(MAX_SIDE * (MAX_SIDE + 4))

    fun select(y: Luma, c: Crop, p: Point): Point? {
        active = false
        matchedFrames = 0
        failure = null
        cx = p.x.toInt()
        cy = p.y.toInt()
        if (cx - MARGIN < c.left || cy - MARGIN < c.top ||
            cx + MARGIN >= c.right || cy + MARGIN >= c.bottom) {
            failure = PatchFailure.EDGE
            return null
        }
        return match(y, c, selecting = true)
    }

    private fun samplePatch(base: Int, stride: Int): Boolean {
        var k = 0
        for (j in 0..2 * RADIUS step 2) for (i in 0..2 * RADIUS step 2) {
            sample[k++] = tile[base + j * stride + i].toDouble()
        }
        val mean = sample.average()
        for (n in sample.indices) sample[n] -= mean
        val norm = sqrt(sample.sumOf { it * it })
        if (norm < MIN_STD * sqrt(COUNT.toDouble())) return false
        for (n in sample.indices) sample[n] /= norm
        return true
    }

    fun follow(y: Luma, c: Crop): Point? = if (active) match(y, c, selecting = false) else null

    // Low-pass before sampling every other pixel; both template and candidates use the same filter.
    private fun loadTile(y: Luma, left: Int, top: Int, width: Int, height: Int) {
        for (j in 0 until height + 4) {
            val row = top + j - 2
            for (i in 0 until width) {
                val x = left + i
                horizontal[j * width + i] = y[x - 2, row] + 4 * y[x - 1, row] +
                    6 * y[x, row] + 4 * y[x + 1, row] + y[x + 2, row]
            }
        }
        for (at in 0 until width * height) {
            tile[at] = (horizontal[at] + 4 * horizontal[at + width] +
                6 * horizontal[at + 2 * width] + 4 * horizontal[at + 3 * width] +
                horizontal[at + 4 * width] + 128) / 256
        }
    }

    private fun match(y: Luma, c: Crop, selecting: Boolean): Point? {
        val x0 = max(c.left + MARGIN, cx - SEARCH)
        val x1 = min(c.right - 1 - MARGIN, cx + SEARCH)
        val y0 = max(c.top + MARGIN, cy - SEARCH)
        val y1 = min(c.bottom - 1 - MARGIN, cy + SEARCH)
        val width = x1 - x0 + 1
        val stride = width + 2 * RADIUS
        loadTile(y, x0 - RADIUS, y0 - RADIUS, stride, y1 - y0 + 1 + 2 * RADIUS)
        if (selecting) {
            if (!samplePatch((cy - y0) * stride + cx - x0, stride)) {
                failure = PatchFailure.LOW_TEXTURE
                return null
            }
            sample.copyInto(template)
        }
        var best = -1.0
        var bx = cx
        var by = cy
        var at = 0
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
        // Refresh only on a changed, well-centered appearance. Learning every tiny shift
        // would move the template across the object while the reported center stays still.
        val k = (by - y0) * width + bx - x0
        if (!selecting && best in 0.90..0.98 && best - second >= 0.12 &&
            bx in (x0 + 1) until x1 && by in (y0 + 1) until y1 &&
            centeredPeak(scores[k - 1], best, scores[k + 1]) &&
            centeredPeak(scores[k - width], best, scores[k + width]) &&
            samplePatch((by - y0) * stride + bx - x0, stride)) {
            for (i in template.indices) template[i] = 0.25 * template[i] + 0.75 * sample[i]
            val norm = sqrt(template.sumOf { it * it })
            for (i in template.indices) template[i] /= norm
        }
        return Point(cx + 0.5f, cy + 0.5f)
    }

    private fun centeredPeak(left: Double, peak: Double, right: Double): Boolean {
        val curvature = 2 * peak - left - right
        // A parabola through the three scores peaks at (right - left) / (2 * curvature).
        return curvature > 0 && abs(right - left) <= 2 * curvature * MAX_UPDATE_OFFSET_PX
    }

    private companion object {
        const val RADIUS = 8
        const val COUNT = 81
        const val SEARCH = 18
        const val MAX_UPDATE_OFFSET_PX = 0.2
        const val MARGIN = RADIUS + 2
        const val MAX_SIDE = 2 * (SEARCH + RADIUS) + 1
        const val MIN_STD = 7.0
    }
}
