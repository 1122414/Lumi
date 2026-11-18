package com.lumi.camera.vision

import com.lumi.camera.guidance.Uv

// Trace the outside of the four-connected foreground containing the selected pixel.
// Other objects and holes never become segments of this path.
internal class ForegroundMask private constructor(val width: Int, val height: Int,
    private val inside: BooleanArray, val bounds: FaceBox, val points: List<Uv?>, val area: Int) {
    fun contains(p: Uv): Boolean {
        val x = (p.x * width).toInt()
        val y = (p.y * height).toInt()
        return p.x >= 0 && p.y >= 0 && x in 0 until width && y in 0 until height && inside[y*width+x]
    }

    fun inImage(w: Int, h: Int, x: Int, y: Int, seed: Uv): ForegroundMask? {
        val bytes = ByteArray(w*h) { -1 }
        for (i in inside.indices) if (inside[i]) bytes[(i/width+y)*w+i%width+x] = 0
        return at(w,h,bytes,seed)
    }

    // The coarse instance selection may veto pixels, but never paints a boundary.
    // Where it cuts through a fused foreground (e.g. a toy touching a mirror), leave a gap.
    fun constrained(polygon: List<Uv>, seed: Uv): ForegroundMask? {
        if (polygon.size < 3) return null
        val allowed = BooleanArray(inside.size)
        val margin = maxOf(2, (width * 0.022).toInt())
        for (row in 0 until height) {
            val y = (row+0.5)/height
            val crossings = mutableListOf<Double>()
            var a = polygon.last()
            for (b in polygon) {
                if ((a.y > y) != (b.y > y)) crossings.add(a.x + (y-a.y)*(b.x-a.x)/(b.y-a.y))
                a = b
            }
            crossings.sort()
            for (i in 1 until crossings.size step 2) {
                val left = ((crossings[i-1]*width).toInt()-margin).coerceIn(0,width)
                val right = (kotlin.math.ceil(crossings[i]*width).toInt()+margin).coerceIn(0,width)
                for (j in maxOf(0,row-margin)..minOf(height-1,row+margin))
                    allowed.fill(true, j*width+left, j*width+right)
            }
        }
        val bytes = ByteArray(inside.size) { if (inside[it] && allowed[it]) 0 else -1 }
        val trimmed = at(width,height,bytes,seed, original = inside) ?: return null
        return trimmed.takeIf { it.area >= area*0.5 }
    }

    companion object {
        fun at(width: Int, height: Int, values: ByteArray, seed: Uv, original: BooleanArray? = null): ForegroundMask? {
            if (values.size != width*height || seed.x !in 0.0..<1.0 || seed.y !in 0.0..<1.0) return null
            val start = (seed.y*height).toInt()*width + (seed.x*width).toInt()
            // Magic Touch's interactive category mask uses 0 for selected and 255 for background.
            if (values[start] != 0.toByte()) return null
            val region = BooleanArray(values.size)
            val queue = IntArray(values.size)
            var head = 0
            var tail = 0
            fun add(i: Int) {
                if (!region[i] && values[i] == 0.toByte()) {
                    region[i] = true
                    queue[tail++] = i
                }
            }
            add(start)
            var first = start
            var left = width
            var top = height
            var right = 0
            var bottom = 0
            while (head < tail) {
                val i = queue[head++]
                first = minOf(first, i)
                val x = i % width
                val y = i / width
                left = minOf(left,x); right = maxOf(right,x+1)
                top = minOf(top,y); bottom = maxOf(bottom,y+1)
                if (x > 0) add(i-1)
                if (x+1 < width) add(i+1)
                if (y > 0) add(i-width)
                if (y+1 < height) add(i+width)
            }
            if (tail < 20 || tail > values.size*0.94) return null
            val bounds = FaceBox(left.toDouble()/width, top.toDouble()/height, right.toDouble()/width, bottom.toDouble()/height)
            fun filled(x: Int, y: Int) = x in 0 until width && y in 0 until height && region[y*width+x]
            var x = first % width
            var y = first / width
            var dir = 0
            fun boundary(d: Int): Boolean = when (d) {
                0 -> filled(x,y) && !filled(x,y-1)
                1 -> filled(x-1,y) && !filled(x,y)
                2 -> filled(x-1,y-1) && !filled(x-1,y)
                else -> filled(x,y-1) && !filled(x-1,y-1)
            }
            val points = ArrayList<Uv?>()
            val stride = maxOf(1, width/100)
            var steps = 0
            do {
                val outsideCell = when (dir) {
                    0 -> (y-1)*width+x
                    1 -> y*width+x
                    2 -> y*width+x-1
                    else -> (y-1)*width+x-1
                }
                val cut = original != null && outsideCell in original.indices && original[outsideCell]
                if (x == 0 || x == width || y == 0 || y == height || cut) {
                    if (points.isEmpty() || points.last() != null) points.add(null)
                } else if (steps % stride == 0) points.add(Uv(x.toDouble()/width, y.toDouble()/height))
                when (dir) { 0 -> x++; 1 -> y++; 2 -> x--; else -> y-- }
                dir = when {
                    boundary((dir+1)%4) -> (dir+1)%4
                    boundary(dir) -> dir
                    boundary((dir+3)%4) -> (dir+3)%4
                    else -> return null
                }
                if (++steps > 4*tail) return null
            } while (x != first%width || y != first/width || dir != 0)
            if (points.size < 4) return null
            return ForegroundMask(width, height, region, bounds, points, tail)
        }
    }
}
