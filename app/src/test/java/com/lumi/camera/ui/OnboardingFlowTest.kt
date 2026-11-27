package com.lumi.camera.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingFlowTest {

    @Test
    fun freshInstallStartsAtWelcomeAndPersistsAfterSkip() {
        val fresh = OnboardingFlow.initial(completedOrSkipped = false, firstShotCelebrated = false)
        assertEquals(OnboardingStep.WELCOME, fresh.step)
        assertTrue(fresh.isOverlayVisible)
        assertFalse(fresh.completedOrSkipped)

        val skipped = fresh.skip()
        assertEquals(OnboardingStep.NONE, skipped.step)
        assertFalse(skipped.isOverlayVisible)
        assertTrue(skipped.completedOrSkipped)
        assertFalse(skipped.awaitingFirstShot)

        // Photo after skipping must not trigger first-shot celebration.
        val afterShot = skipped.onPhotoCaptured()
        assertEquals(OnboardingStep.NONE, afterShot.step)

        // Relaunching with persisted flags stays silent.
        val relaunched = OnboardingFlow.initial(
            completedOrSkipped = skipped.completedOrSkipped,
            firstShotCelebrated = skipped.firstShotCelebrated,
        )
        assertEquals(OnboardingStep.NONE, relaunched.step)
        assertFalse(relaunched.isOverlayVisible)
    }

    @Test
    fun fullTourSupportsForwardBackwardAndFirstShotCelebrationOnce() {
        var flow = OnboardingFlow.initial(completedOrSkipped = false, firstShotCelebrated = false)
        flow = flow.startGuide()
        assertEquals(OnboardingStep.AI_GUIDANCE, flow.step)
        assertEquals(OnboardingTarget.AI_GUIDANCE, flow.step.target)
        assertEquals(1, flow.step.stepIndex)

        flow = flow.next()
        assertEquals(OnboardingStep.COMPOSITION_MODES, flow.step)
        assertEquals(OnboardingTarget.COMPOSITION_MODES, flow.step.target)

        flow = flow.previous()
        assertEquals(OnboardingStep.AI_GUIDANCE, flow.step)

        flow = flow.previous()
        assertEquals(OnboardingStep.WELCOME, flow.step)

        flow = flow.startGuide().next().next().next()
        assertEquals(OnboardingStep.SHUTTER_AND_AUTO, flow.step)
        assertEquals(OnboardingTarget.SHUTTER, flow.step.target)
        assertEquals(4, flow.step.stepIndex)

        // Completing the tour arms the one-time first-shot celebration.
        flow = flow.next()
        assertEquals(OnboardingStep.NONE, flow.step)
        assertTrue(flow.completedOrSkipped)
        assertTrue(flow.awaitingFirstShot)
        assertFalse(flow.firstShotCelebrated)

        // First captured photo opens FIRST_SHOT_COMPLETE once.
        flow = flow.onPhotoCaptured()
        assertEquals(OnboardingStep.FIRST_SHOT_COMPLETE, flow.step)
        assertTrue(flow.firstShotCelebrated)
        assertFalse(flow.awaitingFirstShot)

        flow = flow.dismissFirstShot()
        assertEquals(OnboardingStep.NONE, flow.step)

        // Second photo does not re-open FIRST_SHOT_COMPLETE.
        val secondShot = flow.onPhotoCaptured()
        assertEquals(OnboardingStep.NONE, secondShot.step)
    }

    @Test
    fun replayFromSettingsShowsWelcomeWithoutRetriggeringFirstShotCelebration() {
        val existing = OnboardingFlow.initial(completedOrSkipped = true, firstShotCelebrated = true)
        val replayed = existing.replay()
        assertEquals(OnboardingStep.WELCOME, replayed.step)
        assertTrue(replayed.isOverlayVisible)

        val finishedAgain = replayed.startGuide().next().next().next().next()
        assertEquals(OnboardingStep.NONE, finishedAgain.step)
        assertFalse(finishedAgain.awaitingFirstShot)
        assertEquals(OnboardingStep.NONE, finishedAgain.onPhotoCaptured().step)
    }
}
