package com.lumi.camera.shotplan

import com.lumi.camera.camera.FrameGeometry
import com.lumi.camera.guidance.Observation
import com.lumi.camera.guidance.RotationProjection
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
        fun from(frame: SceneFrame, recommendation: Recommendation? = null, selectedOutline: SubjectOutline? = null): RememberedComposition? {
            val observation = frame.observation ?: return null
            val background = frame.background ?: return null
            if (recommendation == null) {
                val face = frame.box ?: return null
                if (observation.subject == null) return null
                return RememberedComposition(frame.geometry, observation, observation, background.record(face))
            }
            val selected = recommendation.subject
            val outline = selectedOutline ?: return null
            val subject = if (selected.person) {
                observation.subject?.takeIf { outline.containsAtCapture(it.center) }
            } else outline.sourceSubject
            if (subject == null) return null
            val source = observation.copy(subject = subject)
            val box = if (selected.person) frame.box ?: return null else outline.bounds
            val target = recommendation.targetFrom(source, box) ?: return null
            if (recommendation.targetFrom(source, outline.bounds) == null) return null
            val projection = RotationProjection(source, target)
            if (selected.outline.any {
                val point = projection.project(it)
                point == null || point.x !in 0.0..1.0 || point.y !in 0.0..1.0
            }) return null
            return RememberedComposition(frame.geometry, target, source, background.record(outline.bounds, wholeSubject = true),
                outline, useOutlinePosition = !selected.person)
        }
    }
}
