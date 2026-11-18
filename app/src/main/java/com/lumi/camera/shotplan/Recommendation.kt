package com.lumi.camera.shotplan

import com.lumi.camera.guidance.Observation
import com.lumi.camera.guidance.Pose
import com.lumi.camera.guidance.RotationProjection
import com.lumi.camera.guidance.Uv
import com.lumi.camera.vision.FaceBox
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal class Recommendation private constructor(
    val aimPoint: Uv, val confidence: Double, val alreadyGood: Boolean, val reason: String, val subject: SelectedSubject,
) {
    val caption: String = run {
        val named = when {
            subject.name in reason -> reason
            "主体" in reason -> reason.replace("主体", subject.name)
            else -> subject.name + "·" + reason
        }
        if (named.codePointCount(0, named.length) <= 10) named else {
            val clause = named.takeWhile { it !in "，。；！？、,;.!?" }.trim()
            if (subject.name in clause && clause.codePointCount(0, clause.length) <= 10) clause
            else {
                val short = named.substring(0, named.offsetByCodePoints(0, 9))
                if (subject.name in short) "$short…" else {
                    val withNameFirst = subject.name + "·" + named.substringBefore(subject.name)
                    withNameFirst.substring(0, withNameFirst.offsetByCodePoints(0, 9)) + "…"
                }
            }
        }
    }

    companion object {
        // Android JsonReader also accepts e.g. TRUE and non-JSON string escapes in strict mode.
        private val jsonToken = java.util.regex.Pattern.compile(
            """[ \t\r\n]+|[{}\[\],:]|true|false|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?|(?:"(?:[^"\\\x00-\x1f]++|\\(?:["\\/bfnrt]|u[0-9a-fA-F]{4}))*+")""")

        fun hasJsonTokens(text: String): Boolean {
            if (text.length > 4096 || !text.trimStart().startsWith('{')) return false
            val tokens = jsonToken.matcher(text)
            var end = 0
            while (tokens.find()) {
                if (tokens.start() != end) return false
                end = tokens.end()
            }
            return end == text.length
        }

        fun validated(fields: Map<String, Any?>): Recommendation? {
            if (fields.keys != setOf("aimPoint", "confidence", "alreadyGood", "reason", "subject")) return null
            val subject = SelectedSubject.parse(fields["subject"]) ?: return null
            val aim = fields["aimPoint"] as? Map<*, *> ?: return null
            if (aim.keys != setOf("x", "y")) return null
            val x = (aim["x"] as? Number)?.toDouble() ?: return null
            val y = (aim["y"] as? Number)?.toDouble() ?: return null
            val confidence = (fields["confidence"] as? Number)?.toDouble() ?: return null
            val good = fields["alreadyGood"] as? Boolean ?: return null
            val reason = (fields["reason"] as? String)?.trim() ?: return null
            if (x !in 0.0..1.0 || y !in 0.0..1.0 || confidence !in 0.70..1.0 ||
                (good && (x != 0.5 || y != 0.5)) || reason.isEmpty() || reason.length > 80 ||
                reason.any { it.isISOControl() || it == '\u2028' || it == '\u2029' }) return null
            return Recommendation(Uv(x, y), confidence, good, reason, subject)
        }
    }

    fun targetFrom(source: Observation, box: FaceBox): Observation? {
        val face = source.subject ?: return null
        if (box.left !in 0.0..1.0 || box.right !in 0.0..1.0 || box.top !in 0.0..1.0 ||
            box.bottom !in 0.0..1.0 || box.left >= box.right || box.top >= box.bottom) return null
        if (aimPoint == Uv.CENTER) return source

        // Device axes: right, up, towards the photographer. The visible center may
        // differ from the optical axis after the shared ViewPort crop.
        fun look(at: Uv): Pose {
            val x = (at.x - source.lens.cx) / source.lens.fx
            val y = (source.lens.cy - at.y) / source.lens.fy
            val yaw = -atan2(x, 1.0) / 2
            val pitch = atan2(y, hypot(x, 1.0)) / 2
            return Pose(cos(yaw), 0.0, sin(yaw), 0.0) * Pose(cos(pitch), sin(pitch), 0.0, 0.0)
        }
        val pose = source.pose * (look(aimPoint) * look(Uv.CENTER).inverse())
        val target = source.copy(pose = pose)
        val projection = RotationProjection(source, target)
        val corners = listOf(Uv(box.left, box.top), Uv(box.right, box.top),
            Uv(box.right, box.bottom), Uv(box.left, box.bottom)).map {
            val p = projection.project(it) ?: return null
            if (p.x !in 0.0..1.0 || p.y !in 0.0..1.0) return null
            p
        }
        val center = projection.project(face.center) ?: return null
        return target.copy(subject = face.copy(center = center, width = corners.maxOf { it.x } - corners.minOf { it.x }))
    }
}
