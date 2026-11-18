package com.lumi.camera.vision

import androidx.camera.core.ImageProxy
import com.lumi.camera.camera.FrameGeometry

// All samples use the same upright, cropped coordinates as the model JPEG.
internal class SubjectPixels(val width: Int, val height: Int, private val pixels: IntArray) {
    fun at(x: Double, y: Double): Int? {
        if (x < 0 || x >= 1 || y < 0 || y >= 1) return null
        return pixels[(y * height).toInt() * width + (x * width).toInt()]
    }

    fun crop(x: Int, y: Int, w: Int, h: Int): SubjectPixels {
        val out = IntArray(w*h)
        for (row in 0 until h) pixels.copyInto(out, row*w, (y+row)*width+x, (y+row)*width+x+w)
        return SubjectPixels(w,h,out)
    }

    fun argb() = IntArray(pixels.size) { i ->
        val value = pixels[i]
        val y = (value shr 16) - 16
        val u = (value shr 8 and 255) - 128
        val v = (value and 255) - 128
        val r = ((298*y + 409*v + 128) shr 8).coerceIn(0,255)
        val g = ((298*y - 100*u - 208*v + 128) shr 8).coerceIn(0,255)
        val b = ((298*y + 516*u + 128) shr 8).coerceIn(0,255)
        (255 shl 24) or (r shl 16) or (g shl 8) or b
    }

    companion object {
        fun read(image: ImageProxy, frame: FrameGeometry): SubjectPixels {
            val ref = frame.referenceSize
            val height = 256
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
