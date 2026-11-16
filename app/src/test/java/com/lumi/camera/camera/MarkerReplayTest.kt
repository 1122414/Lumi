package com.lumi.camera.camera

import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkerReplayTest {
    private val buffer = Extent(120, 96)
    private val crop = Crop(6, 8, 114, 88)

    // A printed E on paper, sampled at four locations per pixel. Coordinates are
    // relative to the original tap near the top-left corner, not the frame center.
    private fun ink(x: Double, y: Double): Boolean =
        (x in -1.5..1.5 && y in -1.5..16.0) ||
            (x in -1.5..12.0 && y in -1.5..1.5) ||
            (x in 0.0..8.0 && y in 5.0..7.5) ||
            (x in 0.0..10.0 && y in 13.0..16.0)

    private fun render(x: Double, y: Double, degrees: Double, covered: Boolean): Luma {
        val a = Math.toRadians(degrees)
        val c = cos(a)
        val s = sin(a)
        val data = ByteArray(buffer.width * buffer.height)
        for (row in 0 until buffer.height) for (col in 0 until buffer.width) {
            var dark = 0
            for (sy in 0..1) for (sx in 0..1) {
                val dx = col + 0.25 + 0.5 * sx - x
                val dy = row + 0.25 + 0.5 * sy - y
                if (!covered && ink(c * dx + s * dy, -s * dx + c * dy)) dark++
            }
            data[row * buffer.width + col] = (225 - 48 * dark).toByte()
        }
        return Luma(ByteBuffer.wrap(data), buffer.width, 1)
    }

    @Test fun portraitMarkerFollowsTheSameStrokeThroughMotionOcclusionAndReturn() {
        val mapping = FrameMapping(FrameGeometry(buffer, crop, 90), Extent(200, 240))
        val tracker = PatchTracker()
        // The upright crop is 80 x 108, scaled by 2.5; FILL trims 15 px at the top.
        val tap = Point(118.75f, 91.25f) // analysis pixel center (48.5, 40.5)
        val rawTap = requireNotNull(mapping.previewToAnalysis(tap))
        for (index in 0..78) {
            val phase = if (index <= 39) index else 78 - index
            val x = 48.5 + 0.22 * phase
            val y = 40.5 + 0.10 * phase
            val frame = render(x, y, 0.32 * phase, covered = index == 15)
            val raw = if (index == 0) tracker.select(frame, crop, rawTap, 0)
                else tracker.follow(frame, crop, index * 33_000_000L)
            if (index == 15 || index == 16) {
                assertNull("frame $index must not display a marker", raw)
                continue
            }
            assertNotNull("lost the E on frame $index: ${tracker.failure}", raw)
            val visible = mapping.toPreview(requireNotNull(raw))
            val error = hypot(visible.x - (118.75 - (y - 40.5) * 2.5),
                visible.y - (91.25 + (x - 48.5) * 2.5))
            assertTrue("frame $index: $error preview px from the stroke", error <= 5.0)
        }
    }
}
