package com.lumi.camera.shotplan

import com.lumi.camera.camera.FrameGeometry
import com.lumi.camera.guidance.Observation
import com.lumi.camera.vision.BackgroundMark
import com.lumi.camera.vision.SceneFrame

internal class RememberedComposition private constructor(val geometry: FrameGeometry,
    val observation: Observation, val marks: List<BackgroundMark>) {
    companion object {
        fun from(frame: SceneFrame): RememberedComposition? {
            val observation = frame.observation ?: return null
            val face = frame.box ?: return null
            val background = frame.background ?: return null
            return RememberedComposition(frame.geometry, observation, background.record(face))
        }
    }
}
