package com.lumi.camera.guidance

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal data class Uv(val x: Double, val y: Double) {
    fun distance(other: Uv) = hypot(x - other.x, y - other.y)
    companion object { val CENTER = Uv(0.5, 0.5) }
}

// Unit quaternion: upright display axes (right, up, out of screen) to world.
internal data class Pose(val w: Double, val x: Double, val y: Double, val z: Double) {
    operator fun times(b: Pose) = Pose(
        w*b.w - x*b.x - y*b.y - z*b.z, w*b.x + x*b.w + y*b.z - z*b.y,
        w*b.y - x*b.z + y*b.w + z*b.x, w*b.z + x*b.y - y*b.x + z*b.w)
    fun inverse() = Pose(w, -x, -y, -z)
    fun forDisplay(quarterTurns: Int): Pose {
        // ROTATION_90 compensates a physical counterclockwise turn with clockwise rendering.
        val halfAngle = -quarterTurns * Math.PI / 4
        return this * Pose(cos(halfAngle), 0.0, 0.0, sin(halfAngle))
    }
    fun degreesTo(other: Pose) = Math.toDegrees(2 * acos(abs(w*other.w + x*other.x +
        y*other.y + z*other.z).coerceIn(0.0, 1.0)))
}

// Focal lengths and principal point in normalized upright ViewPort coordinates.
internal data class Lens(val fx: Double, val fy: Double, val cx: Double, val cy: Double)
internal data class FacePosition(val id: Int, val center: Uv, val width: Double)
internal data class Observation(val timeNs: Long, val pose: Pose, val lens: Lens, val face: FacePosition?)

internal class RotationProjection(target: Observation, current: Observation) {
    private val a = target.lens
    private val b = current.lens
    private val rotation = current.pose.inverse() * target.pose
    fun project(point: Uv): Uv? {
        val ray = Pose(0.0, (point.x - a.cx) / a.fx, (a.cy - point.y) / a.fy, -1.0)
        val v = rotation * ray * rotation.inverse()
        if (v.z >= -0.05) return null
        return Uv(b.cx + b.fx * v.x / -v.z, b.cy - b.fy * v.y / -v.z)
    }
}

internal data class Guide(val ring: Uv?, val ready: Boolean = false) {
    val trusted get() = ring != null
}

internal class RotationGuidance(private val target: Observation) {
    private val subject = requireNotNull(target.face)
    private var previous: Observation? = null
    private var since: Long? = null
    private var count = 0
    private var lastTimestamp = target.timeNs - 1

    fun lost(): Guide {
        previous = null
        since = null
        count = 0
        return Guide(null)
    }

    fun update(current: Observation, nowNs: Long, backgroundAgrees: Boolean): Guide {
        if (current.timeNs <= lastTimestamp) return lost()
        lastTimestamp = current.timeNs
        val face = current.face
        if (nowNs - current.timeNs !in 0..FRESH_NS || face == null || face.id != subject.id ||
            !backgroundAgrees || face.width / subject.width !in 0.70..1.40) return lost()

        val project = RotationProjection(target, current)
        val center = project.project(Uv.CENTER) ?: return lost()
        val predictedFace = project.project(subject.center) ?: return lost()
        val error = face.center.distance(predictedFace)
        if (error > 0.10) return lost()
        val ring = Uv(center.x + 0.6 * (face.center.x - predictedFace.x),
            center.y + 0.6 * (face.center.y - predictedFace.y))

        val prior = previous
        previous = current
        val dt = prior?.let { current.timeNs - it.timeNs }
        val still = prior != null && dt != null && dt in 1..180_000_000L &&
            prior.pose.degreesTo(current.pose) <= 8.0 * dt / 1e9
        val aligned = ring.distance(Uv.CENTER) <= 0.025 &&
            face.center.distance(subject.center) <= 0.035 && face.width / subject.width in 0.85..1.18 &&
            current.pose.degreesTo(target.pose) <= 2.5
        if (!aligned) {
            since = null
            count = 0
        } else {
            if (!still || since == null) {
                since = current.timeNs
                count = 0
            }
            count++
        }
        val ready = count >= 3 && since?.let { current.timeNs - it >= 350_000_000L } == true
        return Guide(if (ready) Uv.CENTER else ring, ready)
    }

    companion object { const val FRESH_NS = 250_000_000L }
}
