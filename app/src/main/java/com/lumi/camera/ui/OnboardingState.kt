package com.lumi.camera.ui

import android.content.Context

internal enum class OnboardingTarget {
    AI_GUIDANCE,
    COMPOSITION_MODES,
    REFERENCE_PHOTO,
    SHUTTER,
}

internal enum class OnboardingStep(
    val target: OnboardingTarget? = null,
    val stepIndex: Int? = null,
) {
    NONE,
    WELCOME,
    AI_GUIDANCE(target = OnboardingTarget.AI_GUIDANCE, stepIndex = 1),
    COMPOSITION_MODES(target = OnboardingTarget.COMPOSITION_MODES, stepIndex = 2),
    REFERENCE_PHOTO(target = OnboardingTarget.REFERENCE_PHOTO, stepIndex = 3),
    SHUTTER_AND_AUTO(target = OnboardingTarget.SHUTTER, stepIndex = 4),
    FIRST_SHOT_COMPLETE;

    companion object {
        const val TOTAL_TOUR_STEPS = 4
    }
}

/**
 * Pure, deterministic state machine for Lumi's interactive camera onboarding.
 * Persisted flags are mirrored to [OnboardingStore] by the host screen.
 */
internal data class OnboardingFlow(
    val step: OnboardingStep = OnboardingStep.NONE,
    val completedOrSkipped: Boolean = false,
    val awaitingFirstShot: Boolean = false,
    val firstShotCelebrated: Boolean = false,
) {
    val isOverlayVisible: Boolean
        get() = step != OnboardingStep.NONE

    val isTourStep: Boolean
        get() = step.stepIndex != null

    fun startGuide(): OnboardingFlow = copy(step = OnboardingStep.AI_GUIDANCE)

    fun next(): OnboardingFlow = when (step) {
        OnboardingStep.WELCOME -> copy(step = OnboardingStep.AI_GUIDANCE)
        OnboardingStep.AI_GUIDANCE -> copy(step = OnboardingStep.COMPOSITION_MODES)
        OnboardingStep.COMPOSITION_MODES -> copy(step = OnboardingStep.REFERENCE_PHOTO)
        OnboardingStep.REFERENCE_PHOTO -> copy(step = OnboardingStep.SHUTTER_AND_AUTO)
        OnboardingStep.SHUTTER_AND_AUTO -> copy(
            step = OnboardingStep.NONE,
            completedOrSkipped = true,
            awaitingFirstShot = !firstShotCelebrated,
        )
        OnboardingStep.FIRST_SHOT_COMPLETE -> dismissFirstShot()
        OnboardingStep.NONE -> this
    }

    fun previous(): OnboardingFlow = when (step) {
        OnboardingStep.AI_GUIDANCE -> copy(step = OnboardingStep.WELCOME)
        OnboardingStep.COMPOSITION_MODES -> copy(step = OnboardingStep.AI_GUIDANCE)
        OnboardingStep.REFERENCE_PHOTO -> copy(step = OnboardingStep.COMPOSITION_MODES)
        OnboardingStep.SHUTTER_AND_AUTO -> copy(step = OnboardingStep.REFERENCE_PHOTO)
        else -> this
    }

    fun skip(): OnboardingFlow = copy(
        step = OnboardingStep.NONE,
        completedOrSkipped = true,
        awaitingFirstShot = false,
    )

    fun onPhotoCaptured(): OnboardingFlow {
        if (!awaitingFirstShot || firstShotCelebrated || step != OnboardingStep.NONE) {
            return this
        }
        return copy(
            step = OnboardingStep.FIRST_SHOT_COMPLETE,
            awaitingFirstShot = false,
            firstShotCelebrated = true,
        )
    }

    fun dismissFirstShot(): OnboardingFlow = copy(
        step = OnboardingStep.NONE,
        awaitingFirstShot = false,
        firstShotCelebrated = true,
    )

    fun replay(): OnboardingFlow = copy(
        step = OnboardingStep.WELCOME,
        awaitingFirstShot = false,
    )

    companion object {
        fun initial(
            completedOrSkipped: Boolean,
            firstShotCelebrated: Boolean,
        ): OnboardingFlow = OnboardingFlow(
            step = if (completedOrSkipped) OnboardingStep.NONE else OnboardingStep.WELCOME,
            completedOrSkipped = completedOrSkipped,
            awaitingFirstShot = false,
            firstShotCelebrated = firstShotCelebrated,
        )
    }
}

internal object OnboardingStore {
    private const val PREFS = "lumi_onboarding_prefs"
    private const val KEY_COMPLETED_OR_SKIPPED = "completed_or_skipped"
    private const val KEY_FIRST_SHOT_CELEBRATED = "first_shot_celebrated"

    fun load(context: Context): OnboardingFlow {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return OnboardingFlow.initial(
            completedOrSkipped = prefs.getBoolean(KEY_COMPLETED_OR_SKIPPED, false),
            firstShotCelebrated = prefs.getBoolean(KEY_FIRST_SHOT_CELEBRATED, false),
        )
    }

    fun save(context: Context, flow: OnboardingFlow) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_COMPLETED_OR_SKIPPED, flow.completedOrSkipped)
            .putBoolean(KEY_FIRST_SHOT_CELEBRATED, flow.firstShotCelebrated)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
