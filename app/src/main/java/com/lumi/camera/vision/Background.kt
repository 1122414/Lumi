package com.lumi.camera.vision

import androidx.camera.core.ImageProxy
import com.lumi.camera.camera.FrameGeometry
import com.lumi.camera.guidance.RotationProjection
import com.lumi.camera.guidance.Uv
import kotlin.math.sqrt

internal data class Probe(val point: Uv, val light: Double)
internal typealias BackgroundMark = List<Probe>

internal class Background(private val grid: IntArray) {
    init { require(grid.size == W * H) }
    private fun light(p: Uv): Double? {
        val x = p.x * W - 0.5
        val y = p.y * H - 0.5
        if (x < 0 || y < 0 || x >= W-1 || y >= H-1) return null
        val i = x.toInt() + y.toInt()*W
        val u = x - x.toInt()
        val v = y - y.toInt()
        return (1-v)*((1-u)*grid[i] + u*grid[i+1]) + v*((1-u)*grid[i+W] + u*grid[i+W+1])
    }

    fun record(face: FaceBox): List<BackgroundMark> {
        val marks = mutableListOf<BackgroundMark>()
        for (j in 1..5) for (i in 1..5) {
            val x = i / 6.0
            val y = j / 6.0
            if (x in face.left-0.10..face.right+0.10 && y >= face.top-0.08) continue
            val mark = listOf(Uv(x,y), Uv(x-0.035,y), Uv(x+0.035,y), Uv(x,y-0.035), Uv(x,y+0.035))
                .map { Probe(it, light(it)!!) }
            if (mark.maxOf { it.light } - mark.minOf { it.light } >= 24) marks.add(mark)
        }
        return marks
    }

    // Check saved contrasts at their attitude-predicted positions; no moving patch tracker.
    fun agrees(marks: List<BackgroundMark>, projection: RotationProjection): Boolean {
        if (marks.isEmpty()) return true
        var agrees = 0
        var visible = 0
        for (mark in marks) {
            val now = mark.mapNotNull { projection.project(it.point)?.let(::light) }
            if (now.size != mark.size) continue
            visible++
            val oldMean = mark.sumOf { it.light } / mark.size
            val mean = now.average()
            var dot = 0.0; var aa = 0.0; var bb = 0.0
            for (i in now.indices) {
                val a = mark[i].light - oldMean
                val b = now[i] - mean
                dot += a*b; aa += a*a; bb += b*b
            }
            if (bb >= now.size * 25 && dot >= 0.7 * sqrt(aa*bb)) agrees++
        }
        return agrees >= minOf(2, marks.size) && agrees * 2 >= visible
    }

    companion object {
        private const val W = 40
        private const val H = 52
        fun read(image: ImageProxy, frame: FrameGeometry): Background {
            val size = frame.referenceSize
            val plane = image.planes[0]
            val bytes = plane.buffer
            val start = bytes.position()
            val grid = IntArray(W*H)
            for (j in 0 until H) for (i in 0 until W) {
                var sum = 0
                for (v in 0..1) for (u in 0..1) {
                    val raw = frame.referenceToAnalysis((i + 0.25f + u*0.5f)*size.width/W,
                        (j + 0.25f + v*0.5f)*size.height/H)
                    sum += bytes.get(start + raw.y.toInt()*plane.rowStride + raw.x.toInt()*plane.pixelStride)
                        .toInt() and 255
                }
                grid[j*W+i] = sum / 4
            }
            return Background(grid)
        }
    }
}
