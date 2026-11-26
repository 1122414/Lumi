package com.lumi.camera.shotplan

import com.lumi.camera.guidance.Pose
import com.lumi.camera.vision.FaceBox
import kotlin.math.abs

internal enum class ProactivityMode(val wire: String, val label: String) {
    MANUAL("manual", "手动"),
    PROACTIVE("proactive", "主动"),
    QUIET("quiet", "安静");

    fun next(): ProactivityMode = when (this) {
        MANUAL -> PROACTIVE
        PROACTIVE -> QUIET
        QUIET -> MANUAL
    }

    companion object {
        fun fromWire(raw: String?): ProactivityMode =
            entries.firstOrNull { it.wire == raw } ?: MANUAL
    }
}

internal enum class ProactiveReason(val statusHint: String) {
    STEADY_HOLD("已稳住画面，自动分析中…"),
    CLIPPED_HEAD("检测到顶部偏紧，自动优化构图…"),
    SEVERE_TILT("画面明显倾斜，自动建议构图…"),
    LOW_LIGHT("画面偏暗，自动分析光线与构图…"),
}

internal class ProactiveTrigger(
    private val steadyDurationNs: Long = STEADY_HOLD_NS,
    private val urgentDurationNs: Long = URGENT_HOLD_NS,
    private val cooldownNs: Long = DEFAULT_COOLDOWN_NS,
) {
    private var steadySinceNs: Long? = null
    private var steadyAnchorPose: Pose? = null
    private var lastScenePose: Pose? = null
    private var firedForScene = false
    private var cooldownUntilNs = 0L

    fun reset() {
        steadySinceNs = null
        steadyAnchorPose = null
        lastScenePose = null
        firedForScene = false
        cooldownUntilNs = 0L
    }

    fun onManualOrCaptured(nowNs: Long, pose: Pose?) {
        cooldownUntilNs = nowNs + cooldownNs
        firedForScene = true
        if (pose != null) lastScenePose = pose
        steadySinceNs = null
        steadyAnchorPose = null
    }

    fun evaluate(
        nowNs: Long,
        pose: Pose?,
        rollDeg: Double?,
        faces: List<FaceBox>,
        meanLuma: Int?,
        hasActiveGuidance: Boolean,
        isRequestBusy: Boolean,
    ): ProactiveReason? {
        if (pose == null || hasActiveGuidance || isRequestBusy) {
            steadySinceNs = null
            steadyAnchorPose = null
            return null
        }

        // Moving to a clearly different framing resets the per-scene lock.
        val prevScene = lastScenePose
        if (firedForScene && prevScene != null && pose.degreesTo(prevScene) >= NEW_SCENE_DEG) {
            firedForScene = false
        }

        if (nowNs < cooldownUntilNs || firedForScene) {
            steadySinceNs = null
            steadyAnchorPose = null
            return null
        }

        val anchor = steadyAnchorPose
        if (anchor == null || pose.degreesTo(anchor) > STEADY_MAX_DRIFT_DEG) {
            steadyAnchorPose = pose
            steadySinceNs = nowNs
            return null
        }

        val heldNs = nowNs - (steadySinceNs ?: nowNs)
        val urgentReason = when {
            faces.any { it.top < CLIPPED_HEAD_TOP } -> ProactiveReason.CLIPPED_HEAD
            rollDeg != null && abs(rollDeg) >= SEVERE_TILT_DEG -> ProactiveReason.SEVERE_TILT
            meanLuma != null && meanLuma in 1 until LOW_LIGHT_LUMA -> ProactiveReason.LOW_LIGHT
            else -> null
        }

        val triggered = when {
            urgentReason != null && heldNs >= urgentDurationNs -> urgentReason
            heldNs >= steadyDurationNs -> ProactiveReason.STEADY_HOLD
            else -> null
        } ?: return null

        firedForScene = true
        lastScenePose = pose
        cooldownUntilNs = nowNs + cooldownNs
        steadySinceNs = null
        steadyAnchorPose = null
        return triggered
    }

    companion object {
        const val STEADY_HOLD_NS = 900_000_000L
        const val URGENT_HOLD_NS = 450_000_000L
        const val DEFAULT_COOLDOWN_NS = 8_000_000_000L
        const val STEADY_MAX_DRIFT_DEG = 1.6
        const val NEW_SCENE_DEG = 18.0
        const val CLIPPED_HEAD_TOP = 0.04
        const val SEVERE_TILT_DEG = 8.0
        const val LOW_LIGHT_LUMA = 45
    }
}
