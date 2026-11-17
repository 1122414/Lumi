package com.lumi.camera.shotplan

import com.lumi.camera.camera.FrameGeometry
import com.lumi.camera.guidance.Observation
import com.lumi.camera.vision.BackgroundMark
import com.lumi.camera.vision.SceneFrame

internal class RememberedComposition private constructor(val geometry: FrameGeometry,
    val observation: Observation, val backgroundSource: Observation, val marks: List<BackgroundMark>) {
    companion object {
        fun from(frame: SceneFrame, recommendation: Recommendation? = null): RememberedComposition? {
            val observation = frame.observation ?: return null
            val face = frame.box ?: return null
            val background = frame.background ?: return null
            val target = if (recommendation == null) observation else recommendation.targetFrom(observation, face) ?: return null
            return RememberedComposition(frame.geometry, target, observation, background.record(face))
        }
    }
}
