package com.lumi.camera.vision

import com.lumi.camera.guidance.Uv
import org.junit.Assert.assertEquals
import org.junit.Test

class SubjectSegmenterTest {
    @Test fun androidPromptUsesPixelsOfEachInputBitmap() {
        val seed = Uv(0.36, 0.49)
        val full = magicTouchKeypoint(seed, 192, 256)
        assertEquals(69f, full.x(), 0f)
        assertEquals(125f, full.y(), 0f)
        val crop = magicTouchKeypoint(seed, 140, 187)
        assertEquals(50f, crop.x(), 0f)
        assertEquals(91f, crop.y(), 0f)
    }

    @Test fun promptNearBottomRightStaysOnLastPixel() {
        val point = magicTouchKeypoint(Uv(0.99999, 0.99999), 140, 187)
        assertEquals(139f, point.x(), 0f)
        assertEquals(186f, point.y(), 0f)
    }
}
