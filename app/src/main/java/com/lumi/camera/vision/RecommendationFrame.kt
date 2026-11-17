package com.lumi.camera.vision

import android.graphics.Bitmap
import android.graphics.Matrix
import java.io.ByteArrayOutputStream

internal class RecommendationFrame(val scene: SceneFrame, private val pixels: Bitmap) : AutoCloseable {
    fun jpeg(): ByteArray {
        val geometry = scene.geometry
        val crop = geometry.crop
        val scale = 768f / maxOf(crop.width, crop.height)
        val matrix = Matrix().apply { setScale(scale, scale); postRotate(geometry.rotation.toFloat()) }
        val upright = Bitmap.createBitmap(pixels, crop.left, crop.top, crop.width, crop.height, matrix, true)
        return try {
            ByteArrayOutputStream().use { out ->
                check(upright.compress(Bitmap.CompressFormat.JPEG, 85, out))
                out.toByteArray()
            }
        } finally {
            if (upright !== pixels) upright.recycle()
        }
    }

    override fun close() = pixels.recycle()
}
