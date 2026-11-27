package com.lumi.camera.vision

import com.lumi.camera.guidance.Uv
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

internal enum class CloseupKind(val id: String, val label: String) {
    HALF_BODY("half_body", "半身特写"),
    FACE("face", "脸部特写"),
}

internal data class CloseupProposal(
    val id: String,
    val kind: CloseupKind,
    val box: FaceBox,
    val targetZoom: Float,
    val aimUv: Uv = Uv((box.left + box.right) / 2.0, (box.top + box.bottom) / 2.0),
) {
    val chipLabel: String
        get() {
            val z = String.format(Locale.ROOT, "%.1f", targetZoom).removeSuffix(".0")
            return "${kind.label} · ${z}×"
        }
}

internal object CloseupFraming {
    private const val MAX_WIDE_ZOOM = 1.35f
    private const val DISTANT_FACE_MAX_W = 0.24
    private const val DISTANT_FACE_MAX_H = 0.26

    fun snapZoomToOpticalStop(
        desiredZoom: Float,
        currentZoom: Float,
        zoomStops: List<Float>,
        maxZoom: Float,
    ): Float {
        val safeMax = max(maxZoom, currentZoom)
        val clamped = desiredZoom.coerceIn(max(currentZoom + 0.4f, 1.5f), safeMax)
        val higherStops = zoomStops.filter { it > currentZoom + 0.35f && it <= safeMax + 0.05f }
        val nearestOptical = higherStops.minByOrNull { abs(it - clamped) }
        if (nearestOptical != null && abs(nearestOptical - clamped) / clamped <= 0.34f) {
            return nearestOptical
        }
        return (round(clamped * 2f) / 2f).coerceIn(1.5f, safeMax)
    }

    // Strict rule: only suggest portrait close-up boxes AFTER AI composition has selected a real person
    // who appears distant in a wide/standard shot. Never draw boxes on idle views, desks, or objects.
    fun generateForSelectedPerson(
        selectedPersonBounds: FaceBox?,
        faces: List<FaceBox>,
        currentZoom: Float,
        zoomStops: List<Float>,
        maxZoom: Float,
    ): List<CloseupProposal> {
        if (selectedPersonBounds == null) return emptyList()
        if (currentZoom > MAX_WIDE_ZOOM || maxZoom < currentZoom + 0.5f) return emptyList()
        if (faces.isEmpty()) return emptyList()

        // Match a detected face belonging to (or overlapping) the AI-selected person.
        val matchingFace = faces.filter { face ->
            val fcx = (face.left + face.right) / 2.0
            val fcy = (face.top + face.bottom) / 2.0
            val padX = (selectedPersonBounds.right - selectedPersonBounds.left) * 0.15
            val padY = (selectedPersonBounds.bottom - selectedPersonBounds.top) * 0.15
            fcx in (selectedPersonBounds.left - padX)..(selectedPersonBounds.right + padX) &&
                fcy in (selectedPersonBounds.top - padY)..(selectedPersonBounds.bottom + padY)
        }.maxByOrNull { (it.right - it.left) * (it.bottom - it.top) } ?: return emptyList()

        val fw = (matchingFace.right - matchingFace.left).coerceIn(0.03, 0.95)
        val fh = (matchingFace.bottom - matchingFace.top).coerceIn(0.03, 0.95)
        if (fw > DISTANT_FACE_MAX_W || fh > DISTANT_FACE_MAX_H) return emptyList()

        val fcx = (matchingFace.left + matchingFace.right) / 2.0
        val fcy = (matchingFace.top + matchingFace.bottom) / 2.0
        val proposals = mutableListOf<CloseupProposal>()

        // 1. Half-body portrait close-up
        val halfSize = max(fw * 3.1, fh * 2.7).coerceIn(0.42, 0.62)
        val halfCenterY = (fcy + fh * 0.72).coerceIn(halfSize / 2.0, 1.0 - halfSize / 2.0)
        val halfBox = clampedSquareBox(fcx, halfCenterY, halfSize)
        val halfZoom = snapZoomToOpticalStop(
            desiredZoom = (currentZoom / halfSize.toFloat()).coerceIn(1.6f, 4.0f),
            currentZoom = currentZoom,
            zoomStops = zoomStops,
            maxZoom = maxZoom,
        )
        proposals += CloseupProposal(
            id = "closeup_half_body",
            kind = CloseupKind.HALF_BODY,
            box = halfBox,
            targetZoom = halfZoom,
        )

        // 2. Face / head-and-shoulders close-up when face is far enough to punch in cleanly
        if (fw <= 0.20 && fh <= 0.22) {
            val faceCropSize = max(fw * 2.1, fh * 1.9).coerceIn(0.26, 0.40)
            val faceCenterY = (fcy + fh * 0.12).coerceIn(faceCropSize / 2.0, 1.0 - faceCropSize / 2.0)
            val faceBox = clampedSquareBox(fcx, faceCenterY, faceCropSize)
            val faceZoom = snapZoomToOpticalStop(
                desiredZoom = (currentZoom / faceCropSize.toFloat()).coerceIn(2.0f, 5.0f),
                currentZoom = currentZoom,
                zoomStops = zoomStops,
                maxZoom = maxZoom,
            )
            if (abs(faceZoom - halfZoom) >= 0.4f) {
                proposals += CloseupProposal(
                    id = "closeup_face",
                    kind = CloseupKind.FACE,
                    box = faceBox,
                    targetZoom = faceZoom,
                )
            }
        }

        return proposals.take(2)
    }

    private fun clampedSquareBox(cx: Double, cy: Double, size: Double): FaceBox {
        val s = size.coerceIn(0.24, 0.70)
        val half = s / 2.0
        val left = (cx - half).coerceIn(0.03, 0.97 - s)
        val top = (cy - half).coerceIn(0.03, 0.97 - s)
        return FaceBox(
            left = left,
            top = top,
            right = min(0.97, left + s),
            bottom = min(0.97, top + s),
        )
    }
}
