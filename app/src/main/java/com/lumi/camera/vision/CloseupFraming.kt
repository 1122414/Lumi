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
    LIGHT_DETAIL("light_detail", "光影特写"),
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

    fun generate(
        faces: List<FaceBox>,
        subjectBounds: FaceBox?,
        currentZoom: Float,
        zoomStops: List<Float>,
        maxZoom: Float,
        highlightSpot: Uv? = null,
    ): List<CloseupProposal> {
        if (currentZoom > MAX_WIDE_ZOOM || maxZoom < currentZoom + 0.5f) return emptyList()
        val proposals = mutableListOf<CloseupProposal>()

        // Pick primary face (largest detected face in panoramic/full-body range).
        val primaryFace = faces.maxByOrNull { (it.right - it.left) * (it.bottom - it.top) }
        if (primaryFace != null) {
            val fw = (primaryFace.right - primaryFace.left).coerceIn(0.03, 0.95)
            val fh = (primaryFace.bottom - primaryFace.top).coerceIn(0.03, 0.95)
            val fcx = (primaryFace.left + primaryFace.right) / 2.0
            val fcy = (primaryFace.top + primaryFace.bottom) / 2.0

            // Half-body close-up when subject appears distant / full-body in wide shot.
            if (fw <= 0.30 && fh <= 0.32) {
                val halfSize = max(fw * 3.1, fh * 2.7).coerceIn(0.42, 0.62)
                val halfCenterY = (fcy + fh * 0.72).coerceIn(halfSize / 2.0, 1.0 - halfSize / 2.0)
                val halfBox = clampedSquareBox(fcx, halfCenterY, halfSize)
                val rawZoom = (currentZoom / halfSize.toFloat()).coerceIn(1.6f, 4.0f)
                val targetZoom = snapZoomToOpticalStop(rawZoom, currentZoom, zoomStops, maxZoom)
                proposals += CloseupProposal(
                    id = "closeup_half_body",
                    kind = CloseupKind.HALF_BODY,
                    box = halfBox,
                    targetZoom = targetZoom,
                )
            }

            // Face / head-and-shoulders close-up when face is small enough to punch in cleanly.
            if (fw <= 0.24 && fh <= 0.26) {
                val faceCropSize = max(fw * 2.1, fh * 1.9).coerceIn(0.26, 0.42)
                val faceCenterY = (fcy + fh * 0.12).coerceIn(faceCropSize / 2.0, 1.0 - faceCropSize / 2.0)
                val faceBox = clampedSquareBox(fcx, faceCenterY, faceCropSize)
                val rawZoom = (currentZoom / faceCropSize.toFloat()).coerceIn(2.0f, 5.0f)
                val targetZoom = snapZoomToOpticalStop(rawZoom, currentZoom, zoomStops, maxZoom)
                if (proposals.none { abs(it.targetZoom - targetZoom) < 0.25f && it.kind == CloseupKind.FACE }) {
                    proposals += CloseupProposal(
                        id = "closeup_face",
                        kind = CloseupKind.FACE,
                        box = faceBox,
                        targetZoom = targetZoom,
                    )
                }
            }
        }

        // Light-and-shadow / scenic detail close-up when fewer than 2 proposals exist.
        if (proposals.size < 2) {
            val focusBox = subjectBounds?.takeIf {
                val w = it.right - it.left
                val h = it.bottom - it.top
                w in 0.12..0.58 && h in 0.12..0.58
            }
            if (focusBox != null && primaryFace == null) {
                val w = (focusBox.right - focusBox.left)
                val h = (focusBox.bottom - focusBox.top)
                val size = max(w * 1.35, h * 1.35).coerceIn(0.32, 0.56)
                val cx = (focusBox.left + focusBox.right) / 2.0
                val cy = (focusBox.top + focusBox.bottom) / 2.0
                val detailBox = clampedSquareBox(cx, cy, size)
                val rawZoom = (currentZoom / size.toFloat()).coerceIn(1.8f, 4.0f)
                val targetZoom = snapZoomToOpticalStop(rawZoom, currentZoom, zoomStops, maxZoom)
                proposals += CloseupProposal(
                    id = "closeup_light_detail",
                    kind = CloseupKind.LIGHT_DETAIL,
                    box = detailBox,
                    targetZoom = targetZoom,
                )
            } else if (highlightSpot != null && proposals.isEmpty()) {
                val size = 0.44
                val detailBox = clampedSquareBox(highlightSpot.x, highlightSpot.y, size)
                val rawZoom = (currentZoom / size.toFloat()).coerceIn(1.8f, 3.5f)
                val targetZoom = snapZoomToOpticalStop(rawZoom, currentZoom, zoomStops, maxZoom)
                proposals += CloseupProposal(
                    id = "closeup_light_detail",
                    kind = CloseupKind.LIGHT_DETAIL,
                    box = detailBox,
                    targetZoom = targetZoom,
                )
            }
        }

        return proposals.take(2)
    }

    private fun clampedSquareBox(cx: Double, cy: Double, size: Double): FaceBox {
        val s = size.coerceIn(0.22, 0.72)
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
