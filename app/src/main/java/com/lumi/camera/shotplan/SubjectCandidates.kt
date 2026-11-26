package com.lumi.camera.shotplan

import com.lumi.camera.guidance.Uv
import com.lumi.camera.vision.FaceBox
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal data class SubjectCandidate(
    val id: Int,
    val label: String,
    val person: Boolean,
    val box: FaceBox,
    val seed: Uv,
    val isGroup: Boolean = false,
    val userPicked: Boolean = false,
    val syntheticTap: Boolean = false,
) {
    fun toSelectedSubject(nameOverride: String? = null): SelectedSubject {
        val cleanName = nameOverride?.trim()?.takeIf {
            it.codePointCount(0, it.length) in 1..6 && it.all(Char::isLetterOrDigit) &&
                it !in setOf("主体", "物体", "对象")
        } ?: label
        return SelectedSubject.fromBox(cleanName, person, box, seed)
    }
}

internal object SubjectCandidates {
    const val MAX_CANDIDATES = 4

    fun expandFaceToUpperBody(face: FaceBox): FaceBox {
        val w = (face.right - face.left).coerceAtLeast(0.02)
        val h = (face.bottom - face.top).coerceAtLeast(0.02)
        return FaceBox(
            left = (face.left - 0.48 * w).coerceIn(0.01, 0.98),
            top = (face.top - 0.35 * h).coerceIn(0.01, 0.98),
            right = (face.right + 0.48 * w).coerceIn(0.02, 0.99),
            bottom = (face.bottom + 1.85 * h).coerceIn(0.02, 0.99),
        )
    }

    fun unionOf(boxes: List<FaceBox>): FaceBox? {
        if (boxes.isEmpty()) return null
        return FaceBox(
            left = boxes.minOf { it.left }.coerceIn(0.01, 0.98),
            top = boxes.minOf { it.top }.coerceIn(0.01, 0.98),
            right = boxes.maxOf { it.right }.coerceIn(0.02, 0.99),
            bottom = boxes.maxOf { it.bottom }.coerceIn(0.02, 0.99),
        )
    }

    fun build(
        faces: List<FaceBox>,
        objects: List<FaceBox>,
        tapUv: Uv? = null,
    ): List<SubjectCandidate> {
        val raw = mutableListOf<RawCandidate>()

        val validFaces = faces.filter(::isValidBox)
        val personBoxes = validFaces.map { face ->
            val body = expandFaceToUpperBody(face)
            val faceCenter = centerOf(face)
            RawCandidate(
                label = "人物",
                person = true,
                box = body,
                seed = clampSeed(faceCenter, body),
                isGroup = false,
                priority = 2,
            )
        }

        if (validFaces.size >= 2) {
            val groupBox = unionOf(personBoxes.map { it.box })
            if (groupBox != null && areaOf(groupBox) <= 0.95) {
                val gc = centerOf(groupBox)
                val centralFace = validFaces.minByOrNull { centerOf(it).distance(gc) } ?: validFaces.first()
                raw += RawCandidate(
                    label = "合照",
                    person = true,
                    box = groupBox,
                    seed = clampSeed(centerOf(centralFace), groupBox),
                    isGroup = true,
                    priority = 3,
                )
            }
        }
        raw += personBoxes

        for (obj in objects) {
            if (!isValidBox(obj)) continue
            val area = areaOf(obj)
            if (area < 0.008 || area > 0.92) continue
            val c = clampSeed(centerOf(obj), obj)
            raw += RawCandidate(
                label = "物体",
                person = false,
                box = obj,
                seed = c,
                isGroup = false,
                priority = 1,
            )
        }

        val picked: RawCandidate? = tapUv?.takeIf { it.x in 0.01..0.99 && it.y in 0.01..0.99 }?.let { tap ->
            // Prefer the tightest non-group candidate containing the tap point.
            val hit = raw.filter { !it.isGroup && contains(it.box, tap) }
                .minByOrNull { areaOf(it.box) }
                ?: raw.filter { contains(it.box, tap) }.minByOrNull { areaOf(it.box) }
            if (hit != null) {
                hit.copy(seed = clampSeed(tap, hit.box), userPicked = true, priority = 10)
            } else {
                val synBox = FaceBox(
                    left = (tap.x - 0.18).coerceIn(0.02, 0.80),
                    top = (tap.y - 0.22).coerceIn(0.02, 0.76),
                    right = (tap.x + 0.18).coerceIn(0.20, 0.98),
                    bottom = (tap.y + 0.22).coerceIn(0.24, 0.98),
                )
                RawCandidate(
                    label = "所选主体",
                    person = false,
                    box = synBox,
                    seed = clampSeed(tap, synBox),
                    isGroup = false,
                    userPicked = true,
                    syntheticTap = true,
                    priority = 10,
                )
            }
        }

        val ordered = buildList {
            if (picked != null) add(picked)
            addAll(raw.sortedByDescending(::score))
        }

        val deduped = mutableListOf<RawCandidate>()
        for (item in ordered) {
            if (deduped.size >= MAX_CANDIDATES) break
            val duplicate = deduped.any { existing ->
                if (existing.isGroup != item.isGroup && existing.person && item.person) {
                    false
                } else {
                    iou(existing.box, item.box) > 0.55
                }
            }
            if (!duplicate) deduped += item
        }

        return deduped.mapIndexed { index, item ->
            SubjectCandidate(
                id = index + 1,
                label = item.label,
                person = item.person,
                box = item.box,
                seed = item.seed,
                isGroup = item.isGroup,
                userPicked = item.userPicked,
                syntheticTap = item.syntheticTap,
            )
        }
    }

    fun resolveSuggestedZoom(rawZoom: Any?, allowedStops: List<Float>, currentZoom: Float): Float? {
        if (allowedStops.size < 2 || rawZoom == null) return null
        val requested = when (rawZoom) {
            is Number -> rawZoom.toFloat()
            is String -> rawZoom.trim().removeSuffix("x").removeSuffix("X").removeSuffix("×").toFloatOrNull()
            else -> null
        } ?: return null
        if (!requested.isFinite() || requested <= 0f) return null
        val matched = allowedStops.minByOrNull { abs(it - requested) } ?: return null
        if (abs(matched - requested) > 0.16f) return null
        if (abs(matched - currentZoom) <= 0.15f) return null
        return matched
    }

    fun formatZoomLabel(ratio: Float): String =
        String.format(Locale.ROOT, "%.1f", ratio).removeSuffix(".0") + "×"

    private data class RawCandidate(
        val label: String,
        val person: Boolean,
        val box: FaceBox,
        val seed: Uv,
        val isGroup: Boolean,
        val priority: Int,
        val userPicked: Boolean = false,
        val syntheticTap: Boolean = false,
    )

    private fun score(c: RawCandidate): Double {
        val area = areaOf(c.box).coerceAtMost(0.45)
        val center = centerOf(c.box)
        val dist = hypot(center.x - 0.5, center.y - 0.5)
        return c.priority * 10.0 + area * 2.5 + (1.0 - dist)
    }

    private fun isValidBox(b: FaceBox): Boolean =
        b.left in 0.0..1.0 && b.right in 0.0..1.0 && b.top in 0.0..1.0 && b.bottom in 0.0..1.0 &&
            b.right - b.left >= 0.03 && b.bottom - b.top >= 0.03

    private fun areaOf(b: FaceBox): Double = (b.right - b.left) * (b.bottom - b.top)
    private fun centerOf(b: FaceBox): Uv = Uv((b.left + b.right) / 2.0, (b.top + b.bottom) / 2.0)
    private fun contains(b: FaceBox, p: Uv): Boolean = p.x in b.left..b.right && p.y in b.top..b.bottom

    private fun clampSeed(p: Uv, b: FaceBox): Uv {
        val padX = min(0.01, (b.right - b.left) * 0.15)
        val padY = min(0.01, (b.bottom - b.top) * 0.15)
        return Uv(
            p.x.coerceIn(b.left + padX, b.right - padX),
            p.y.coerceIn(b.top + padY, b.bottom - padY),
        )
    }

    private fun iou(a: FaceBox, b: FaceBox): Double {
        val ix = max(0.0, min(a.right, b.right) - max(a.left, b.left))
        val iy = max(0.0, min(a.bottom, b.bottom) - max(a.top, b.top))
        val inter = ix * iy
        if (inter <= 0.0) return 0.0
        val union = areaOf(a) + areaOf(b) - inter
        return if (union <= 0.0) 0.0 else inter / union
    }
}
