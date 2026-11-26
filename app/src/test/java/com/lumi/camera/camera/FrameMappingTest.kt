package com.lumi.camera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameMappingTest {
    private val crop = Crop(80, 40, 560, 400)
    private fun mapping(degrees: Int, size: Extent, scale: CenterScale = CenterScale.FILL) =
        FrameMapping(FrameGeometry(Extent(640, 480), crop, degrees), size, scale)
    private fun check(expected: Point, actual: Point?) {
        requireNotNull(actual)
        assertEquals(expected.x.toDouble(), actual.x.toDouble(), 0.001)
        assertEquals(expected.y.toDouble(), actual.y.toDouble(), 0.001)
    }

    @Test fun rotationsUseCroppedBufferOriginAndClockwiseDisplayCoordinates() {
        val raw = Point(200f, 130f)
        for ((degrees, ref) in listOf(0 to Point(120f, 90f), 90 to Point(270f, 120f),
            180 to Point(360f, 270f), 270 to Point(90f, 360f))) {
            val size = if (degrees % 180 == 0) Extent(960, 720) else Extent(720, 960)
            val m = mapping(degrees, size)
            check(ref, m.toReference(raw))
            val screen = Point(2 * ref.x, 2 * ref.y)
            check(screen, m.toPreview(raw))
            check(raw, m.previewToAnalysis(screen))
        }
    }

    @Test fun fillCenterSquarePreviewClipsTheLongDimension() {
        val m = mapping(90, Extent(720, 720))
        // Upright crop is 360×480; scale 2 and trim 120 pixels at top and bottom.
        check(Point(720f, -120f), m.toPreview(Point(80f, 40f)))
        check(Point(140f, 220f), m.previewToAnalysis(Point(360f, 0f)))
        check(Point(500f, 220f), m.previewToAnalysis(Point(360f, 720f)))
    }

    @Test fun uprightDetectorPointAndProjectedRingShareTheCroppedPreview() {
        val raw = Point(200f, 130f)
        val fullImagePoints = listOf(Point(200f, 130f), Point(350f, 200f),
            Point(440f, 350f), Point(130f, 440f))
        for ((quarterTurns, full) in fullImagePoints.withIndex()) {
            val m = mapping(quarterTurns * 90, Extent(333, 517))
            val ref = m.frame.uprightToReference(full)
            check(raw, m.frame.referenceToAnalysis(ref.x, ref.y))
            check(m.toPreview(raw), m.referenceToPreview(ref))
        }
    }

    @Test fun fitCenterLetterboxRejectsTapsOutsideTheImage() {
        val m = mapping(0, Extent(960, 960), CenterScale.FIT)
        check(Point(0f, 120f), m.toPreview(Point(80f, 40f)))
        check(Point(560f, 400f), m.previewToAnalysis(Point(960f, 840f)))
        assertNull(m.previewToAnalysis(Point(480f, 119f)))
        assertNull(m.previewToAnalysis(Point(480f, 841f)))
        assertNull(m.previewToAnalysis(Point(-1f, 300f)))
    }

    @Test fun landscapeAnalysisWithPortraitViewportUsesTheCropNotTheWholeSensor() {
        val m = FrameMapping(FrameGeometry(Extent(1280, 720), Crop(160, 0, 1120, 720), 90),
            Extent(1080, 1440))
        check(Point(1080f, 0f), m.toPreview(Point(160f, 0f)))
        check(Point(0f, 1440f), m.toPreview(Point(1120f, 720f)))
        check(Point(480f, 240f), m.previewToAnalysis(Point(720f, 480f)))
    }

    @Test fun fractionalPixelsRoundTripUnderBothScalesAfterResize() {
        val raw = Point(319.5f, 219.5f)
        for (scale in CenterScale.entries) for (rot in listOf(0, 90, 180, 270)) {
            for (view in listOf(Extent(333, 517), Extent(517, 333))) {
                val m = mapping(rot, view, scale)
                check(raw, m.previewToAnalysis(m.toPreview(raw)))
            }
        }
    }

    @Test fun mirroredFrontCameraFlipsHorizontalCoordinatesAcrossAllRotations() {
        val raw = Point(200f, 130f)
        val fullImagePoints = listOf(Point(200f, 130f), Point(350f, 200f),
            Point(440f, 350f), Point(130f, 440f))
        for ((quarterTurns, full) in fullImagePoints.withIndex()) {
            val degrees = quarterTurns * 90
            val view = if (degrees % 180 == 0) Extent(960, 720) else Extent(720, 960)
            val normal = FrameMapping(FrameGeometry(Extent(640, 480), crop, degrees, mirrored = false), view)
            val mirrored = FrameMapping(FrameGeometry(Extent(640, 480), crop, degrees, mirrored = true), view)
            val ref = mirrored.frame.uprightToReference(full)
            val unmirroredRef = normal.frame.uprightToReference(full)
            assertEquals(mirrored.frame.referenceSize.width - unmirroredRef.x.toDouble(), ref.x.toDouble(), 0.001)
            assertEquals(unmirroredRef.y.toDouble(), ref.y.toDouble(), 0.001)
            check(ref, mirrored.toReference(raw))
            check(raw, mirrored.frame.referenceToAnalysis(ref.x, ref.y))
            check(mirrored.toPreview(raw), mirrored.referenceToPreview(ref))
            check(ref, mirrored.previewToReference(mirrored.referenceToPreview(ref)))
            check(raw, mirrored.previewToAnalysis(mirrored.toPreview(raw)))
        }
    }
}
