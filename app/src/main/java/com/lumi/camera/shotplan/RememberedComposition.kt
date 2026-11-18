package com.lumi.camera.shotplan

import com.lumi.camera.camera.FrameGeometry
import com.lumi.camera.guidance.Observation
import com.lumi.camera.guidance.RotationProjection
import com.lumi.camera.guidance.Uv
import com.lumi.camera.vision.BackgroundMark
import com.lumi.camera.vision.SceneFrame
import com.lumi.camera.vision.SubjectOutline

internal class RememberedComposition private constructor(val geometry: FrameGeometry,
    val observation: Observation, val backgroundSource: Observation, val marks: List<BackgroundMark>,
    val outline: SubjectOutline? = null, private val useOutlinePosition: Boolean = false) {

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
        fun from(frame: SceneFrame): RememberedComposition? {
            val observation = frame.observation ?: return null
            val background = frame.background ?: return null
            val face = frame.box ?: return null
            if (observation.subject == null) return null
            return RememberedComposition(frame.geometry, observation, observation, background.record(face))
        }

        fun recommended(frame: SceneFrame, recommendation: Recommendation, selectedOutline: SubjectOutline?): PlanResult<RememberedComposition> {
            val observation = frame.observation ?: return PlanResult.Rejected(PlanProblem.FRAME_UNAVAILABLE)
            val background = frame.background ?: return PlanResult.Rejected(PlanProblem.FRAME_UNAVAILABLE)
            val selected = recommendation.subject
            val outline = selectedOutline ?: return PlanResult.Rejected(PlanProblem.UNCONFIRMED_OUTLINE)
            val subject = if (selected.person) {
                observation.subject?.takeIf { outline.containsAtCapture(it.center) }
            } else outline.sourceSubject
            if (subject == null) return PlanResult.Rejected(PlanProblem.UNCONFIRMED_FACE)
            val source = observation.copy(subject = subject)
            val box = if (selected.person) frame.box ?: return PlanResult.Rejected(PlanProblem.UNCONFIRMED_FACE) else outline.bounds
            val clipped = PlanResult.Rejected(PlanProblem.SUBJECT_CROPPED)
            val target = recommendation.targetFrom(source, box) ?: return clipped
            if (selected.person && recommendation.targetFrom(source, outline.bounds) == null) return clipped
            // The model selects an instance with a loose polygon. Its padding can leave
            // the view while every measured pixel of a fully outlined object stays inside.
            if (recommendation.aimPoint != Uv.CENTER && (selected.person || !outline.hasFullCaptureContour)) {
                val projection = RotationProjection(source, target)
                if (selected.outline.any {
                    val point = projection.project(it)
                    point == null || point.x !in 0.0..1.0 || point.y !in 0.0..1.0
                }) return clipped
            }
            return PlanResult.Accepted(RememberedComposition(frame.geometry, target, source, background.record(outline.bounds, wholeSubject = true),
                outline, useOutlinePosition = !selected.person))
        }
    }
}
