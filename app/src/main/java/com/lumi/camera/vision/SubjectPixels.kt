package com.lumi.camera.vision

import androidx.camera.core.ImageProxy
import com.lumi.camera.camera.FrameGeometry

// All samples use the same upright, cropped coordinates as the model JPEG.
internal class SubjectPixels(val width: Int, val height: Int, private val pixels: IntArray) {
    fun at(x: Double, y: Double): Int? {
        if (x < 0 || x >= 1 || y < 0 || y >= 1) return null
        return pixels[(y * height).toInt() * width + (x * width).toInt()]
    }

    companion object {
        fun read(image: ImageProxy, frame: FrameGeometry): SubjectPixels {
            val ref = frame.referenceSize
            val height = 160
            val width = (height * ref.width / ref.height).coerceIn(48, 240)
            val planes = image.planes
            val y = planes[0]
            val u = planes[1]
            val v = planes[2]
            val yb = y.buffer
            val ub = u.buffer
            val vb = v.buffer
            val yStart = yb.position()
            val uStart = ub.position()
            val vStart = vb.position()
            val xs = FloatArray(width) { (it + 0.5f) * ref.width / width }
            val pixels = IntArray(width * height)
            for (row in 0 until height) {
                val py = (row + 0.5f) * ref.height / height
                for (col in 0 until width) {
                    val raw = frame.referenceToAnalysis(xs[col], py)
                    val rx = raw.x.toInt()
                    val ry = raw.y.toInt()
                    val light = yb.get(yStart + ry*y.rowStride + rx*y.pixelStride).toInt() and 255
                    val blue = ub.get(uStart + ry/2*u.rowStride + rx/2*u.pixelStride).toInt() and 255
                    val red = vb.get(vStart + ry/2*v.rowStride + rx/2*v.pixelStride).toInt() and 255
                    pixels[row*width + col] = (light shl 16) or (blue shl 8) or red
                }
            }
            return SubjectPixels(width, height, pixels)
        }
    }
}
