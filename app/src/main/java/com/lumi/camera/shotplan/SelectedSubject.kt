package com.lumi.camera.shotplan

import com.lumi.camera.guidance.Uv
import com.lumi.camera.vision.FaceBox
import kotlin.math.abs

internal class SelectedSubject private constructor(val name: String, val person: Boolean, val outline: List<Uv>) {
    val bounds = FaceBox(outline.minOf { it.x }, outline.minOf { it.y }, outline.maxOf { it.x }, outline.maxOf { it.y })

    fun contains(p: Uv): Boolean {
        var inside = false
        var a = outline.last()
        for (b in outline) {
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x-a.x) * (p.y-a.y) / (b.y-a.y) + a.x) inside = !inside
            a = b
        }
        return inside
    }

    companion object {
        const val MAX_VERTICES = 128

        fun nameOf(raw: Any?): String? {
            val name = ((raw as? Map<*, *>)?.get("name") as? String)?.trim() ?: return null
            return name.takeIf { it.codePointCount(0, it.length) in 1..6 && it.all(Char::isLetterOrDigit) &&
                it !in setOf("主体", "物体", "对象") }
        }

        fun parse(raw: Any?): SelectedSubject? {
            val fields = raw as? Map<*, *> ?: return null
            if (fields.keys != setOf("name", "kind", "outline")) return null
            val name = nameOf(fields) ?: return null
            val person = when (fields["kind"]) { "person" -> true; "object" -> false; else -> return null }
            val vertices = fields["outline"] as? List<*> ?: return null
            if (vertices.size !in 6..MAX_VERTICES) return null
            fun coordinate(n: Any?): Double? = (n as? Number)?.toDouble()?.takeIf {
                it in 0.0..1000.0 && it == it.toInt().toDouble()
            }?.div(1000)
            val points = vertices.map {
                val pair = it as? List<*> ?: return null
                if (pair.size != 2) return null
                Uv(coordinate(pair[0]) ?: return null, coordinate(pair[1]) ?: return null)
            }.let { if (it.first() == it.last()) it.dropLast(1) else it }
            if (points.size < 6 || points.distinct().size != points.size) return null
            fun cross(a: Uv, b: Uv, c: Uv) = (b.x-a.x)*(c.y-a.y) - (b.y-a.y)*(c.x-a.x)
            var twiceArea = 0.0
            for (i in points.indices) {
                val a = points[i]
                val b = points[(i+1) % points.size]
                twiceArea += a.x*b.y - b.x*a.y
                for (j in i+2 until points.size) {
                    if (i == 0 && j == points.lastIndex) continue
                    val c = points[j]
                    val d = points[(j+1) % points.size]
                    if (cross(a,b,c)*cross(a,b,d) < 0 && cross(c,d,a)*cross(c,d,b) < 0) return null
                }
            }
            if (abs(twiceArea) < 0.004) return null
            return SelectedSubject(name, person, if (twiceArea > 0) points else points.reversed())
        }
    }
}
