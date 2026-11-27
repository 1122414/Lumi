package com.lumi.camera.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class S7CloseupFramingTest {

    @Test
    fun `panoramic portrait generates half-body and face closeup boxes snapped to optical zoom stops`() {
        val distantFace = FaceBox(0.44, 0.28, 0.56, 0.42) // small face in wide 1x shot
        val proposals = CloseupFraming.generate(
            faces = listOf(distantFace),
            subjectBounds = null,
            currentZoom = 1.0f,
            zoomStops = listOf(0.6f, 1.0f, 2.0f, 3.0f),
            maxZoom = 8.0f,
        )
        assertEquals(2, proposals.size)
        assertEquals(CloseupKind.HALF_BODY, proposals[0].kind)
        assertEquals(CloseupKind.FACE, proposals[1].kind)
        assertTrue(proposals[0].targetZoom >= 1.5f)
        assertTrue(proposals[1].targetZoom >= proposals[0].targetZoom)
        assertEquals(2.0f, proposals[0].targetZoom, 0.01f)
        assertEquals(3.0f, proposals[1].targetZoom, 0.01f)
    }

    @Test
    fun `closeup proposals stay hidden once user is already zoomed in`() {
        val face = FaceBox(0.40, 0.25, 0.55, 0.42)
        val proposals = CloseupFraming.generate(
            faces = listOf(face),
            subjectBounds = null,
            currentZoom = 2.0f,
            zoomStops = listOf(0.6f, 1.0f, 2.0f, 3.0f),
            maxZoom = 8.0f,
        )
        assertTrue(proposals.isEmpty())
    }

    @Test
    fun `scenic wide shot without face generates light-detail closeup around subject box`() {
        val scenicSubject = FaceBox(0.38, 0.36, 0.62, 0.62)
        val proposals = CloseupFraming.generate(
            faces = emptyList(),
            subjectBounds = scenicSubject,
            currentZoom = 1.0f,
            zoomStops = listOf(1.0f, 2.0f, 4.0f),
            maxZoom = 8.0f,
        )
        assertEquals(1, proposals.size)
        assertEquals(CloseupKind.LIGHT_DETAIL, proposals[0].kind)
        assertEquals(2.0f, proposals[0].targetZoom, 0.01f)
    }

    @Test
    fun `post shot recrop adds half-body and face closeup options when face is detected in wide shot`() {
        val distantFace = FaceBox(0.44, 0.26, 0.56, 0.40)
        val proposals = PostShotRecrop.generateProposals(
            subjectBounds = FaceBox(0.36, 0.24, 0.64, 0.84),
            faceBox = distantFace,
        )
        assertTrue(proposals.any { it.id == "half_body_closeup" })
        assertTrue(proposals.any { it.id == "face_closeup" })
    }
}
