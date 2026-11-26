package com.lumi.camera.shotplan

import com.lumi.camera.guidance.Observation
import com.lumi.camera.guidance.RotationProjection
import com.lumi.camera.guidance.StepDirection
import com.lumi.camera.guidance.Uv
import com.lumi.camera.vision.FaceBox

internal enum class PlanProblem(val notice: String) {
    INCOMPLETE("模型只返回了主体，构图未返回完整"),
    INVALID_FIELDS("模型返回的构图格式有误"),
    INVALID_OUTLINE("模型未给出可用的主体轮廓"),
    INVALID_AIM("模型给出的取景中心无效"),
    CONFLICTING_AIM("模型给出的两个取景位置不一致"),
    LOW_CONFIDENCE("模型对这次构图把握不足"),
    UNCONFIRMED_OUTLINE("主体边缘暂未确认"),
    UNCONFIRMED_FACE("尚未确认所选人物的脸部"),
    FRAME_UNAVAILABLE("推荐画面的取景信息不完整"),
    SUBJECT_CROPPED("推荐角度会裁掉主体"),
}

internal sealed interface PlanResult<out T> {
    data class Accepted<T>(val value: T) : PlanResult<T>
    data class Rejected(val problem: PlanProblem) : PlanResult<Nothing>
}

internal class Recommendation private constructor(
    val aimPoint: Uv,
    val confidence: Double,
    val alreadyGood: Boolean,
    val reason: String,
    val subject: SelectedSubject,
    val choice: CompositionChoice? = null,
    val suggestedZoom: Float? = null,
    val candidateId: Int? = null,
    val directorCue: DirectorCue? = null,
    val stepDirection: StepDirection? = null,
    val exposureSuggestion: AiExposureSuggestion? = null,
) {
    fun withMeasuredBounds(bounds: FaceBox): Recommendation {
        val selectedChoice = choice ?: return this
        val computed = CompositionChoice.aimPointFor(bounds, selectedChoice)
        return Recommendation(computed, confidence, computed == Uv.CENTER, reason, subject, selectedChoice, suggestedZoom, candidateId, directorCue, stepDirection, exposureSuggestion)
    }

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
            """[ \t\r\n]+|[{}\[\],:]|true|false|null|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?|(?:"(?:[^"\\\x00-\x1f]++|\\(?:["\\/bfnrt]|u[0-9a-fA-F]{4}))*+")""")

        private val candidateAllowedKeys = setOf("candidate", "choice", "reason", "name", "zoom", "confidence", "focus", "tip", "step", "exposure")
        private val choiceAllowedKeys = setOf("subject", "choice", "confidence", "reason", "zoom", "focus", "tip", "step", "exposure")

        fun hasJsonTokens(text: String): Boolean {
            if (text.length > 8192 || !text.trimStart().startsWith('{')) return false
            val tokens = jsonToken.matcher(text)
            var end = 0
            while (tokens.find()) {
                if (tokens.start() != end) return false
                end = tokens.end()
            }
            return end == text.length
        }

        private fun parseValidReason(raw: Any?): String? {
            val reason = (raw as? String)?.trim() ?: return null
            if (reason.isEmpty() || reason.length > 80 ||
                reason.any { it.isISOControl() || it == '\u2028' || it == '\u2029' }) return null
            return reason
        }

        private fun parseCandidateId(raw: Any?): Int? {
            val num = (raw as? Number)?.toDouble() ?: return null
            if (!num.isFinite() || num != num.toInt().toDouble()) return null
            return num.toInt()
        }

        fun evaluateCandidate(
            fields: Map<String, Any?>,
            candidates: List<SubjectCandidate>,
            zoomStops: List<Float> = emptyList(),
            currentZoom: Float = 1f,
        ): PlanResult<Recommendation> {
            val malformed = PlanResult.Rejected(PlanProblem.INVALID_FIELDS)
            if (candidates.isEmpty() || !fields.keys.all { it in candidateAllowedKeys }) return malformed
            if ("candidate" !in fields || "choice" !in fields || "reason" !in fields) return malformed
            val id = parseCandidateId(fields["candidate"]) ?: return malformed
            val picked = candidates.firstOrNull { it.id == id } ?: return malformed
            val choice = CompositionChoice.parse(fields["choice"]) ?: return malformed
            val reason = parseValidReason(fields["reason"]) ?: return malformed
            val nameOverride = fields["name"]?.let { it as? String ?: return malformed }
            val confidence = if ("confidence" in fields && fields["confidence"] != null) {
                (fields["confidence"] as? Number)?.toDouble()?.takeIf { it in 0.0..1.0 } ?: return malformed
            } else 1.0
            val suggestedZoom = SubjectCandidates.resolveSuggestedZoom(fields["zoom"], zoomStops, currentZoom)
            val directorCue = DirectorCue.parse(fields["focus"], fields["tip"])
            val stepDirection = StepDirection.parse(fields["step"])
            val exposureSuggestion = AiExposureSuggestion.parse(fields["exposure"])
            val subject = picked.toSelectedSubject(nameOverride)
            val aim = CompositionChoice.aimPointFor(subject.bounds, choice)
            return PlanResult.Accepted(
                Recommendation(aim, confidence, aim == Uv.CENTER, reason, subject, choice, suggestedZoom, candidateId = id, directorCue = directorCue, stepDirection = stepDirection, exposureSuggestion = exposureSuggestion)
            )
        }

        fun evaluate(
            fields: Map<String, Any?>,
            subject: SelectedSubject?,
            zoomStops: List<Float> = emptyList(),
            currentZoom: Float = 1f,
        ): PlanResult<Recommendation> {
            val malformed = PlanResult.Rejected(PlanProblem.INVALID_FIELDS)
            if (fields.keys.all { it in choiceAllowedKeys } &&
                setOf("subject", "choice", "confidence", "reason").all { it in fields }) {
                if (subject == null) return PlanResult.Rejected(PlanProblem.INVALID_OUTLINE)
                val choice = CompositionChoice.parse(fields["choice"]) ?: return malformed
                val confidence = (fields["confidence"] as? Number)?.toDouble() ?: return malformed
                val reason = parseValidReason(fields["reason"]) ?: return malformed
                if (confidence !in 0.0..1.0) return malformed
                if (confidence < 0.70) return PlanResult.Rejected(PlanProblem.LOW_CONFIDENCE)
                val suggestedZoom = SubjectCandidates.resolveSuggestedZoom(fields["zoom"], zoomStops, currentZoom)
                val directorCue = DirectorCue.parse(fields["focus"], fields["tip"])
                val stepDirection = StepDirection.parse(fields["step"])
                val exposureSuggestion = AiExposureSuggestion.parse(fields["exposure"])
                val aim = CompositionChoice.aimPointFor(subject.bounds, choice)
                return PlanResult.Accepted(
                    Recommendation(aim, confidence, aim == Uv.CENTER, reason, subject, choice, suggestedZoom, directorCue = directorCue, stepDirection = stepDirection, exposureSuggestion = exposureSuggestion)
                )
            }
            if (fields.keys != setOf("aimPoint", "confidence", "alreadyGood", "reason", "subject")) return malformed
            if (subject == null) return PlanResult.Rejected(PlanProblem.INVALID_OUTLINE)
            val aim = fields["aimPoint"] as? Map<*, *> ?: return malformed
            if (aim.keys != setOf("x", "y")) return malformed
            val x = (aim["x"] as? Number)?.toDouble() ?: return malformed
            val y = (aim["y"] as? Number)?.toDouble() ?: return malformed
            val confidence = (fields["confidence"] as? Number)?.toDouble() ?: return malformed
            val good = fields["alreadyGood"] as? Boolean ?: return malformed
            val reason = parseValidReason(fields["reason"]) ?: return malformed
            if (confidence !in 0.0..1.0) return malformed
            if (x !in 0.0..1.0 || y !in 0.0..1.0) return PlanResult.Rejected(PlanProblem.INVALID_AIM)
            if (good && (x != 0.5 || y != 0.5)) return PlanResult.Rejected(PlanProblem.CONFLICTING_AIM)
            if (confidence < 0.70) return PlanResult.Rejected(PlanProblem.LOW_CONFIDENCE)
            return PlanResult.Accepted(Recommendation(Uv(x, y), confidence, good, reason, subject))
        }
    }

    fun targetFrom(source: Observation, box: FaceBox, currentZoom: Float = 1f): Observation? {
        val face = source.subject ?: return null
        if (box.left !in 0.0..1.0 || box.right !in 0.0..1.0 || box.top !in 0.0..1.0 ||
            box.bottom !in 0.0..1.0 || box.left >= box.right || box.top >= box.bottom) return null
        if (aimPoint == Uv.CENTER) return source

        // Device axes: right, up, towards the photographer. The visible center may
        // differ from the optical axis after the shared ViewPort crop.
        val pose = source.pose * (source.lens.lookAt(aimPoint) * source.lens.lookAt(Uv.CENTER).inverse())
        val target = source.copy(pose = pose)
        val zoomOutScale = if (suggestedZoom != null && currentZoom > 0f && suggestedZoom < currentZoom) {
            (suggestedZoom / currentZoom).toDouble().coerceIn(0.3, 1.0)
        } else 1.0
        val checkTarget = if (zoomOutScale < 1.0) {
            target.copy(lens = target.lens.copy(fx = target.lens.fx * zoomOutScale, fy = target.lens.fy * zoomOutScale))
        } else target
        val projection = RotationProjection(source, checkTarget)
        val corners = listOf(Uv(box.left, box.top), Uv(box.right, box.top),
            Uv(box.right, box.bottom), Uv(box.left, box.bottom)).map {
            val p = projection.project(it) ?: return null
            if (p.x !in 0.0..1.0 || p.y !in 0.0..1.0) return null
            p
        }
        val center = RotationProjection(source, target).project(face.center) ?: return null
        return target.copy(subject = face.copy(center = center, width = corners.maxOf { it.x } - corners.minOf { it.x }))
    }
}

// An identified subject can survive a rejected composition, but can never confer readiness.
internal data class ModelReply(
    val name: String,
    val subject: SelectedSubject?,
    val decision: PlanResult<Recommendation>,
    val rawContent: String = "",
    val rawAimPoint: Uv? = null,
    val rawChoice: CompositionChoice? = null,
    val candidateId: Int? = null,
    val suggestedZoom: Float? = null,
    val usedFallback: Boolean = false,
) {
    companion object {
        fun from(
            fields: Map<String, Any?>,
            complete: Boolean,
            rawContent: String = "",
            candidates: List<SubjectCandidate> = emptyList(),
            zoomStops: List<Float> = emptyList(),
            currentZoom: Float = 1f,
        ): ModelReply? {
            if ("candidate" in fields && candidates.isNotEmpty()) {
                val id = (fields["candidate"] as? Number)?.toDouble()
                    ?.takeIf { it.isFinite() && it == it.toInt().toDouble() }?.toInt()
                val picked = id?.let { cid -> candidates.firstOrNull { it.id == cid } }
                val nameOverride = fields["name"] as? String
                val subject = picked?.toSelectedSubject(nameOverride)
                val decision = if (complete) {
                    Recommendation.evaluateCandidate(fields, candidates, zoomStops, currentZoom)
                } else {
                    PlanResult.Rejected(PlanProblem.INCOMPLETE)
                }
                val choice = CompositionChoice.parse(fields["choice"])
                val suggestedZoom = SubjectCandidates.resolveSuggestedZoom(fields["zoom"], zoomStops, currentZoom)
                val rawAim = if (choice != null && subject != null) CompositionChoice.aimPointFor(subject.bounds, choice) else null
                return ModelReply(
                    name = subject?.name ?: picked?.label ?: "主体",
                    subject = subject,
                    decision = decision,
                    rawContent = rawContent,
                    rawAimPoint = rawAim,
                    rawChoice = choice,
                    candidateId = id,
                    suggestedZoom = suggestedZoom,
                    usedFallback = false,
                )
            }

            val name = SelectedSubject.nameOf(fields["subject"]) ?: return null
            val subject = SelectedSubject.parse(fields["subject"])
            val decision = if (complete) {
                Recommendation.evaluate(fields, subject, zoomStops, currentZoom)
            } else {
                PlanResult.Rejected(PlanProblem.INCOMPLETE)
            }
            val choice = CompositionChoice.parse(fields["choice"])
            val suggestedZoom = SubjectCandidates.resolveSuggestedZoom(fields["zoom"], zoomStops, currentZoom)
            val rawAim = (fields["aimPoint"] as? Map<*, *>)?.let { aim ->
                val x = (aim["x"] as? Number)?.toDouble()
                val y = (aim["y"] as? Number)?.toDouble()
                if (x != null && y != null && x.isFinite() && y.isFinite()) Uv(x, y) else null
            } ?: if (choice != null && subject != null) CompositionChoice.aimPointFor(subject.bounds, choice) else null
            return ModelReply(
                name = name,
                subject = subject,
                decision = decision,
                rawContent = rawContent,
                rawAimPoint = rawAim,
                rawChoice = choice,
                candidateId = null,
                suggestedZoom = suggestedZoom,
                usedFallback = true,
            )
        }
    }
}
