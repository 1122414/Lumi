package com.lumi.camera.filter

import android.graphics.Bitmap
import com.lumi.camera.vision.SubjectPixels

// Renders lightweight live preview thumbnails for the bottom filter selector using the exact same
// 3D LUT + YCbCr skin-tone preservation math as the real-time OpenGL shader.
internal object FilterThumbnailRenderer {
    const val THUMB_SIZE = 84

    fun sampleSquareFromPixels(pixels: SubjectPixels, size: Int = THUMB_SIZE): Bitmap? {
        val sw = pixels.width
        val sh = pixels.height
        if (sw <= 0 || sh <= 0) return null
        val cropSide = minOf(sw, sh)
        val x0 = (sw - cropSide) / 2
        val y0 = (sh - cropSide) / 2
        val srcArgb = pixels.argb()
        val dst = IntArray(size * size)
        for (y in 0 until size) {
            val sy = (y0 + (y * cropSide) / size).coerceIn(0, sh - 1)
            val srcRow = sy * sw
            val dstRow = y * size
            for (x in 0 until size) {
                val sx = (x0 + (x * cropSide) / size).coerceIn(0, sw - 1)
                dst[dstRow + x] = srcArgb[srcRow + sx]
            }
        }
        return Bitmap.createBitmap(dst, size, size, Bitmap.Config.ARGB_8888)
    }

    fun renderAllPresets(sourceSquare: Bitmap): Map<FilterPreset, Bitmap> {
        val w = sourceSquare.width
        val h = sourceSquare.height
        if (w <= 0 || h <= 0) return emptyMap()
        val srcPixels = IntArray(w * h)
        sourceSquare.getPixels(srcPixels, 0, w, 0, 0, w, h)

        val result = LinkedHashMap<FilterPreset, Bitmap>(FilterPreset.selectablePresets.size)
        val rgbTmp = FloatArray(3)
        for (preset in FilterPreset.selectablePresets) {
            if (preset == FilterPreset.ORIGINAL) {
                result[preset] = sourceSquare
                continue
            }
            val lut = ColorLut3D.forPreset(preset)
            val skinProtect = preset.baseGrade.skinProtect
            val isMonochrome = preset.baseGrade.saturation <= -0.99f
            val dst = IntArray(srcPixels.size)
            for (i in srcPixels.indices) {
                val px = srcPixels[i]
                val r = ((px ushr 16) and 0xFF) / 255f
                val g = ((px ushr 8) and 0xFF) / 255f
                val b = (px and 0xFF) / 255f

                lut.sample(r, g, b, rgbTmp)
                var outR = rgbTmp[0]
                var outG = rgbTmp[1]
                var outB = rgbTmp[2]

                if (!isMonochrome && skinProtect > 0.01f) {
                    val skin = ColorLut3D.skinWeightYCbCr(r, g, b) * skinProtect
                    if (skin > 0.01f) {
                        // Preserve LUT luminance/tone curve while restoring natural human skin chroma
                        val baseL = (0.299f * r + 0.587f * g + 0.114f * b).coerceAtLeast(0.005f)
                        val lutL = 0.299f * outR + 0.587f * outG + 0.114f * outB
                        val lumaScale = (lutL / baseL).coerceIn(0.65f, 1.38f)
                        val naturalR = (r * lumaScale).coerceIn(0f, 1f)
                        val naturalG = (g * lumaScale).coerceIn(0f, 1f)
                        val naturalB = (b * lumaScale).coerceIn(0f, 1f)
                        val keep = (skin * 0.68f).coerceIn(0f, 0.78f)
                        outR = outR + (naturalR - outR) * keep
                        outG = outG + (naturalG - outG) * keep
                        outB = outB + (naturalB - outB) * keep
                    }
                }

                val ri = (outR.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val gi = (outG.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val bi = (outB.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                dst[i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
            }
            result[preset] = Bitmap.createBitmap(dst, w, h, Bitmap.Config.ARGB_8888)
        }
        return result
    }
}
