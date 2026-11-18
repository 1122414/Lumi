package com.lumi.camera.vision

import com.lumi.camera.guidance.Observation
import com.lumi.camera.guidance.RotationProjection
import com.lumi.camera.guidance.SubjectPosition
import com.lumi.camera.guidance.Uv
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot

internal data class SeenOutline(val points: List<Uv?>, val subject: SubjectPosition?)

// The model supplies an ordered silhouette. Each anchor remembers the two colours on
// opposite sides of its edge in the sent frame. Only fresh matching edges are drawn.
internal class SubjectOutline private constructor(private val source: Observation, private val anchors: List<Anchor>) {
    private data class Anchor(val at: Uv, val inside: Uv, val outside: Uv, val inner: Int, val outer: Int)
    private var positions = anchors.map { it.at }
    private var lastSeenNs: Long? = null

    fun observe(current: Observation, image: SubjectPixels): SeenOutline? {
        val projection = RotationProjection(source, current)
        val reverse = RotationProjection(current, source)
        val continuous = lastSeenNs?.let { current.timeNs - it in 1..400_000_000L } == true
        data class Probe(val center: Uv, val dx: Double, val dy: Double, val edge: Edge?)
        var visible = 0
        var xx = 0.0
        var xy = 0.0
        var yy = 0.0
        var bx = 0.0
        var by = 0.0
        val probes = anchors.mapIndexed { i, anchor ->
            val center = projection.project(if (continuous) positions[i] else anchor.at) ?: return@mapIndexed null
            if (center.x !in -0.04..1.04 || center.y !in -0.04..1.04) return@mapIndexed null
            visible++
            if (anchor.inner == anchor.outer) return@mapIndexed null
            val inside = projection.project(anchor.inside) ?: return@mapIndexed null
            val outside = projection.project(anchor.outside) ?: return@mapIndexed null
            val dx = (inside.x - outside.x) / 2
            val dy = (inside.y - outside.y) / 2
            val edge = findEdge(image, center, dx, dy, anchor)
            if (edge != null) {
                // Each edge measures motion along its normal. Together they also locate corners
                // when the phone translates and the silhouette slides along a straight edge.
                val length = hypot(dx, dy)
                val nx = dx / length
                val ny = dy / length
                val movement = (edge.at.x - center.x)*nx + (edge.at.y - center.y)*ny
                xx += nx*nx
                xy += nx*ny
                yy += ny*ny
                bx += movement*nx
                by += movement*ny
            }
            Probe(center, dx, dy, edge)
        }
        val determinant = xx*yy - xy*xy
        val tx = if (determinant > 1.0) ((bx*yy - by*xy)/determinant).coerceIn(-0.05, 0.05) else 0.0
        val ty = if (determinant > 1.0) ((by*xx - bx*xy)/determinant).coerceIn(-0.05, 0.05) else 0.0
        val points = probes.mapIndexed { i, p ->
            if (p == null) null else if (abs(tx) + abs(ty) < 0.002) p.edge?.at else {
                val center = Uv(p.center.x + tx, p.center.y + ty)
                findEdge(image, center, p.dx, p.dy, anchors[i])?.at
            }
        }
        val count = points.count { it != null }
        if (count < 5 || count < visible * 0.50) return null
        lastSeenNs = current.timeNs
        positions = points.mapIndexed { i, p -> p?.let(reverse::project) ?: if (continuous) positions[i] else anchors[i].at }
        val seen = points.filterNotNull()
        val subject = if (count >= anchors.size * 0.72 && visible == anchors.size) {
            val left = seen.minOf { it.x }
            val right = seen.maxOf { it.x }
            val center = Uv((left + right) / 2, (seen.minOf { it.y } + seen.maxOf { it.y }) / 2)
            SubjectPosition(0, center, right - left)
        } else null
        return SeenOutline(points, subject)
    }

    companion object {
        private data class Edge(val at: Uv, val inner: Int, val outer: Int)

        private fun colorDistance(a: Int, b: Int): Int =
            abs((a shr 16) - (b shr 16)) + abs((a shr 8 and 255) - (b shr 8 and 255)) + abs((a and 255) - (b and 255))

        private fun findEdge(image: SubjectPixels, center: Uv, dx: Double, dy: Double, anchor: Anchor?): Edge? {
            var best: Edge? = null
            var bestScore = Double.NEGATIVE_INFINITY
            for (step in -6..6) {
                val x = center.x + step * dx * 0.55
                val y = center.y + step * dy * 0.55
                val inner = image.at(x + dx, y + dy) ?: continue
                val outer = image.at(x - dx, y - dy) ?: continue
                val contrast = colorDistance(inner, outer)
                if (contrast < 24) continue
                val score = if (anchor == null) contrast.coerceAtMost(120).toDouble() else {
                    val direct = colorDistance(inner, anchor.inner) + colorDistance(outer, anchor.outer)
                    val swapped = colorDistance(inner, anchor.outer) + colorDistance(outer, anchor.inner)
                    if (direct > 135 || direct + 18 >= swapped) continue
                    -direct.toDouble()
                } - abs(step) * 4.0
                if (score > bestScore) {
                    best = Edge(Uv(x,y), inner, outer)
                    bestScore = score
                }
            }
            return best
        }

        fun from(source: Observation, polygon: List<Uv>, image: SubjectPixels): SubjectOutline? {
            val anchors = mutableListOf<Anchor>()
            var attempted = 0
            for (i in polygon.indices) {
                val a = polygon[i]
                val b = polygon[(i + 1) % polygon.size]
                val x = b.x - a.x
                val y = b.y - a.y
                val length = hypot(x,y)
                if (length <= 0.0) continue
                val dx = -y / length * 0.014
                val dy = x / length * 0.014
                val samples = ceil(length / 0.018).toInt()
                attempted += samples
                for (j in 0 until samples) {
                    val f = (j + 0.5) / samples
                    val p = Uv(a.x + x*f, a.y + y*f)
                    val edge = findEdge(image, p, dx, dy, null)
                    // Keep gaps in the polygon order. A flat edge must not become a chord across the subject.
                    val at = edge?.at ?: p
                    anchors.add(Anchor(at, Uv(at.x+dx, at.y+dy), Uv(at.x-dx, at.y-dy),
                        edge?.inner ?: 0, edge?.outer ?: 0))
                }
            }
            if (attempted < 8 || anchors.count { it.inner != it.outer } < attempted * 0.50) return null
            return SubjectOutline(source, anchors)
        }
    }
}
