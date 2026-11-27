package com.lumi.camera.vision

import com.lumi.camera.camera.CameraCapabilities
import com.lumi.camera.camera.LiveTelemetry
import com.lumi.camera.guidance.Uv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class S8LightFinderTest {

    @Test
    fun `detects strong backlight on portrait with actionable rim-light tip`() {
        val g = LightFinder.analyze(
            LightSample(
                meanLuma = 148,
                leftMean = 155,
                rightMean = 152,
                topMean = 170,
                bottomMean = 130,
                subjectVsBgDiff = -34,
                hasFace = true,
            ),
        )
        assertEquals(LightPosition.BACKLIT, g.position)
        assertTrue(g.isActionable)
        assertTrue(g.tip.contains("侧身"))
    }

    @Test
    fun `detects harsh overhead top light when upper face is much brighter than chin`() {
        val g = LightFinder.analyze(
            LightSample(
                meanLuma = 140,
                leftMean = 138,
                rightMean = 142,
                topMean = 172,
                bottomMean = 118,
                subjectVsBgDiff = 4,
                subjectTopMean = 178,
                subjectBottomMean = 136,
                hasFace = true,
            ),
        )
        assertEquals(LightPosition.TOP_HARSH, g.position)
        assertTrue(g.isActionable)
        assertTrue(g.tip.contains("阴影"))
    }

    @Test
    fun `detects left and right side light and feeds promptTag into LiveTelemetry`() {
        val left = LightFinder.analyze(
            LightSample(
                meanLuma = 132,
                leftMean = 162,
                rightMean = 114,
                topMean = 136,
                bottomMean = 128,
                subjectLeftMean = 156,
                subjectRightMean = 124,
                highlightSpot = Uv(0.18, 0.32),
                hasFace = true,
            ),
        )
        assertEquals(LightPosition.LEFT_SIDE, left.position)
        assertTrue(left.badgeText.contains("←"))

        val right = LightFinder.analyze(
            LightSample(
                meanLuma = 132,
                leftMean = 110,
                rightMean = 158,
                topMean = 135,
                bottomMean = 129,
                subjectLeftMean = 118,
                subjectRightMean = 152,
                hasFace = true,
            ),
        )
        assertEquals(LightPosition.RIGHT_SIDE, right.position)
        assertTrue(right.badgeText.contains("→"))

        val summary = LiveTelemetry(ambientLux = 800f, lightGuidance = left)
            .promptSummary(CameraCapabilities())
        assertTrue(summary != null && summary.contains("主光方位:左侧主光"))
    }

    @Test
    fun `low light and soft front light classify accurately`() {
        val low = LightFinder.analyze(
            LightSample(meanLuma = 38, leftMean = 40, rightMean = 36, topMean = 40, bottomMean = 35),
        )
        assertEquals(LightPosition.LOW_LIGHT, low.position)
        assertTrue(low.isActionable)

        val front = LightFinder.analyze(
            LightSample(
                meanLuma = 138,
                leftMean = 136,
                rightMean = 139,
                topMean = 142,
                bottomMean = 134,
                subjectVsBgDiff = 26,
                hasFace = true,
            ),
        )
        assertEquals(LightPosition.FRONT, front.position)
        assertFalse(front.isActionable)
    }
}
