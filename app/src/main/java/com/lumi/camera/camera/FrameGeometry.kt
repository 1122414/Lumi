package com.lumi.camera.camera

import kotlin.math.max
import kotlin.math.min

data class Point(val x: Float, val y: Float)
data class Extent(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) }
}
data class Crop(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    init { require(left >= 0 && top >= 0 && width > 0 && height > 0) }
}
data class FrameGeometry(val buffer: Extent, val crop: Crop, val rotation: Int) {
    init {
        require(crop.right <= buffer.width && crop.bottom <= buffer.height)
        require(rotation in setOf(0, 90, 180, 270))
    }
}

enum class CenterScale { FILL, FIT }

// Coordinates refer to pixel edges; the center of the first pixel is (0.5, 0.5).
// The reference plane is the shared ViewPort crop after clockwise display rotation.
class FrameMapping(val frame: FrameGeometry, val view: Extent, mode: CenterScale = CenterScale.FILL) {
    private val c = frame.crop
    private val swap = frame.rotation == 90 || frame.rotation == 270
    private val rw = if (swap) c.height else c.width
    private val rh = if (swap) c.width else c.height
    private val sx = view.width.toFloat() / rw
    private val sy = view.height.toFloat() / rh
    private val scale = if (mode == CenterScale.FILL) max(sx, sy) else min(sx, sy)
    private val dx = (view.width - rw * scale) / 2
    private val dy = (view.height - rh * scale) / 2

    fun toReference(p: Point): Point {
        val x = p.x - c.left
        val y = p.y - c.top
        return when (frame.rotation) {
            0 -> Point(x, y)
            90 -> Point(c.height - y, x)
            180 -> Point(c.width - x, c.height - y)
            else -> Point(y, c.width - x)
        }
    }

    fun toPreview(p: Point): Point = toReference(p).let { Point(dx + it.x * scale, dy + it.y * scale) }

    fun previewToAnalysis(p: Point): Point? {
        if (p.x !in 0f..view.width.toFloat() || p.y !in 0f..view.height.toFloat()) return null
        val x = (p.x - dx) / scale
        val y = (p.y - dy) / scale
        if (x !in 0f..rw.toFloat() || y !in 0f..rh.toFloat()) return null
        val raw = when (frame.rotation) {
            0 -> Point(x, y)
            90 -> Point(y, c.height - x)
            180 -> Point(c.width - x, c.height - y)
            else -> Point(c.width - y, x)
        }
        return Point(raw.x + c.left, raw.y + c.top)
    }
}
