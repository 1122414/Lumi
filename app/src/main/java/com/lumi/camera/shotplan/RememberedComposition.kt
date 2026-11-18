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
        if (!useOutlinePosition) return observation
        val seen = frame.outline?.takeIf { frame.follower === outline }
        return observation.copy(subject = seen?.subject)
    }

    companion object {
        fun from(frame: SceneFrame, recommendation: Recommendation? = null): RememberedComposition? {
            val observation = frame.observation ?: return null
            val background = frame.background ?: return null
            if (recommendation == null) {
                val face = frame.box ?: return null
                if (observation.subject == null) return null
                return RememberedComposition(frame.geometry, observation, observation, background.record(face))
            }
            val selected = recommendation.subject
            val pixels = frame.pixels ?: return null
            val outline = SubjectOutline.from(observation, selected.outline, pixels) ?: return null
            val subject = if (selected.person) {
                observation.subject?.takeIf { selected.contains(it.center) }
            } else outline.observe(observation, pixels)?.subject
            if (subject == null) return null
            val source = observation.copy(subject = subject)
            val box = if (selected.person) frame.box ?: return null else selected.bounds
            val target = recommendation.targetFrom(source, box) ?: return null
            val projection = RotationProjection(source, target)
            if (selected.outline.any {
                val point = projection.project(it)
                point == null || point.x !in 0.0..1.0 || point.y !in 0.0..1.0
            }) return null
            return RememberedComposition(frame.geometry, target, source, background.record(selected.bounds),
                outline, useOutlinePosition = !selected.person)
        }
    }
}
