package com.lumi.camera.shotplan

import com.lumi.camera.guidance.Uv
import com.lumi.camera.vision.FaceBox
import kotlin.math.hypot

internal enum class CompositionPreset(val label: String, val promptHint: String?) {
    AUTO("智能", null),
    RULE_OF_THIRDS("三分", "用户偏好三分线构图：请结合主体视线/朝向与背景留白，优先从 left_third 或 right_third 中选择更合适的一侧。"),
    SYMMETRY("对称", "用户偏好居中对称构图：请优先选择 center（若主体已稳定居中可选 keep）。"),
    TIGHT("紧凑", "用户偏好紧凑特写构图：请优先选择 tight_top 收紧上方多余留白（若顶部已很紧凑可选 keep）。"),
    HEADROOM("留白", "用户偏好留白呼吸感构图：请优先选择 more_headroom 增加上方环境留白（若上方留白已充足可选 keep）。");

    fun resolveChoice(
        box: FaceBox,
        originalChoice: CompositionChoice?,
        preferredThird: CompositionChoice = CompositionChoice.LEFT_THIRD,
    ): CompositionChoice? = when (this) {
        AUTO -> originalChoice
        RULE_OF_THIRDS -> when (preferredThird) {
            CompositionChoice.LEFT_THIRD, CompositionChoice.RIGHT_THIRD -> preferredThird
            else -> if ((box.left + box.right) / 2.0 > 0.5) CompositionChoice.RIGHT_THIRD else CompositionChoice.LEFT_THIRD
        }
        SYMMETRY -> CompositionChoice.CENTER
        TIGHT -> CompositionChoice.TIGHT_TOP
        HEADROOM -> CompositionChoice.MORE_HEADROOM
    }
}

internal data class PresetSelection(
    val preset: CompositionPreset = CompositionPreset.AUTO,
    val thirdChoice: CompositionChoice = CompositionChoice.LEFT_THIRD,
) {
    val activeThirdSide: CompositionChoice?
        get() = if (preset == CompositionPreset.RULE_OF_THIRDS) thirdChoice else null
}

internal enum class CompositionChoice(val wireName: String, val label: String) {
    KEEP("keep", "保持当前构图"),
    CENTER("center", "主体居中"),
    LEFT_THIRD("left_third", "主体放左三分"),
    RIGHT_THIRD("right_third", "主体放右三分"),
    TIGHT_TOP("tight_top", "减少顶部留白"),
    MORE_HEADROOM("more_headroom", "增加顶部留白"),
    PULL_IN("pull_in", "拉回安全区");

    companion object {
        fun parse(raw: Any?): CompositionChoice? {
            val text = (raw as? String)?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wireName == text }
        }

        // Convert a semantic framing choice into the camera aimPoint in current upright UV coordinates.
        // Moving the camera toward aimPoint = 0.5 + (current - desired) places the subject at desired.
        fun aimPointFor(box: FaceBox, choice: CompositionChoice): Uv {
            if (choice == KEEP) return Uv.CENTER
            val w = (box.right - box.left).coerceIn(0.01, 1.0)
            val h = (box.bottom - box.top).coerceIn(0.01, 1.0)
            val cx = (box.left + box.right) / 2.0
            val cy = (box.top + box.bottom) / 2.0

            val safeMarginX = minOf(0.08, ((1.0 - w) / 2.0).coerceAtLeast(0.0))
            val safeMarginY = minOf(0.08, ((1.0 - h) / 2.0).coerceAtLeast(0.0))
            val minCx = safeMarginX + w / 2.0
            val maxCx = 1.0 - safeMarginX - w / 2.0
            val minCy = safeMarginY + h / 2.0
            val maxCy = 1.0 - safeMarginY - h / 2.0

            fun clampX(x: Double) = x.coerceIn(minOf(minCx, maxCx), maxOf(minCx, maxCx))
            fun clampY(y: Double) = y.coerceIn(minOf(minCy, maxCy), maxOf(minCy, maxCy))

            val desiredX: Double
            val desiredY: Double
            when (choice) {
                KEEP -> return Uv.CENTER
                CENTER -> {
                    desiredX = clampX(0.50)
                    desiredY = clampY(0.50)
                }
                LEFT_THIRD -> {
                    desiredX = clampX(0.36)
                    desiredY = clampY(cy)
                }
                RIGHT_THIRD -> {
                    desiredX = clampX(0.64)
                    desiredY = clampY(cy)
                }
                TIGHT_TOP -> {
                    // Reduce excess top headroom and gently pull far-edge subjects into a balanced horizontal band.
                    val targetTop = minOf(box.top, 0.14)
                    desiredX = clampX(cx.coerceIn(0.34, 0.66))
                    desiredY = clampY(targetTop + h / 2.0)
                }
                MORE_HEADROOM -> {
                    // Give more breathing room above the subject and avoid locking far-edge subjects at the border.
                    val targetTop = maxOf(box.top, 0.24)
                    desiredX = clampX(cx.coerceIn(0.34, 0.66))
                    desiredY = clampY(targetTop + h / 2.0)
                }
                PULL_IN -> {
                    // Pull an edge-crowded subject cleanly inside safe margins and closer to center.
                    val pullMarginX = minOf(0.14, ((1.0 - w) / 2.0).coerceAtLeast(0.0))
                    val pullMarginY = minOf(0.14, ((1.0 - h) / 2.0).coerceAtLeast(0.0))
                    val pulledX = (cx + (0.50 - cx) * 0.5).coerceIn(
                        minOf(pullMarginX + w / 2.0, 1.0 - pullMarginX - w / 2.0),
                        maxOf(pullMarginX + w / 2.0, 1.0 - pullMarginX - w / 2.0),
                    )
                    val pulledY = (cy + (0.50 - cy) * 0.5).coerceIn(
                        minOf(pullMarginY + h / 2.0, 1.0 - pullMarginY - h / 2.0),
                        maxOf(pullMarginY + h / 2.0, 1.0 - pullMarginY - h / 2.0),
                    )
                    desiredX = clampX(pulledX)
                    desiredY = clampY(pulledY)
                }
            }

            // To move subject from (cx, cy) to (desiredX, desiredY), aim camera at 0.5 + (current - desired).
            val aimX = (0.5 + (cx - desiredX)).coerceIn(0.05, 0.95)
            val aimY = (0.5 + (cy - desiredY)).coerceIn(0.05, 0.95)
            return if (hypot(aimX - 0.5, aimY - 0.5) < 0.02) Uv.CENTER else Uv(aimX, aimY)
        }

        // Adapt the capture-time desired subject center to live zoomed subject dimensions.
        fun desiredCenterFor(desired0: Uv, captureHeight: Double, liveWidth: Double, liveHeight: Double, choice: CompositionChoice?): Uv {
            val w = liveWidth.coerceIn(0.01, 1.0)
            val h = liveHeight.coerceIn(0.01, 1.0)
            val safeMarginX = minOf(0.08, ((1.0 - w) / 2.0).coerceAtLeast(0.0))
            val safeMarginY = minOf(0.08, ((1.0 - h) / 2.0).coerceAtLeast(0.0))
            val minCx = safeMarginX + w / 2.0
            val maxCx = 1.0 - safeMarginX - w / 2.0
            val minCy = safeMarginY + h / 2.0
            val maxCy = 1.0 - safeMarginY - h / 2.0
            fun clampX(x: Double) = x.coerceIn(minOf(minCx, maxCx), maxOf(minCx, maxCx))
            fun clampY(y: Double) = y.coerceIn(minOf(minCy, maxCy), maxOf(minCy, maxCy))
            return when (choice) {
                CENTER -> Uv(clampX(0.50), clampY(0.50))
                LEFT_THIRD -> Uv(clampX(0.36), clampY(desired0.y))
                RIGHT_THIRD -> Uv(clampX(0.64), clampY(desired0.y))
                TIGHT_TOP, MORE_HEADROOM -> {
                    val targetTop = desired0.y - captureHeight.coerceIn(0.01, 1.0) / 2.0
                    Uv(clampX(desired0.x.coerceIn(0.34, 0.66)), clampY(targetTop + h / 2.0))
                }
                KEEP, PULL_IN, null -> Uv(clampX(desired0.x), clampY(desired0.y))
            }
        }
    }
}
