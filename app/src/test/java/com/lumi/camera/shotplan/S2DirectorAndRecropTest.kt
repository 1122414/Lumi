package com.lumi.camera.shotplan

import com.lumi.camera.guidance.Pose
import com.lumi.camera.guidance.Uv
import com.lumi.camera.vision.FaceBox
import com.lumi.camera.vision.PostShotRecrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class S2DirectorAndRecropTest {
    private fun yaw(deg: Double): Pose {
        val h = Math.toRadians(deg) / 2.0
        return Pose(cos(h), 0.0, 0.0, sin(h))
    }

    @Test fun directorCueParsesExplicitAndInferredCategoriesAndCompactsLongTips() {
        val explicit = DirectorCue.parse("lighting", "侧身迎着窗光更柔和")!!
        assertEquals(DirectorCategory.LIGHTING, explicit.category)
        assertEquals("侧身迎着窗光更柔和", explicit.tip)

        val inferredPose = DirectorCue.parse(null, "抬头看镜头自然微笑")!!
        assertEquals(DirectorCategory.POSE, inferredPose.category)

        val longTip = DirectorCue.parse("background", "往左挪半步避开杂物，这样主体更干净突出")!!
        assertEquals(DirectorCategory.BACKGROUND, longTip.category)
        assertTrue("compacted tip must be <= 12 chars, got ${longTip.tip}",
            longTip.tip.codePointCount(0, longTip.tip.length) <= DirectorCue.MAX_TIP_CHARS)

        val candidates = listOf(SubjectCandidate(1, "人像", true, FaceBox(0.30, 0.20, 0.65, 0.80), Uv(0.47, 0.50)))
        val reply = ModelReply.from(
            fields = mapOf(
                "candidate" to 1,
                "name" to "女孩",
                "choice" to "left_third",
                "reason" to "人物左三分留白",
                "focus" to "pose",
                "tip" to "微扬下巴自然笑",
            ),
            complete = true,
            candidates = candidates,
        )!!
        val plan = (reply.decision as PlanResult.Accepted).value
        assertNotNull(plan.directorCue)
        assertEquals(DirectorCategory.POSE, plan.directorCue!!.category)
        assertEquals("微扬下巴自然笑", plan.directorCue!!.tip)
    }

    @Test fun proactiveTriggerFiresOnSteadyHoldUrgentIssuesAndRespectsCooldown() {
        val trigger = ProactiveTrigger()
        val p0 = yaw(0.0)
        // Initial frame starts steady timer, does not fire immediately.
        assertNull(trigger.evaluate(0L, p0, 0.5, emptyList(), 120, hasActiveGuidance = false, isRequestBusy = false))
        assertNull(trigger.evaluate(500_000_000L, p0, 0.5, emptyList(), 120, hasActiveGuidance = false, isRequestBusy = false))
        // After >= 900ms steady hold, fires STEADY_HOLD.
        assertEquals(
            ProactiveReason.STEADY_HOLD,
            trigger.evaluate(950_000_000L, p0, 0.5, emptyList(), 120, hasActiveGuidance = false, isRequestBusy = false),
        )
        // During cooldown / same scene, does not re-fire.
        assertNull(trigger.evaluate(3_000_000_000L, p0, 0.5, emptyList(), 120, hasActiveGuidance = false, isRequestBusy = false))
        assertNull(trigger.evaluate(10_000_000_000L, p0, 0.5, emptyList(), 120, hasActiveGuidance = false, isRequestBusy = false))

        // Panning to a new scene (> 18°) after cooldown unlocks trigger; clipped head fires quickly (450ms).
        val pNew = yaw(25.0)
        val clippedFace = listOf(FaceBox(0.35, 0.01, 0.65, 0.35))
        assertNull(trigger.evaluate(10_100_000_000L, pNew, 0.5, clippedFace, 120, hasActiveGuidance = false, isRequestBusy = false))
        assertEquals(
            ProactiveReason.CLIPPED_HEAD,
            trigger.evaluate(10_600_000_000L, pNew, 0.5, clippedFace, 120, hasActiveGuidance = false, isRequestBusy = false),
        )

        // Severe tilt and low light also trigger when fresh and idle.
        trigger.reset()
        assertNull(trigger.evaluate(0L, p0, 10.5, emptyList(), 120, hasActiveGuidance = false, isRequestBusy = false))
        assertEquals(
            ProactiveReason.SEVERE_TILT,
            trigger.evaluate(500_000_000L, p0, 10.5, emptyList(), 120, hasActiveGuidance = false, isRequestBusy = false),
        )

        trigger.reset()
        assertNull(trigger.evaluate(0L, p0, 0.2, emptyList(), 30, hasActiveGuidance = false, isRequestBusy = false))
        assertEquals(
            ProactiveReason.LOW_LIGHT,
            trigger.evaluate(500_000_000L, p0, 0.2, emptyList(), 30, hasActiveGuidance = false, isRequestBusy = false),
        )

        assertEquals(ProactivityMode.PROACTIVE, ProactivityMode.MANUAL.next())
        assertEquals(ProactivityMode.QUIET, ProactivityMode.PROACTIVE.next())
        assertEquals(ProactivityMode.MANUAL, ProactivityMode.QUIET.next())
    }

    @Test fun postShotRecropGeneratesDistinctSubjectSafeProposalsAndSummary() {
        val subject = FaceBox(0.18, 0.25, 0.48, 0.72)
        val proposals = PostShotRecrop.generateProposals(subject)
        assertEquals("should generate 2 aesthetic recrop proposals", 2, proposals.size)
        assertTrue(
            "proposals should be meaningfully distinct",
            proposals[0].crop.isMeaningfullyDifferentFrom(proposals[1].crop),
        )
        for (p in proposals) {
            assertTrue("${p.id} must not crop subject left", p.crop.left <= subject.left + 0.001f)
            assertTrue("${p.id} must not crop subject right", p.crop.right >= subject.right - 0.001f)
            assertTrue("${p.id} must not crop subject top", p.crop.top <= subject.top + 0.001f)
            assertTrue("${p.id} must not crop subject bottom", p.crop.bottom >= subject.bottom - 0.001f)
        }

        val tiltSummary = PostShotRecrop.reviewSummary(subject, 4.8, wasReady = false, reason = null, directorCue = null)
        assertTrue("tilt summary should mention tilt", "倾斜" in tiltSummary)

        val cueSummary = PostShotRecrop.reviewSummary(
            subject,
            0.8,
            wasReady = false,
            reason = null,
            directorCue = DirectorCue(DirectorCategory.LIGHTING, "侧身迎着窗光"),
        )
        assertTrue("cue summary should include director tip", "侧身迎着窗光" in cueSummary)
    }
}
