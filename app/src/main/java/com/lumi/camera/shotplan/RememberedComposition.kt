package com.lumi.camera.shotplan

import com.lumi.camera.camera.FrameGeometry
import com.lumi.camera.guidance.Lens
import com.lumi.camera.guidance.Observation
import com.lumi.camera.guidance.Pose
import com.lumi.camera.guidance.RotationProjection
import com.lumi.camera.guidance.StepDirection
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
    val suggestedZoom: Float? = null, val directorCue: DirectorCue? = null,
    val stepDirection: StepDirection? = null, val referenceGuidance: ReferenceGuidance? = null) {

    fun withPreset(selection: PresetSelection, livePose: Pose, liveLens: Lens, shift: TrackedShift?): RememberedComposition {
        val trackedOutline = outline ?: return this
        if (selection.preset == CompositionPreset.AUTO) {
            val restored = RememberedComposition(geometry, observation, backgroundSource, marks, outline,
                useOutlinePosition, originalChoice, originalDesiredSubjectCenter, originalChoice, originalDesiredSubjectCenter, suggestedZoom, directorCue, stepDirection, referenceGuidance)
            val liveTarget = restored.targetFor(livePose, liveLens, shift)
            return RememberedComposition(geometry, liveTarget, backgroundSource, marks, outline,
                useOutlinePosition, originalChoice, originalDesiredSubjectCenter, originalChoice, originalDesiredSubjectCenter, suggestedZoom, directorCue, stepDirection, referenceGuidance)
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
            useOutlinePosition, newChoice, newDesired0, originalChoice, originalDesiredSubjectCenter, suggestedZoom, directorCue, stepDirection, referenceGuidance)
        val liveTarget = updated.targetFor(livePose, liveLens, shift)
        return RememberedComposition(geometry, liveTarget, backgroundSource, marks, outline,
            useOutlinePosition, newChoice, newDesired0, originalChoice, originalDesiredSubjectCenter, suggestedZoom, directorCue, stepDirection, referenceGuidance)
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

    fun liveSubjectBounds(livePose: Pose, liveLens: Lens, shift: TrackedShift?): com.lumi.camera.vision.FaceBox? {
        val b0 = outline?.bounds ?: return null
        val center = predictedSubjectCenter(livePose, liveLens, shift) ?: return null
        val apparentScale = (shift?.scale ?: 1.0).coerceIn(0.5, 1.6)
        val w = ((b0.right - b0.left) * (liveLens.fx / backgroundSource.lens.fx) * apparentScale).coerceIn(0.05, 0.95)
        val h = ((b0.bottom - b0.top) * (liveLens.fy / backgroundSource.lens.fy) * apparentScale).coerceIn(0.05, 0.95)
        val left = (center.x - w / 2.0).coerceIn(0.0, 0.95)
        val top = (center.y - h / 2.0).coerceIn(0.0, 0.95)
        val right = (center.x + w / 2.0).coerceIn(left + 0.05, 1.0)
        val bottom = (center.y + h / 2.0).coerceIn(top + 0.05, 1.0)
        return com.lumi.camera.vision.FaceBox(left, top, right, bottom)
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
        val apparentScale = (shift?.scale ?: 1.0).coerceIn(0.5, 1.6)
        val liveW = (w0 * zoomScale * apparentScale).coerceIn(0.01, 1.0)
        val liveH = (h0 * (liveLens.fy / backgroundSource.lens.fy) * apparentScale).coerceIn(0.01, 1.0)
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
        const val SINGLE_FACE_NO_ID = -1
        const val GROUP_FACE_ID = -2

        // Match only the locked person's face (never the union of unrelated background diners).
        fun matchLiveFace(
            anchor: com.lumi.camera.guidance.SubjectPosition,
            predicted: Uv,
            trackedFaces: List<com.lumi.camera.vision.TrackedFace>,
            lensScale: Double = 1.0,
        ): Uv? {
            if (trackedFaces.isEmpty()) return null
            val scale = lensScale.coerceAtLeast(0.5)
            val expectedW = (anchor.width * scale).coerceAtLeast(0.02)
            fun faceCenter(b: com.lumi.camera.vision.FaceBox) = Uv((b.left + b.right) / 2.0, (b.top + b.bottom) / 2.0)

            if (anchor.id == GROUP_FACE_ID) {
                val maxGroupDist = (0.30 * scale).coerceAtMost(0.55)
                val nearby = trackedFaces.filter { tf ->
                    val c = faceCenter(tf.box)
                    kotlin.math.hypot(c.x - predicted.x, c.y - predicted.y) <= maxGroupDist
                }.map { it.box }
                if (nearby.size < 2) return null
                val u = SubjectCandidates.unionOf(nearby) ?: return null
                return faceCenter(u)
            }

            if (anchor.id >= 0) {
                val byId = trackedFaces.firstOrNull { it.id == anchor.id }
                if (byId != null) {
                    val c = faceCenter(byId.box)
                    val w = (byId.box.right - byId.box.left).coerceAtLeast(0.01)
                    val dist = kotlin.math.hypot(c.x - predicted.x, c.y - predicted.y)
                    val ratio = w / expectedW
                    if (dist <= 0.25 * scale && ratio in 0.45..2.2) return c
                }
            }

            val maxDist = (0.16 * scale).coerceAtMost(0.32)
            return trackedFaces.mapNotNull { tf ->
                val c = faceCenter(tf.box)
                val w = (tf.box.right - tf.box.left).coerceAtLeast(0.01)
                val ratio = w / expectedW
                val dist = kotlin.math.hypot(c.x - predicted.x, c.y - predicted.y)
                if (dist <= maxDist && ratio in 0.50..2.0) c to dist else null
            }.minByOrNull { it.second }?.first
        }

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

        fun closeup(frame: SceneFrame, aimUv: Uv, targetZoom: Float): RememberedComposition? {
            val source = frame.observation ?: return null
            val targetPose = source.pose * (source.lens.lookAt(aimUv) * source.lens.lookAt(Uv.CENTER).inverse())
            val targetObs = source.copy(pose = targetPose)
            return RememberedComposition(
                geometry = frame.geometry,
                observation = targetObs,
                backgroundSource = source,
                marks = emptyList(),
                suggestedZoom = targetZoom,
            )
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
                val candidatesInFrame = if (frame.trackedFaces.isNotEmpty()) {
                    frame.trackedFaces
                } else {
                    frame.faces.map { com.lumi.camera.vision.TrackedFace(null, it) }
                }
                val insideOutline = candidatesInFrame.filter { tf ->
                    val c = Uv((tf.box.left + tf.box.right) / 2.0, (tf.box.top + tf.box.bottom) / 2.0)
                    outline.containsAtCapture(c)
                }
                val insideSelection = if (insideOutline.isNotEmpty()) insideOutline else candidatesInFrame.filter { tf ->
                    val c = Uv((tf.box.left + tf.box.right) / 2.0, (tf.box.top + tf.box.bottom) / 2.0)
                    selected.contains(c)
                }
                when {
                    insideSelection.size == 1 -> {
                        val tf = insideSelection.first()
                        com.lumi.camera.guidance.SubjectPosition(
                            id = tf.id ?: SINGLE_FACE_NO_ID,
                            center = Uv((tf.box.left + tf.box.right) / 2.0, (tf.box.top + tf.box.bottom) / 2.0),
                            width = (tf.box.right - tf.box.left).coerceAtLeast(0.01),
                        )
                    }
                    insideSelection.size >= 2 -> {
                        val u = SubjectCandidates.unionOf(insideSelection.map { it.box })
                        u?.let {
                            com.lumi.camera.guidance.SubjectPosition(
                                id = GROUP_FACE_ID,
                                center = Uv((it.left + it.right) / 2.0, (it.top + it.bottom) / 2.0),
                                width = (it.right - it.left).coerceAtLeast(0.01),
                            )
                        }
                    }
                    candidatesInFrame.isEmpty() -> observation.subject?.takeIf { outline.containsAtCapture(it.center) }
                    else -> null
                } ?: if (recommendation.candidateId != null || frame.faces.size >= 2) outline.sourceSubject else null
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
                desiredSubjectCenter = desired0, suggestedZoom = effectivePlan.suggestedZoom,
                directorCue = effectivePlan.directorCue, stepDirection = effectivePlan.stepDirection,
                referenceGuidance = effectivePlan.referenceGuidance))
        }
    }
}
