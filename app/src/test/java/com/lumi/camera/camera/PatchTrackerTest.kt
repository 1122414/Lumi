package com.lumi.camera.camera

import java.nio.ByteBuffer
import java.util.Random
import org.junit.Assert.*
import org.junit.Test

class PatchTrackerTest {
    private val crop = Crop(6, 4, 92, 86)
    private val noise = IntArray(96 * 90).also { data ->
        val random = Random(582)
        for (i in data.indices) data[i] = 20 + random.nextInt(180)
    }
    private fun image(value: (Int, Int) -> Int): Luma {
        // Exercise prefixed, padded, non-packed buffers as well as the matcher itself.
        val bytes = ByteBuffer.allocate(11 + 206 * 90)
        bytes.position(11)
        for (y in 0 until 90) for (x in 0 until 96)
            bytes.put(11 + y * 206 + 2 * x, value(x, y).toByte())
        return Luma(bytes, 206, 2)
    }
    private fun shifted(dx: Int = 0, dy: Int = 0, exposure: Int = 0) = image { x, y ->
        noise[(y - dy).coerceIn(0, 89) * 96 + (x - dx).coerceIn(0, 95)] + exposure
    }

    @Test fun tapFollowsTranslatedFeatureUnderExposureChangeAndMapsBackToPortrait() {
        val tracker = PatchTracker()
        val m = FrameMapping(FrameGeometry(Extent(96, 90), crop, 90), Extent(164, 172))
        val tap = Point(91f, 71f)
        val selected = tracker.select(shifted(), crop, m.previewToAnalysis(tap)!!)
        assertEquals(tap, m.toPreview(requireNotNull(selected)))
        val next = tracker.follow(shifted(9, -7, 25), crop)
        assertEquals(Point(105f, 89f), m.toPreview(requireNotNull(next)))
    }

    @Test fun gradualInPlaneRotationKeepsTheSelectedCenter() {
        val tracker = PatchTracker()
        assertNotNull(tracker.select(shifted(), crop, Point(42.5f, 42.5f)))
        for (degree in 1..45) {
            val a = Math.toRadians(degree.toDouble())
            val frame = image { x, y ->
                val u = (42 + (x - 42) * kotlin.math.cos(a) + (y - 42) * kotlin.math.sin(a)).coerceIn(0.0, 94.0)
                val v = (42 - (x - 42) * kotlin.math.sin(a) + (y - 42) * kotlin.math.cos(a)).coerceIn(0.0, 88.0)
                val ix = u.toInt(); val iy = v.toInt()
                val fx = u - ix; val fy = v - iy
                fun row(j: Int) = (1 - fx) * noise[j * 96 + ix] + fx * noise[j * 96 + ix + 1]
                ((1 - fy) * row(iy) + fy * row(iy + 1)).toInt()
            }
            assertEquals("rotation: $degree", Point(42.5f, 42.5f), tracker.follow(frame, crop))
        }
    }

    @Test fun lowTextureAndEdgeTapsAreRejected() {
        val tracker = PatchTracker()
        assertNull(tracker.select(image { _, _ -> 90 }, crop, Point(42f, 42f)))
        assertEquals(PatchFailure.LOW_TEXTURE, tracker.failure)
        assertEquals(0, tracker.matchedFrames)
        assertNull(tracker.select(shifted(), crop, Point(10f, 12f)))
        assertEquals(PatchFailure.EDGE, tracker.failure)
    }

    @Test fun ambiguousRepeatedTextureDoesNotProduceAMarker() {
        val tracker = PatchTracker()
        val periodic = image { x, y -> if ((x / 4 + y / 4) % 2 == 0) 30 else 180 }
        assertNull(tracker.select(periodic, crop, Point(45f, 45f)))
        assertEquals(PatchFailure.AMBIGUOUS, tracker.failure)
    }

    @Test fun occlusionClearsTrackUntilNewTapInsteadOfReusingLastPoint() {
        val tracker = PatchTracker()
        assertNotNull(tracker.select(shifted(), crop, Point(42f, 42f)))
        assertNull(tracker.follow(image { _, _ -> 63 }, crop))
        assertEquals(PatchFailure.WEAK_MATCH, tracker.failure)
        assertEquals(1, tracker.matchedFrames)
        assertNull(tracker.follow(shifted(), crop))
        assertNotNull(tracker.select(shifted(), crop, Point(42f, 42f)))
        assertNull(tracker.failure)
        assertEquals(1, tracker.matchedFrames)
    }
}
