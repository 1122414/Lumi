package com.lumi.camera.vision

import com.lumi.camera.guidance.Uv

internal enum class LightPosition(val label: String, val arrow: String) {
    FRONT("顺光柔和", "◉"),
    LEFT_SIDE("左侧主光", "←"),
    RIGHT_SIDE("右侧主光", "→"),
    TOP_HARSH("顶光较硬", "↓"),
    BACKLIT("强逆光", "☀"),
    LOW_LIGHT("环境偏暗", "☾"),
    EVEN("光线均匀", "◎"),
}

internal data class LightSample(
    val meanLuma: Int,
    val leftMean: Int,
    val rightMean: Int,
    val topMean: Int,
    val bottomMean: Int,
    val subjectVsBgDiff: Int? = null,
    val subjectLeftMean: Int? = null,
    val subjectRightMean: Int? = null,
    val subjectTopMean: Int? = null,
    val subjectBottomMean: Int? = null,
    val highlightSpot: Uv? = null,
    val hasFace: Boolean = false,
)

internal data class LightGuidance(
    val position: LightPosition,
    val sourceUv: Uv? = null,
    val tip: String,
    val isActionable: Boolean,
) {
    val badgeText: String
        get() = "${position.arrow} ${position.label}"

    val promptTag: String
        get() = "主光方位:${position.label}"
}

internal object LightFinder {
    fun analyze(sample: LightSample): LightGuidance {
        val diff = sample.subjectVsBgDiff
        val sideGrad = sample.leftMean - sample.rightMean
        val faceSideGrad = if (sample.subjectLeftMean != null && sample.subjectRightMean != null) {
            sample.subjectLeftMean - sample.subjectRightMean
        } else 0
        val faceTopGrad = if (sample.subjectTopMean != null && sample.subjectBottomMean != null) {
            sample.subjectTopMean - sample.subjectBottomMean
        } else 0
        val topGrad = sample.topMean - sample.bottomMean

        // 1. Severe low light
        if (sample.meanLuma < 52) {
            return LightGuidance(
                position = LightPosition.LOW_LIGHT,
                sourceUv = sample.highlightSpot,
                tip = "环境偏暗 · 靠近窗边或光源再拍",
                isActionable = true,
            )
        }

        // 2. Strong backlight (subject significantly darker than background)
        if (diff != null && diff <= -24) {
            return LightGuidance(
                position = LightPosition.BACKLIT,
                sourceUv = sample.highlightSpot,
                tip = if (sample.hasFace) "强逆光 · 侧身 30° 借发丝轮廓光" else "强逆光 · 可轻压曝光保高光层次",
                isActionable = true,
            )
        }

        // 3. Harsh overhead top light (forehead/top noticeably brighter than chin/bottom)
        if ((sample.hasFace && faceTopGrad >= 24) || (topGrad >= 42 && sample.topMean >= 165)) {
            return LightGuidance(
                position = LightPosition.TOP_HARSH,
                sourceUv = sample.highlightSpot ?: Uv(0.5, 0.12),
                tip = if (sample.hasFace) "头顶强光 · 退半步到阴影边缘更柔" else "顶光反差硬 · 寻找斜射光或阴影过渡",
                isActionable = true,
            )
        }

        // 4. Clear left or right directional side light (great for sculpting portraits!)
        val effectiveSide = if (sample.hasFace && kotlin.math.abs(faceSideGrad) >= 16) faceSideGrad else sideGrad
        if (effectiveSide >= 22) {
            return LightGuidance(
                position = LightPosition.LEFT_SIDE,
                sourceUv = sample.highlightSpot ?: Uv(0.14, 0.35),
                tip = if (sample.hasFace) "左侧窗/侧光 · 脸微转向左借光" else "左侧主光 · 顺着光影层次构图",
                isActionable = true,
            )
        }
        if (effectiveSide <= -22) {
            return LightGuidance(
                position = LightPosition.RIGHT_SIDE,
                sourceUv = sample.highlightSpot ?: Uv(0.86, 0.35),
                tip = if (sample.hasFace) "右侧窗/侧光 · 脸微转向右借光" else "右侧主光 · 顺着光影层次构图",
                isActionable = true,
            )
        }

        // 5. Clean front light vs even diffuse light
        if (diff != null && diff >= 20) {
            return LightGuidance(
                position = LightPosition.FRONT,
                sourceUv = sample.highlightSpot,
                tip = "顺光通透 · 肤色均匀适合直接拍摄",
                isActionable = false,
            )
        }

        return LightGuidance(
            position = LightPosition.EVEN,
            sourceUv = sample.highlightSpot,
            tip = "光比均匀 · 可安心构图",
            isActionable = false,
        )
    }
}

internal class LightGuidanceStabilizer(
    private val holdNs: Long = 1_500_000_000L,
) {
    private var stable: LightGuidance? = null
    private var pendingPosition: LightPosition? = null
    private var pendingSinceNs: Long = 0L

    fun update(candidate: LightGuidance?, nowNs: Long): LightGuidance? {
        if (candidate == null) return stable
        val current = stable
        if (current == null) {
            stable = candidate
            pendingPosition = null
            return stable
        }
        if (candidate.position == current.position) {
            stable = candidate
            pendingPosition = null
            return stable
        }
        if (pendingPosition != candidate.position) {
            pendingPosition = candidate.position
            pendingSinceNs = nowNs
            return current
        }
        if (nowNs - pendingSinceNs >= holdNs) {
            stable = candidate
            pendingPosition = null
        }
        return stable
    }

    fun reset() {
        stable = null
        pendingPosition = null
        pendingSinceNs = 0L
    }
}
