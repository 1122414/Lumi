package com.lumi.camera.shotplan

import android.content.Context
import com.lumi.camera.vision.FaceBox
import com.lumi.camera.vision.SubjectPixels
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class PhotoQualityScore(
    val subjectClarity: Int,
    val frameBalance: Int,
    val backgroundCleanliness: Int,
    val naturalness: Int,
) {
    val overall: Int = (
        subjectClarity * 0.30 +
            frameBalance * 0.25 +
            backgroundCleanliness * 0.25 +
            naturalness * 0.20
        ).roundToInt().coerceIn(0, 100)

    val grade: String = when {
        overall >= 88 -> "S"
        overall >= 76 -> "A"
        overall >= 62 -> "B"
        else -> "C"
    }

    val weakestDimension: String = listOf(
        "主体突出" to subjectClarity,
        "画面平衡" to frameBalance,
        "背景干净" to backgroundCleanliness,
        "自然度" to naturalness,
    ).minBy { it.second }.first
}

internal object PhotoQualityEvaluator {
    fun evaluate(
        subjectBounds: FaceBox?,
        rollDeg: Double?,
        wasReady: Boolean,
        meanLuma: Int?,
        zoomRatio: Float,
        backgroundClutter: Double?,
        unresolvedStep: Boolean,
        directorCue: DirectorCue?,
    ): PhotoQualityScore {
        val subjectScore = scoreSubject(subjectBounds, wasReady)
        val balanceScore = scoreBalance(subjectBounds, rollDeg, wasReady)
        val bgScore = scoreBackground(backgroundClutter, unresolvedStep, directorCue)
        val naturalScore = scoreNaturalness(subjectBounds, meanLuma, zoomRatio)
        return PhotoQualityScore(subjectScore, balanceScore, bgScore, naturalScore)
    }

    // Estimate background clutter (0.0 = very clean, 1.0 = very busy) from Y-plane gradients outside subject box.
    fun estimateBackgroundClutter(pixels: SubjectPixels?, subjectBounds: FaceBox?): Double? {
        if (pixels == null || pixels.width < 12 || pixels.height < 12) return null
        val box = subjectBounds ?: FaceBox(0.30, 0.25, 0.70, 0.75)
        var gradSum = 0.0
        var count = 0
        for (row in 1 until 11) {
            val y = row / 12.0
            for (col in 1 until 11) {
                val x = col / 12.0
                if (x in box.left..box.right && y in box.top..box.bottom) continue
                val c = pixels.at(x, y) ?: continue
                val r = pixels.at((x + 0.04).coerceAtMost(0.99), y) ?: continue
                val d = pixels.at(x, (y + 0.04).coerceAtMost(0.99)) ?: continue
                val y0 = c shr 16
                val gx = abs((r shr 16) - y0)
                val gy = abs((d shr 16) - y0)
                gradSum += (gx + gy) * 0.5
                count++
            }
        }
        if (count < 8) return null
        val meanGrad = gradSum / count
        return ((meanGrad - 6.0) / 38.0).coerceIn(0.0, 1.0)
    }

    private fun scoreSubject(box: FaceBox?, wasReady: Boolean): Int {
        if (box == null) return if (wasReady) 74 else 66
        val area = ((box.right - box.left) * (box.bottom - box.top)).coerceIn(0.0, 1.0)
        var score = when {
            area in 0.12..0.52 -> 92
            area in 0.06..0.68 -> 80
            else -> 64
        }
        val clippedEdges = listOf(
            box.left < 0.025,
            box.right > 0.975,
            box.top < 0.025,
            box.bottom > 0.985,
        ).count { it }
        score -= clippedEdges * 10
        if (wasReady) score += 6
        return score.coerceIn(35, 98)
    }

    private fun scoreBalance(box: FaceBox?, rollDeg: Double?, wasReady: Boolean): Int {
        val tilt = rollDeg?.let { abs(it) } ?: 1.0
        var score = when {
            tilt <= 2.5 -> 94
            tilt <= 5.0 -> 80
            tilt <= 9.0 -> 64
            else -> 48
        }
        if (box != null) {
            val cx = (box.left + box.right) / 2.0
            val nearThirdOrCenter = minOf(abs(cx - 0.35), abs(cx - 0.50), abs(cx - 0.65))
            if (nearThirdOrCenter <= 0.07) score += 4
            else if (nearThirdOrCenter >= 0.16) score -= 8
        }
        if (wasReady) score += 3
        return score.coerceIn(35, 98)
    }

    private fun scoreBackground(clutter: Double?, unresolvedStep: Boolean, cue: DirectorCue?): Int {
        var score = if (clutter != null) {
            (94.0 - clutter * 36.0).roundToInt()
        } else 80
        if (unresolvedStep) score -= 12
        if (cue?.category == DirectorCategory.BACKGROUND) score -= 6
        return score.coerceIn(38, 96)
    }

    private fun scoreNaturalness(box: FaceBox?, meanLuma: Int?, zoomRatio: Float): Int {
        var score = 88
        if (meanLuma != null) {
            score -= when {
                meanLuma in 70..205 -> 0
                meanLuma in 45..225 -> 8
                else -> 18
            }
        }
        if (zoomRatio > 3.5f || zoomRatio < 0.65f) score -= 6
        if (box != null && box.top < 0.05) score -= 8
        return score.coerceIn(40, 96)
    }
}

internal data class AestheticPreferenceProfile(
    val thirdsWeight: Float = 0f,
    val centerWeight: Float = 0f,
    val tightWeight: Float = 0f,
    val headroomWeight: Float = 0f,
    val wideZoomWeight: Float = 0f,
    val standardZoomWeight: Float = 0f,
    val teleZoomWeight: Float = 0f,
    val totalSignals: Int = 0,
) {
    fun recordComposition(choice: CompositionChoice?, zoomRatio: Float, weight: Float = 1f): AestheticPreferenceProfile {
        val decay = 0.94f
        var t = thirdsWeight * decay
        var c = centerWeight * decay
        var ti = tightWeight * decay
        var h = headroomWeight * decay
        when (choice) {
            CompositionChoice.LEFT_THIRD, CompositionChoice.RIGHT_THIRD -> t += weight
            CompositionChoice.CENTER -> c += weight
            CompositionChoice.TIGHT_TOP -> ti += weight
            CompositionChoice.MORE_HEADROOM -> h += weight
            else -> Unit
        }
        var wz = wideZoomWeight * decay
        var sz = standardZoomWeight * decay
        var tz = teleZoomWeight * decay
        when {
            zoomRatio < 0.9f -> wz += weight
            zoomRatio >= 1.75f -> tz += weight
            else -> sz += weight
        }
        return AestheticPreferenceProfile(t, c, ti, h, wz, sz, tz, totalSignals + 1)
    }

    fun recordRecrop(proposalId: String, weight: Float = 1.5f): AestheticPreferenceProfile {
        val mapped = when (proposalId) {
            "rule_of_thirds" -> CompositionChoice.LEFT_THIRD
            "tight_focus" -> CompositionChoice.TIGHT_TOP
            "balanced_center" -> CompositionChoice.CENTER
            else -> null
        }
        return recordComposition(mapped, zoomRatio = 1f, weight = weight)
    }

    fun promptPrior(): String? {
        if (totalSignals < MIN_SIGNALS_FOR_PRIOR) return null
        val styleEntries = listOf(
            "三分留白构图" to thirdsWeight,
            "居中对称构图" to centerWeight,
            "紧凑特写构图" to tightWeight,
            "大留白呼吸感构图" to headroomWeight,
        ).sortedByDescending { it.second }
        val topStyle = styleEntries.firstOrNull()?.takeIf { it.second >= 2.0f && it.second >= (styleEntries.getOrNull(1)?.second ?: 0f) * 1.25f }?.first

        val zoomEntries = listOf(
            "广角大场景" to wideZoomWeight,
            "中长焦(2x及以上)聚焦" to teleZoomWeight,
        ).sortedByDescending { it.second }
        val topZoom = zoomEntries.firstOrNull()?.takeIf { it.second >= 2.2f && it.second > standardZoomWeight * 1.15f }?.first

        return when {
            topStyle != null && topZoom != null -> "用户近期偏好$topStyle 与$topZoom，同等条件下可适当倾斜。"
            topStyle != null -> "用户近期偏好$topStyle，同等条件下可适当倾斜。"
            topZoom != null -> "用户近期偏好$topZoom，同等条件下可适当倾斜。"
            else -> null
        }
    }

    fun summaryLabel(): String = promptPrior()?.removeSuffix("，同等条件下可适当倾斜。") ?: "正在学习你的拍摄偏好（已记录 $totalSignals 次）"

    companion object {
        const val MIN_SIGNALS_FOR_PRIOR = 3
    }
}

internal class AestheticPreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("lumi_aesthetic_prefs", Context.MODE_PRIVATE)

    fun read(): AestheticPreferenceProfile = AestheticPreferenceProfile(
        thirdsWeight = prefs.getFloat("thirds", 0f),
        centerWeight = prefs.getFloat("center", 0f),
        tightWeight = prefs.getFloat("tight", 0f),
        headroomWeight = prefs.getFloat("headroom", 0f),
        wideZoomWeight = prefs.getFloat("wide_zoom", 0f),
        standardZoomWeight = prefs.getFloat("std_zoom", 0f),
        teleZoomWeight = prefs.getFloat("tele_zoom", 0f),
        totalSignals = prefs.getInt("signals", 0),
    )

    fun save(profile: AestheticPreferenceProfile) {
        prefs.edit()
            .putFloat("thirds", profile.thirdsWeight)
            .putFloat("center", profile.centerWeight)
            .putFloat("tight", profile.tightWeight)
            .putFloat("headroom", profile.headroomWeight)
            .putFloat("wide_zoom", profile.wideZoomWeight)
            .putFloat("std_zoom", profile.standardZoomWeight)
            .putFloat("tele_zoom", profile.teleZoomWeight)
            .putInt("signals", profile.totalSignals)
            .apply()
    }

    fun recordComposition(choice: CompositionChoice?, zoomRatio: Float): AestheticPreferenceProfile {
        val updated = read().recordComposition(choice, zoomRatio)
        save(updated)
        return updated
    }

    fun recordRecrop(proposalId: String): AestheticPreferenceProfile {
        val updated = read().recordRecrop(proposalId)
        save(updated)
        return updated
    }

    fun clear(): AestheticPreferenceProfile {
        prefs.edit().clear().apply()
        return AestheticPreferenceProfile()
    }
}
