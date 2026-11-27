package com.lumi.camera.camera

import android.view.Surface
import androidx.camera.core.ImageCapture
import kotlin.math.roundToInt

internal enum class FlashOption(val wire: String, val label: String) {
    OFF("off", "闪光灯关"),
    AUTO("auto", "自动闪光"),
    ON("on", "闪光灯开"),
    TORCH("torch", "常亮补光");

    fun next(): FlashOption = when (this) {
        OFF -> AUTO
        AUTO -> ON
        ON -> TORCH
        TORCH -> OFF
    }

    val usesTorch: Boolean get() = this == TORCH

    fun toImageCaptureFlashMode(): Int = when (this) {
        AUTO -> ImageCapture.FLASH_MODE_AUTO
        ON -> ImageCapture.FLASH_MODE_ON
        OFF, TORCH -> ImageCapture.FLASH_MODE_OFF
    }

    companion object {
        fun fromWire(raw: String?): FlashOption =
            entries.firstOrNull { it.wire == raw } ?: OFF
    }
}

internal data class PixelCropRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

internal enum class CaptureAspectRatio(
    val wire: String,
    val label: String,
    val portraitRatio: Float,
) {
    RATIO_3_4("3_4", "3:4", 3f / 4f),
    RATIO_1_1("1_1", "1:1", 1f),
    RATIO_9_16("9_16", "9:16", 9f / 16f);

    fun cropRectFor(imageWidth: Int, imageHeight: Int): PixelCropRect {
        if (imageWidth <= 0 || imageHeight <= 0 || this == RATIO_3_4) {
            return PixelCropRect(0, 0, imageWidth.coerceAtLeast(0), imageHeight.coerceAtLeast(0))
        }
        val landscape = imageWidth > imageHeight
        val targetRatio = when (this) {
            RATIO_3_4 -> if (landscape) 4f / 3f else 3f / 4f
            RATIO_1_1 -> 1f
            RATIO_9_16 -> if (landscape) 16f / 9f else 9f / 16f
        }
        val currentRatio = imageWidth.toFloat() / imageHeight.toFloat()
        return if (currentRatio > targetRatio) {
            val w = (imageHeight * targetRatio).roundToInt().coerceIn(1, imageWidth)
            val left = ((imageWidth - w) / 2).coerceAtLeast(0)
            PixelCropRect(left = left, top = 0, width = w, height = imageHeight)
        } else {
            val h = (imageWidth / targetRatio).roundToInt().coerceIn(1, imageHeight)
            val top = ((imageHeight - h) / 2).coerceAtLeast(0)
            PixelCropRect(left = 0, top = top, width = imageWidth, height = h)
        }
    }

    companion object {
        fun fromWire(raw: String?): CaptureAspectRatio =
            entries.firstOrNull { it.wire == raw } ?: RATIO_3_4
    }
}

internal enum class TimerOption(val wire: String, val label: String, val seconds: Int) {
    OFF("off", "关", 0),
    SEC_3("3s", "3s", 3),
    SEC_10("10s", "10s", 10);

    fun next(): TimerOption = when (this) {
        OFF -> SEC_3
        SEC_3 -> SEC_10
        SEC_10 -> OFF
    }

    companion object {
        fun fromWire(raw: String?): TimerOption =
            entries.firstOrNull { it.wire == raw } ?: OFF
    }
}

internal object DeviceOrientationHelper {
    private const val HYSTERESIS_DEG = 15

    // Resolves raw sensor orientation (0..359, or -1 unknown) into discrete upright buckets:
    // 0 (portrait), 90 (landscape right / top of phone pointing left), 270 (landscape left / top pointing right).
    fun resolveBucketDegrees(rawDegrees: Int, currentBucket: Int): Int {
        if (rawDegrees < 0) return currentBucket
        val normalized = ((rawDegrees % 360) + 360) % 360
        // Distance to each candidate bucket (0, 90, 270; ignore upside-down 180 to keep controls stable)
        val distToCurrent = angularDistance(normalized, currentBucket)
        if (distToCurrent < 45 + HYSTERESIS_DEG) return currentBucket
        return when {
            normalized < 45 || normalized >= 315 -> 0
            normalized in 45 until 135 -> 90
            normalized in 225 until 315 -> 270
            else -> currentBucket
        }
    }

    // Surface rotation passed to CameraX ImageCapture.targetRotation so landscape photos save upright.
    fun toSurfaceRotation(bucketDegrees: Int): Int = when (bucketDegrees) {
        90 -> Surface.ROTATION_270
        270 -> Surface.ROTATION_90
        180 -> Surface.ROTATION_180
        else -> Surface.ROTATION_0
    }

    // Counter-rotation for UI icons so icons stay upright relative to gravity.
    fun toUiIconRotation(bucketDegrees: Int): Float = when (bucketDegrees) {
        90 -> -90f
        270 -> 90f
        else -> 0f
    }

    private fun angularDistance(a: Int, b: Int): Int {
        val diff = kotlin.math.abs(a - b) % 360
        return if (diff > 180) 360 - diff else diff
    }
}

internal data class CameraControlsUiState(
    val flash: FlashOption = FlashOption.OFF,
    val hasFlash: Boolean = false,
    val aspectRatio: CaptureAspectRatio = CaptureAspectRatio.RATIO_3_4,
    val quickEvSteps: Float = 0f,
    val minEvSteps: Float = -2f,
    val maxEvSteps: Float = 2f,
    val canQuickEv: Boolean = true,
)
