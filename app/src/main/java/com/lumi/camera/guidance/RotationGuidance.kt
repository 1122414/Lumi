package com.lumi.camera.guidance

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
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
internal data class Lens(val fx: Double, val fy: Double, val cx: Double, val cy: Double) {
    fun lookAt(point: Uv): Pose {
        val x = (point.x - cx) / fx
        val y = (cy - point.y) / fy
        val yaw = -atan2(x, 1.0) / 2
        val pitch = atan2(y, hypot(x, 1.0)) / 2
        return Pose(cos(yaw), 0.0, sin(yaw), 0.0) * Pose(cos(pitch), sin(pitch), 0.0, 0.0)
    }
}
internal data class SubjectPosition(val id: Int, val center: Uv, val width: Double)
internal data class Observation(val timeNs: Long, val pose: Pose, val lens: Lens, val subject: SubjectPosition?,
    val completeSubject: Boolean = true, val matchedInterior: Boolean = false)

internal class RotationProjection(target: Observation, current: Observation) {
    private val a = target.lens
    private val b = current.lens
    private val rotation = current.pose.inverse() * target.pose
    private fun rayInCurrentView(point: Uv): Pose {
        val ray = Pose(0.0, (point.x - a.cx) / a.fx, (a.cy - point.y) / a.fy, -1.0)
        return rotation * ray * rotation.inverse()
    }

    fun project(point: Uv): Uv? {
        val v = rayInCurrentView(point)
        if (v.z >= -0.05) return null
        return Uv(b.cx + b.fx * v.x / -v.z, b.cy - b.fy * v.y / -v.z)
    }

    fun outsideView(corners: List<Uv>): Boolean {
        // Corners can straddle the camera plane at a side-on angle. Test clipping
        // before dividing by depth so the behind-camera corners still participate.
        val rays = corners.map(::rayInCurrentView)
        return rays.isNotEmpty() && (rays.all { it.z >= 0 } ||
            rays.all { b.fx*it.x < b.cx*it.z } || rays.all { b.fx*it.x > (b.cx-1)*it.z } ||
            rays.all { -b.fy*it.y < b.cy*it.z } || rays.all { -b.fy*it.y > (b.cy-1)*it.z })
    }

    fun onView(point: Uv, previousEdge: Uv): Uv {
        val v = rayInCurrentView(point)
        val depth = -v.z
        val dx = b.fx * v.x + (b.cx - 0.5) * depth
        val dy = -b.fy * v.y + (b.cy - 0.5) * depth
        val extent = maxOf(abs(dx), abs(dy))
        // At the antipode either way around is valid. Keep the arrival side through sensor noise.
        if (depth < 0 && extent < 0.05 * -depth) return previousEdge
        val divisor = maxOf(depth, 2 * extent)
        return Uv(0.5 + dx / divisor, 0.5 + dy / divisor)
    }
}

internal enum class GuideEvidence { CONFIRMED, PARTIAL_SUBJECT, BACKGROUND_UNCERTAIN, FACE_GAP, NO_FACE, OTHER_FACE, MISMATCH, NO_OBSERVATION, STALE, VERIFYING_COMPOSITION, RETURN_DIRECTION }

internal data class Guide(val ring: Uv?, val ready: Boolean = false,
    val evidence: GuideEvidence = GuideEvidence.NO_OBSERVATION, val merged: Boolean = ready) {
    val trusted get() = evidence == GuideEvidence.CONFIRMED
}

internal class ReturnDirection(private val target: Observation, private val source: Observation = target,
    private val subjectCorners: List<Uv> = emptyList()) {
    private var previousEdge = Uv(1.0, 0.5)

    fun display(visual: Guide, observed: Observation?, livePose: Pose?, subjectVisible: Boolean = false): Guide {
        val pose = livePose ?: observed?.pose ?: return visual
        val live = target.copy(pose = pose, lens = observed?.lens ?: target.lens)
        val goal = RotationProjection(target, live).onView(Uv.CENTER, previousEdge)
        val atEdge = maxOf(abs(goal.x - 0.5), abs(goal.y - 0.5)) >= 0.499999
        if (atEdge) previousEdge = goal
        if (subjectVisible && subjectCorners.isNotEmpty() && observed?.subject != null &&
            observed.subject.id == target.subject?.id && visual.ring != null) {
            // The ring is already measured in this frame. Only compensate the rotation since exposure.
            val ring = RotationProjection(observed, observed.copy(pose = pose)).onView(visual.ring, previousEdge)
            if (visual.merged && ring.distance(Uv.CENTER) > 0.045) return Guide(ring, evidence = GuideEvidence.RETURN_DIRECTION)
            return if (visual.merged) visual else visual.copy(ring = ring)
        }
        if (subjectCorners.isNotEmpty() && visual.ring == null) {
            val projection = RotationProjection(source, live)
            if (subjectVisible || !atEdge || !projection.outsideView(subjectCorners)) return visual
        }
        if (atEdge || (goal.distance(Uv.CENTER) > 0.045 && (visual.ring == null || visual.merged || visual.ready))) {
            return Guide(goal, evidence = GuideEvidence.RETURN_DIRECTION)
        }
        if (observed != null && visual.ring != null && !visual.merged && !visual.ready) {
            val ring = RotationProjection(observed, live).onView(visual.ring, previousEdge)
            return visual.copy(ring = ring)
        }
        return visual
    }
}

internal class RotationGuidance(private val target: Observation, private val verifyScene: Boolean = false) {
    private val subject = requireNotNull(target.subject)
    private var previous: Observation? = null
    private var since: Long? = null
    private var count = 0
    private var lastTimestamp = target.timeNs - 1
    private var lastFaceNs: Long? = null
    private var lastBackgroundNs: Long? = null
    private var lastReadyNs: Long? = null
    private var displayAim = Uv.CENTER

    private fun resetHold() {
        previous = null
        since = null
        count = 0
    }

    fun lost(reason: GuideEvidence = GuideEvidence.NO_OBSERVATION): Guide {
        resetHold()
        lastFaceNs = null
        lastBackgroundNs = null
        lastReadyNs = null
        displayAim = Uv.CENTER
        return Guide(null, evidence = reason)
    }

    private fun recentlyMerged(nowNs: Long, near: Boolean): Boolean {
        val last = lastReadyNs ?: return false
        if (near && nowNs - last in 0..MERGED_DISPLAY_NS) return true
        lastReadyNs = null
        return false
    }

    private fun measuredView(frame: Observation): Observation? {
        val seen = frame.subject ?: return null
        if ((!frame.completeSubject && !frame.matchedInterior) || seen.id != subject.id ||
            seen.width / subject.width !in 0.70..1.40) return null
        val targetRay = target.pose * target.lens.lookAt(subject.center)
        val measuredRay = frame.lens.lookAt(seen.center)
        val relative = (frame.pose * measuredRay).inverse() * targetRay
        // A matched point fixes two angles. Preserve the sensor's twist about that ray
        // for roll. Opposite-facing rays cannot resolve that twist reliably.
        val length = hypot(relative.w, relative.z)
        if (length < 0.70710678) return null
        val twist = Pose(relative.w / length, 0.0, 0.0, relative.z / length)
        return frame.copy(pose = targetRay * twist.inverse() * measuredRay.inverse())
    }

    private fun smoothedRing(current: Observation, observed: Uv, projection: RotationProjection): Uv? {
        val aim = RotationProjection(current, target).project(observed) ?: return null
        val elapsed = lastFaceNs?.let { current.timeNs - it }
        val weight = if (elapsed != null && elapsed in 1..FRESH_NS) 1.0 - exp(-elapsed / CORRECTION_NS) else 1.0
        displayAim = Uv(displayAim.x + weight * (aim.x - displayAim.x), displayAim.y + weight * (aim.y - displayAim.y))
        return projection.project(displayAim)
    }

    fun update(current: Observation, nowNs: Long, backgroundAgrees: Boolean): Guide =
        update(current, nowNs) { backgroundAgrees }

    fun update(frame: Observation, nowNs: Long, checkBackground: (Observation) -> Boolean): Guide {
        if (frame.timeNs <= lastTimestamp || nowNs - frame.timeNs !in 0..FRESH_NS) return lost(GuideEvidence.STALE)
        lastTimestamp = frame.timeNs
        val measured = if (verifyScene) measuredView(frame) else null
        val current = measured ?: frame
        val backgroundAgrees = checkBackground(current)
        val projection = RotationProjection(target, current)
        val center = projection.project(Uv.CENTER) ?: return lost(GuideEvidence.MISMATCH)
        val angle = current.pose.degreesTo(target.pose)
        val face = current.subject
        if (face == null) {
            if (verifyScene) return lost(GuideEvidence.NO_FACE)
            resetHold()
            val seen = lastFaceNs
            if (seen == null || nowNs - seen !in 0..FACE_GAP_NS) return lost(GuideEvidence.NO_FACE)
            val ring = projection.project(displayAim) ?: return lost(GuideEvidence.MISMATCH)
            return Guide(ring, evidence = GuideEvidence.FACE_GAP,
                merged = recentlyMerged(nowNs, angle <= 4.0 && ring.distance(Uv.CENTER) <= 0.045))
        }
        if (face.id != subject.id) return lost(GuideEvidence.OTHER_FACE)
        if (verifyScene && current.completeSubject && measured == null) return lost(GuideEvidence.MISMATCH)
        val width = face.width / subject.width
        val predicted = projection.project(subject.center) ?: return lost(GuideEvidence.MISMATCH)
        if (width !in 0.70..1.40 || face.center.distance(predicted) > 0.10) return lost(GuideEvidence.MISMATCH)
        val observed = if (measured != null) center else Uv(center.x + 0.6 * (face.center.x - predicted.x),
            center.y + 0.6 * (face.center.y - predicted.y))

        val ring = if (measured != null) center else smoothedRing(current, observed, projection) ?: return lost(GuideEvidence.MISMATCH)
        lastFaceNs = current.timeNs
        val offset = observed.distance(Uv.CENTER)
        val faceOffset = face.center.distance(subject.center)
        val near = offset <= 0.045 && faceOffset <= 0.055 && width in 0.80..1.24 && angle <= 4.0
        if (!backgroundAgrees) {
            resetHold()
            if (measured != null) {
                lastReadyNs = null
                return Guide(ring, evidence = GuideEvidence.VERIFYING_COMPOSITION)
            }
            if (verifyScene && lastBackgroundNs?.let { nowNs-it in 0..350_000_000L } != true)
                return lost(GuideEvidence.BACKGROUND_UNCERTAIN)
            return Guide(ring, evidence = GuideEvidence.BACKGROUND_UNCERTAIN, merged = recentlyMerged(nowNs, near))
        }

        lastBackgroundNs = current.timeNs
        if (!current.completeSubject) {
            resetHold()
            lastReadyNs = null
            return Guide(ring, evidence = GuideEvidence.PARTIAL_SUBJECT)
        }
        val prior = previous
        previous = frame
        val dt = prior?.let { current.timeNs - it.timeNs } ?: 0L
        val continuous = dt in 1..180_000_000L
        val wasReady = prior != null && prior.timeNs == lastReadyNs && continuous && near
        val still = prior != null && continuous && prior.pose.degreesTo(frame.pose) <= 8.0 * dt / 1e9
        val aligned = offset <= 0.025 && faceOffset <= 0.035 && width in 0.85..1.18 && angle <= 2.5
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
        val ready = wasReady || (count >= 3 && since?.let { current.timeNs - it >= 350_000_000L } == true)
        if (ready) lastReadyNs = current.timeNs
        return Guide(if (ready) Uv.CENTER else ring, ready, GuideEvidence.CONFIRMED, recentlyMerged(nowNs, near))
    }

    companion object {
        const val FRESH_NS = 250_000_000L
        private const val FACE_GAP_NS = 200_000_000L
        private const val CORRECTION_NS = 90_000_000.0
        private const val MERGED_DISPLAY_NS = 550_000_000L
    }
}
