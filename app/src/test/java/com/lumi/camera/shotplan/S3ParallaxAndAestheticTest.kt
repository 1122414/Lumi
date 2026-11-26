package com.lumi.camera.shotplan

import com.lumi.camera.guidance.Lens
import com.lumi.camera.guidance.Observation
import com.lumi.camera.guidance.ParallaxStepTracker
import com.lumi.camera.guidance.Pose
import com.lumi.camera.guidance.StepDirection
import com.lumi.camera.guidance.Uv
import com.lumi.camera.vision.FaceBox
import com.lumi.camera.vision.SubjectPixels
import com.lumi.camera.vision.TrackedShift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class S3ParallaxAndAestheticTest {

    @Test fun parallaxStepTrackerMeasuresLateralAndDepthStepsAndLatches() {
        val pose = Pose(1.0, 0.0, 0.0, 0.0)
        val lens = Lens(1.0, 1.0, 0.5, 0.5)
        val obs = Observation(0L, pose, lens, null)
        // Background with vertical stripes so background Dx estimation stays anchored at 0 when background does not move.
        val bgPixels = SubjectPixels(64, 64, IntArray(64 * 64) { i ->
            val x = i % 64
            val luma = if ((x / 8) % 2 == 0) 180 else 70
            (luma shl 16) or (128 shl 8) or 128
        })
        val subjectBox = FaceBox(0.35, 0.30, 0.65, 0.75)

        // STEP_LEFT expects foreground subject to shift RIGHT (+dx) relative to static background.
        val leftTracker = ParallaxStepTracker.fromBounds(StepDirection.LEFT, obs, subjectBox, bgPixels)
        // Wrong direction (negative dx) should make zero progress.
        val wrong = leftTracker.update(obs, bgPixels, TrackedShift(-0.08, 0.0, lens, 50_000_000L, matched = true))
        assertEquals(0f, wrong.progress, 0.01f)
        assertFalse(wrong.satisfied)

        // Stepping left (+0.075 parallax over several frames) reaches satisfied & latches.
        var stepState = wrong
        for (i in 1..8) {
            stepState = leftTracker.update(obs, bgPixels, TrackedShift(0.08, 0.0, lens, i * 60_000_000L, matched = true))
        }
        assertTrue("stepping left should satisfy parallax goal, got ${stepState.progress}", stepState.satisfied)
        assertEquals(1f, stepState.progress, 0.001f)

        // STEP_CLOSER tracks apparent scale growth (> 1.15).
        val closerTracker = ParallaxStepTracker.fromBounds(StepDirection.CLOSER, obs, subjectBox, bgPixels)
        var closerState = closerTracker.update(obs, bgPixels, TrackedShift(0.0, 0.0, lens, 50_000_000L, matched = true, scale = 1.0))
        assertFalse(closerState.satisfied)
        for (i in 1..8) {
            closerState = closerTracker.update(obs, bgPixels, TrackedShift(0.0, 0.0, lens, i * 60_000_000L, matched = true, scale = 1.20))
        }
        assertTrue("stepping closer should satisfy scale goal", closerState.satisfied)
    }

    @Test fun modelProtocolParsesStepDirectionAndInjectsPreferencePrior() {
        val candidates = listOf(SubjectCandidate(1, "人物", true, FaceBox(0.35, 0.25, 0.65, 0.80), Uv(0.50, 0.50)))
        val reply = ModelReply.from(
            fields = mapOf(
                "candidate" to 1,
                "name" to "女孩",
                "choice" to "left_third",
                "reason" to "避开柱子留白",
                "focus" to "background",
                "tip" to "向右挪半步避开柱子",
                "step" to "right",
            ),
            complete = true,
            candidates = candidates,
        )!!
        val plan = (reply.decision as PlanResult.Accepted).value
        assertEquals(StepDirection.RIGHT, plan.stepDirection)
        assertEquals(DirectorCategory.BACKGROUND, plan.directorCue?.category)

        val promptWithPrior = ChatProtocol.promptFor(
            preset = CompositionPreset.AUTO,
            candidates = candidates,
            zoomStops = listOf(1f, 2f),
            currentZoom = 1f,
            preferencePrior = "用户近期偏好三分留白构图，同等条件下可适当倾斜。",
        )
        assertTrue("prompt should include step schema", "step" in promptWithPrior)
        assertTrue("prompt should include local aesthetic prior", "三分留白构图" in promptWithPrior)
    }

    @Test fun photoQualityEvaluatorAndPreferenceProfileWorkEndToEnd() {
        val cleanBg = SubjectPixels(64, 64, IntArray(64 * 64) { (140 shl 16) or (128 shl 8) or 128 })
        val busyBg = SubjectPixels(64, 64, IntArray(64 * 64) { i ->
            val luma = if (((i % 64) / 2 + (i / 64) / 2) % 2 == 0) 220 else 30
            (luma shl 16) or (128 shl 8) or 128
        })
        val subject = FaceBox(0.24, 0.20, 0.50, 0.72)
        val cleanClutter = PhotoQualityEvaluator.estimateBackgroundClutter(cleanBg, subject)!!
        val busyClutter = PhotoQualityEvaluator.estimateBackgroundClutter(busyBg, subject)!!
        assertTrue("busy background clutter ($busyClutter) should exceed clean ($cleanClutter)", busyClutter > cleanClutter + 0.3)

        val goodScore = PhotoQualityEvaluator.evaluate(
            subjectBounds = subject,
            rollDeg = 0.8,
            wasReady = true,
            meanLuma = 135,
            zoomRatio = 2.0f,
            backgroundClutter = cleanClutter,
            unresolvedStep = false,
            directorCue = null,
        )
        val weakScore = PhotoQualityEvaluator.evaluate(
            subjectBounds = FaceBox(0.01, 0.01, 0.20, 0.25),
            rollDeg = 10.5,
            wasReady = false,
            meanLuma = 30,
            zoomRatio = 1.0f,
            backgroundClutter = busyClutter,
            unresolvedStep = true,
            directorCue = DirectorCue(DirectorCategory.BACKGROUND, "避开杂物"),
        )
        assertTrue("good shot (${goodScore.overall}) must outscore weak shot (${weakScore.overall})", goodScore.overall > weakScore.overall + 25)
        assertEquals("S", goodScore.grade)

        var profile = AestheticPreferenceProfile()
        assertNull("fewer than 3 signals should not inject prior", profile.promptPrior())
        profile = profile
            .recordComposition(CompositionChoice.LEFT_THIRD, 2.0f)
            .recordComposition(CompositionChoice.RIGHT_THIRD, 2.0f)
            .recordRecrop("rule_of_thirds")
        val prior = profile.promptPrior()
        assertNotNull(prior)
        assertTrue("prior should mention thirds preference: $prior", "三分" in prior!!)
    }
}
