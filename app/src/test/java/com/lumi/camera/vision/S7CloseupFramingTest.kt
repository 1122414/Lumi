package com.lumi.camera.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class S7CloseupFramingTest {

    @Test
    fun `selected distant person generates half-body and face closeup boxes snapped to optical stops`() {
        val selectedPerson = FaceBox(0.36, 0.24, 0.64, 0.84)
        val distantFace = FaceBox(0.44, 0.28, 0.56, 0.42)
        val proposals = CloseupFraming.generateForSelectedPerson(
            selectedPersonBounds = selectedPerson,
            faces = listOf(distantFace),
            currentZoom = 1.0f,
            zoomStops = listOf(0.6f, 1.0f, 2.0f, 3.0f),
            maxZoom = 8.0f,
        )
        assertEquals(2, proposals.size)
        assertEquals(CloseupKind.HALF_BODY, proposals[0].kind)
        assertEquals(CloseupKind.FACE, proposals[1].kind)
        assertEquals(2.0f, proposals[0].targetZoom, 0.01f)
        assertEquals(3.0f, proposals[1].targetZoom, 0.01f)
    }

    @Test
    fun `closeup boxes stay hidden when no subject selected or no matching face`() {
        val distantFace = FaceBox(0.44, 0.28, 0.56, 0.42)
        assertTrue(
            CloseupFraming.generateForSelectedPerson(
                selectedPersonBounds = null,
                faces = listOf(distantFace),
                currentZoom = 1.0f,
                zoomStops = listOf(1.0f, 2.0f, 3.0f),
                maxZoom = 8.0f,
            ).isEmpty(),
        )
        assertTrue(
            CloseupFraming.generateForSelectedPerson(
                selectedPersonBounds = FaceBox(0.36, 0.24, 0.64, 0.84),
                faces = emptyList(),
                currentZoom = 1.0f,
                zoomStops = listOf(1.0f, 2.0f, 3.0f),
                maxZoom = 8.0f,
            ).isEmpty(),
        )
    }

    @Test
    fun `closeup proposals stay hidden when person is already close or user is zoomed in`() {
        val closeFace = FaceBox(0.32, 0.18, 0.68, 0.54)
        assertTrue(
            CloseupFraming.generateForSelectedPerson(
                selectedPersonBounds = FaceBox(0.20, 0.14, 0.80, 0.92),
                faces = listOf(closeFace),
                currentZoom = 1.0f,
                zoomStops = listOf(1.0f, 2.0f, 3.0f),
                maxZoom = 8.0f,
            ).isEmpty(),
        )
        assertTrue(
            CloseupFraming.generateForSelectedPerson(
                selectedPersonBounds = FaceBox(0.36, 0.24, 0.64, 0.84),
                faces = listOf(FaceBox(0.44, 0.28, 0.56, 0.42)),
                currentZoom = 2.0f,
                zoomStops = listOf(1.0f, 2.0f, 3.0f),
                maxZoom = 8.0f,
            ).isEmpty(),
        )
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
