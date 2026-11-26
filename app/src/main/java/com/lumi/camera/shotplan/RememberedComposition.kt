package com.lumi.camera.shotplan

import com.lumi.camera.camera.FrameGeometry
import com.lumi.camera.guidance.Lens
import com.lumi.camera.guidance.Observation
import com.lumi.camera.guidance.Pose
import com.lumi.camera.guidance.RotationProjection
import com.lumi.camera.guidance.Uv
import com.lumi.camera.vision.BackgroundMark
import com.lumi.camera.vision.SceneFrame
import com.lumi.camera.vision.SubjectOutline
import com.lumi.camera.vision.TrackedShift

internal class RememberedComposition private constructor(val geometry: FrameGeometry,
    val observation: Observation, val backgroundSource: Observation, val marks: List<BackgroundMark>,
    val outline: SubjectOutline? = null, private val useOutlinePosition: Boolean = false,
    val choice: CompositionChoice? = null, val desiredSubjectCenter: Uv? = null,
    val originalChoice: CompositionChoice? = choice, val originalDesiredSubjectCenter: Uv? = desiredSubjectCenter,
    val suggestedZoom: Float? = null) {

    fun withPreset(selection: PresetSelection, livePose: Pose, liveLens: Lens, shift: TrackedShift?): RememberedComposition {
        val trackedOutline = outline ?: return this
        if (selection.preset == CompositionPreset.AUTO) {
            val restored = RememberedComposition(geometry, observation, backgroundSource, marks, outline,
                useOutlinePosition, originalChoice, originalDesiredSubjectCenter, originalChoice, originalDesiredSubjectCenter, suggestedZoom)
            val liveTarget = restored.targetFor(livePose, liveLens, shift)
            return RememberedComposition(geometry, liveTarget, backgroundSource, marks, outline,
                useOutlinePosition, originalChoice, originalDesiredSubjectCenter, originalChoice, originalDesiredSubjectCenter, suggestedZoom)
        }
        val b0 = trackedOutline.bounds
        val w0 = (b0.right - b0.left).coerceIn(0.01, 1.0)
        val h0 = (b0.bottom - b0.top).coerceIn(0.01, 1.0)
        val liveW = (w0 * (liveLens.fx / backgroundSource.lens.fx)).coerceIn(0.01, 1.0)
        val liveH = (h0 * (liveLens.fy / backgroundSource.lens.fy)).coerceIn(0.01, 1.0)
        val liveCenter = predictedSubjectCenter(livePose, liveLens, shift)
            ?: Uv((b0.left + b0.right) / 2.0, (b0.top + b0.bottom) / 2.0)
        val liveBox = com.lumi.camera.vision.FaceBox(
            (liveCenter.x - liveW / 2.0).coerceIn(0.0, 1.0),
            (liveCenter.y - liveH / 2.0).coerceIn(0.0, 1.0),
            (liveCenter.x + liveW / 2.0).coerceIn(0.0, 1.0),
            (liveCenter.y + liveH / 2.0).coerceIn(0.0, 1.0),
        )
        val newChoice = selection.preset.resolveChoice(liveBox, originalChoice, selection.thirdChoice) ?: return this
        val anchorDesired = originalDesiredSubjectCenter ?: Uv((b0.left + b0.right) / 2.0, (b0.top + b0.bottom) / 2.0)
        // Compute capture-equivalent desired center so targetFor handles both 1x and zoomed views smoothly.
        val captureAim = CompositionChoice.aimPointFor(b0, newChoice)
        val c0 = Uv((b0.left + b0.right) / 2.0, (b0.top + b0.bottom) / 2.0)
        val newDesired0 = when (newChoice) {
            CompositionChoice.TIGHT_TOP -> Uv(anchorDesired.x.coerceIn(0.34, 0.66), 0.14 + h0 / 2.0)
            CompositionChoice.MORE_HEADROOM -> Uv(anchorDesired.x.coerceIn(0.34, 0.66), 0.24 + h0 / 2.0)
            else -> Uv(c0.x - (captureAim.x - 0.5), c0.y - (captureAim.y - 0.5))
        }
        val updated = RememberedComposition(geometry, observation, backgroundSource, marks, outline,
            useOutlinePosition, newChoice, newDesired0, originalChoice, originalDesiredSubjectCenter, suggestedZoom)
        val liveTarget = updated.targetFor(livePose, liveLens, shift)
        return RememberedComposition(geometry, liveTarget, backgroundSource, marks, outline,
            useOutlinePosition, newChoice, newDesired0, originalChoice, originalDesiredSubjectCenter, suggestedZoom)
    }

    fun predictedSubjectCenter(livePose: Pose, liveLens: Lens, shift: TrackedShift?): Uv? {
        val trackedOutline = outline ?: return null
        val b = trackedOutline.bounds
        val c0 = Uv((b.left + b.right) / 2.0, (b.top + b.bottom) / 2.0)
        val liveObs = backgroundSource.copy(pose = livePose, lens = liveLens)
        val projected = RotationProjection(backgroundSource, liveObs).project(c0) ?: return null
        val shiftDx = if (shift != null) shift.dx * (liveLens.fx / shift.lens.fx) else 0.0
        val shiftDy = if (shift != null) shift.dy * (liveLens.fy / shift.lens.fy) else 0.0
        return Uv(projected.x + shiftDx, projected.y + shiftDy)
    }

    // Recompute the live target camera pose from the tracked subject position and current zoom.
    // Anchors back to the recommended 3D viewpoint if the photographer pitches or translates far away
    // so pitching down at clutter can never falsely merge the guidance ring.
    fun targetFor(livePose: Pose, liveLens: Lens, shift: TrackedShift?): Observation {
        val trackedOutline = outline ?: return observation
        val desired0 = desiredSubjectCenter ?: return observation
        val b0 = trackedOutline.bounds
        val c0 = Uv((b0.left + b0.right) / 2.0, (b0.top + b0.bottom) / 2.0)
        val w0 = (b0.right - b0.left).coerceIn(0.01, 1.0)
        val h0 = (b0.bottom - b0.top).coerceIn(0.01, 1.0)
        val zoomScale = (liveLens.fx / backgroundSource.lens.fx).coerceAtLeast(0.5)
        val liveW = (w0 * zoomScale).coerceIn(0.01, 1.0)
        val liveH = (h0 * (liveLens.fy / backgroundSource.lens.fy)).coerceIn(0.01, 1.0)
        val desired = CompositionChoice.desiredCenterFor(desired0, h0, liveW, liveH, choice)
        val anchorTargetPose = backgroundSource.pose * (backgroundSource.lens.lookAt(c0) * liveLens.lookAt(desired).inverse())
        val pitchDrift = kotlin.math.abs(livePose.elevationDeg(liveLens.front) - anchorTargetPose.elevationDeg(liveLens.front))
        val shiftMag = if (shift != null) {
            kotlin.math.hypot(shift.dx * (liveLens.fx / shift.lens.fx), shift.dy * (liveLens.fy / shift.lens.fy))
        } else 0.0
        val maxShift = MAX_PARALLAX_SHIFT * zoomScale
        if (pitchDrift > MAX_VIEWPOINT_PITCH_DRIFT_DEG || shiftMag > maxShift) {
            return observation.copy(pose = anchorTargetPose, lens = liveLens)
        }
        val liveCenter = predictedSubjectCenter(livePose, liveLens, shift)
        val targetPose = if (liveCenter != null) {
            livePose * (liveLens.lookAt(liveCenter) * liveLens.lookAt(desired).inverse())
        } else {
            anchorTargetPose
        }
        return observation.copy(pose = targetPose, lens = liveLens)
    }

    fun isViewpointDrifted(livePose: Pose, liveLens: Lens): Boolean {
        val trackedOutline = outline ?: return false
        val desired0 = desiredSubjectCenter ?: return false
        val b0 = trackedOutline.bounds
        val c0 = Uv((b0.left + b0.right) / 2.0, (b0.top + b0.bottom) / 2.0)
        val w0 = (b0.right - b0.left).coerceIn(0.01, 1.0)
        val h0 = (b0.bottom - b0.top).coerceIn(0.01, 1.0)
        val liveW = (w0 * (liveLens.fx / backgroundSource.lens.fx)).coerceIn(0.01, 1.0)
        val liveH = (h0 * (liveLens.fy / backgroundSource.lens.fy)).coerceIn(0.01, 1.0)
        val desired = CompositionChoice.desiredCenterFor(desired0, h0, liveW, liveH, choice)
        val anchorTargetPose = backgroundSource.pose * (backgroundSource.lens.lookAt(c0) * liveLens.lookAt(desired).inverse())
        return kotlin.math.abs(livePose.elevationDeg(liveLens.front) - anchorTargetPose.elevationDeg(liveLens.front)) > MAX_VIEWPOINT_PITCH_DRIFT_DEG
    }

    fun current(frame: SceneFrame): Observation? {
        val observation = frame.observation ?: return null
        if (outline == null) return observation
        val seen = frame.outline?.takeIf { frame.follower === outline }
        val verified = if (useOutlinePosition) seen?.position else observation.subject?.takeIf {
            seen?.mask?.contains(it.center) == true
        }
        return observation.copy(subject = verified, completeSubject = seen?.complete == true,
            matchedInterior = if (useOutlinePosition) seen?.matchedInterior == true else verified != null)
    }

    companion object {
        const val MAX_VIEWPOINT_PITCH_DRIFT_DEG = 12.0
        const val MAX_PARALLAX_SHIFT = 0.35

        fun from(frame: SceneFrame): RememberedComposition? {
            val observation = frame.observation ?: return null
            val background = frame.background ?: return null
            val face = frame.box ?: return null
            if (observation.subject == null) return null
            return RememberedComposition(frame.geometry, observation, observation, background.record(face))
        }

        fun remembered(frame: SceneFrame): RememberedComposition? {
            val observation = frame.observation ?: return null
            return RememberedComposition(frame.geometry, observation, observation, emptyList())
        }

        fun recommended(
            frame: SceneFrame,
            recommendation: Recommendation,
            selectedOutline: SubjectOutline?,
            currentZoom: Float = 1f,
        ): PlanResult<RememberedComposition> {
            val observation = frame.observation ?: return PlanResult.Rejected(PlanProblem.FRAME_UNAVAILABLE)
            val selected = recommendation.subject
            val outline = selectedOutline ?: return PlanResult.Rejected(PlanProblem.UNCONFIRMED_OUTLINE)
            val matchedFace = if (selected.person) {
                observation.subject?.takeIf { outline.containsAtCapture(it.center) }
                    ?: frame.faces.firstOrNull { f ->
                        val c = Uv((f.left + f.right) / 2.0, (f.top + f.bottom) / 2.0)
                        outline.containsAtCapture(c)
                    }?.let { f ->
                        com.lumi.camera.guidance.SubjectPosition(
                            id = 0,
                            center = Uv((f.left + f.right) / 2.0, (f.top + f.bottom) / 2.0),
                            width = f.right - f.left,
                        )
                    }
                    ?: if (recommendation.candidateId != null || frame.faces.size >= 2) outline.sourceSubject else null
            } else outline.sourceSubject
            if (matchedFace == null) return PlanResult.Rejected(PlanProblem.UNCONFIRMED_FACE)
            val source = observation.copy(subject = matchedFace)
            val effectivePlan = if (recommendation.choice != null) recommendation.withMeasuredBounds(outline.bounds) else recommendation
            val box = if (selected.person && frame.faces.size == 1 && frame.box != null) frame.box else outline.bounds
            val clipped = PlanResult.Rejected(PlanProblem.SUBJECT_CROPPED)
            val target = effectivePlan.targetFrom(source, box, currentZoom) ?: return clipped
            if (selected.person && effectivePlan.targetFrom(source, outline.bounds, currentZoom) == null) return clipped
            // The model selects an instance with a loose polygon. Its padding can leave
            // the view while every measured pixel of a fully outlined object stays inside.
            if (effectivePlan.aimPoint != Uv.CENTER && (selected.person || !outline.hasFullCaptureContour)) {
                val projection = RotationProjection(source, target)
                if (selected.outline.any {
                    val point = projection.project(it)
                    point == null || point.x !in 0.0..1.0 || point.y !in 0.0..1.0
                }) return clipped
            }
            val marks = frame.background?.record(outline.bounds, wholeSubject = true).orEmpty()
            val ob = outline.bounds
            val c0 = Uv((ob.left + ob.right) / 2.0, (ob.top + ob.bottom) / 2.0)
            val desired0 = Uv(c0.x - (effectivePlan.aimPoint.x - 0.5), c0.y - (effectivePlan.aimPoint.y - 0.5))
            return PlanResult.Accepted(RememberedComposition(frame.geometry, target, source, marks,
                outline, useOutlinePosition = !selected.person, choice = effectivePlan.choice,
                desiredSubjectCenter = desired0, suggestedZoom = effectivePlan.suggestedZoom))
        }
    }
}
