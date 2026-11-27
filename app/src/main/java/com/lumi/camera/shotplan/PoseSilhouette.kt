package com.lumi.camera.shotplan

internal enum class PoseSilhouette(
    val wireName: String,
    val label: String,
    val anchorX: Float,
    val headTopY: Float,
    val heightFraction: Float,
    val facingRight: Boolean,
    val isGroup: Boolean = false,
) {
    HALF_BODY_LEFT(
        wireName = "half_left",
        label = "左三分半身姿态",
        anchorX = 0.36f,
        headTopY = 0.17f,
        heightFraction = 0.68f,
        facingRight = true,
    ),
    HALF_BODY_RIGHT(
        wireName = "half_right",
        label = "右三分半身姿态",
        anchorX = 0.64f,
        headTopY = 0.17f,
        heightFraction = 0.68f,
        facingRight = false,
    ),
    CENTER_PORTRAIT(
        wireName = "center_portrait",
        label = "居中人像姿态",
        anchorX = 0.50f,
        headTopY = 0.16f,
        heightFraction = 0.70f,
        facingRight = true,
    ),
    FULL_BODY_SCENIC(
        wireName = "full_scenic",
        label = "人景全身比例",
        anchorX = 0.38f,
        headTopY = 0.44f,
        heightFraction = 0.48f,
        facingRight = true,
    ),
    COUPLE_GROUP(
        wireName = "group",
        label = "合影肩线平衡",
        anchorX = 0.50f,
        headTopY = 0.22f,
        heightFraction = 0.62f,
        facingRight = true,
        isGroup = true,
    );

    companion object {
        fun parse(raw: Any?): PoseSilhouette? {
            val key = (raw as? String)?.trim()?.lowercase() ?: return null
            if (key.isEmpty() || key == "none" || key == "null") return null
            return entries.firstOrNull { it.wireName == key }
        }

        fun inferForPortrait(
            choice: CompositionChoice?,
            faceCount: Int,
            isPerson: Boolean,
        ): PoseSilhouette? {
            if (!isPerson && faceCount <= 0) return null
            if (faceCount >= 2 || choice == CompositionChoice.GROUP_BALANCE) return COUPLE_GROUP
            return when (choice) {
                CompositionChoice.GROUP_BALANCE -> COUPLE_GROUP
                CompositionChoice.LEFT_THIRD -> HALF_BODY_LEFT
                CompositionChoice.RIGHT_THIRD -> HALF_BODY_RIGHT
                CompositionChoice.SCENIC_PORTRAIT, CompositionChoice.LOWER_THIRD -> FULL_BODY_SCENIC
                CompositionChoice.CENTER, CompositionChoice.LEADING_FRAME,
                CompositionChoice.TIGHT_TOP, CompositionChoice.MORE_HEADROOM,
                CompositionChoice.PULL_IN, CompositionChoice.KEEP, null -> CENTER_PORTRAIT
            }
        }
    }
}
