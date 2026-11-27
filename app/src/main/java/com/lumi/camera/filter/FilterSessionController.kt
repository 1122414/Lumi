package com.lumi.camera.filter

import com.lumi.camera.camera.LiveTelemetry

/**
 * Owns live 3D LUT filter selection, intensity, AI adaptive color tweaks, and preview-effect sync.
 */
internal class FilterSessionController(
    initialMode: FilterSelectionMode = FilterSelectionMode.MANUAL,
    initialPreset: FilterPreset = FilterPreset.ORIGINAL,
    initialIntensity: Float = FilterAdjuster.DEFAULT_INTENSITY,
    initialAiAdaptive: Boolean = true,
    private val onStateChanged: (FilterUiState) -> Unit,
) {
    var mode: FilterSelectionMode = initialMode
        private set
    var preset: FilterPreset = initialPreset
        private set
    var intensity: Float = initialIntensity.coerceIn(0f, 1f)
        private set
    var aiAdaptive: Boolean = initialAiAdaptive
        private set
    var aiSuggestion: AiFilterSuggestion? = null
        private set
    var previewRequested: Boolean = false
        private set

    var state: FilterUiState = FilterUiState(
        mode = mode,
        selectedPreset = preset,
        intensity = intensity,
        aiAdaptiveEnabled = aiAdaptive,
    )
        private set

    val activeGrade: FilterGrade
        get() = state.resolvedGrade

    val shouldRequestAiFilterAdvice: Boolean
        get() = mode == FilterSelectionMode.AI_SMART || (preset != FilterPreset.ORIGINAL && aiAdaptive)

    val manualPresetForPrompt: FilterPreset?
        get() = if (mode == FilterSelectionMode.MANUAL) preset else null

    fun refresh(telemetry: LiveTelemetry, previewEffect: PreviewFilterEffect?): FilterUiState {
        val next = FilterAdjuster.resolve(
            mode = mode,
            selectedPreset = preset,
            intensity = intensity,
            aiAdaptiveEnabled = aiAdaptive,
            aiSuggestion = aiSuggestion,
            telemetry = telemetry,
        )
        state = next
        previewEffect?.updateGrade(next.resolvedGrade)
        onStateChanged(next)
        return next
    }

    fun selectAiSmart(telemetry: LiveTelemetry, previewEffect: PreviewFilterEffect?) {
        mode = FilterSelectionMode.AI_SMART
        if (intensity <= 0.05f) intensity = FilterAdjuster.DEFAULT_INTENSITY
        refresh(telemetry, previewEffect)
    }

    fun selectPreset(nextPreset: FilterPreset, telemetry: LiveTelemetry, previewEffect: PreviewFilterEffect?) {
        mode = FilterSelectionMode.MANUAL
        preset = nextPreset
        if (nextPreset != FilterPreset.ORIGINAL && intensity <= 0.05f) {
            intensity = FilterAdjuster.DEFAULT_INTENSITY
        }
        refresh(telemetry, previewEffect)
    }

    fun setIntensity(value: Float, telemetry: LiveTelemetry, previewEffect: PreviewFilterEffect?) {
        intensity = value.coerceIn(0f, 1f)
        refresh(telemetry, previewEffect)
    }

    fun setAiAdaptive(enabled: Boolean, telemetry: LiveTelemetry, previewEffect: PreviewFilterEffect?) {
        if (aiAdaptive == enabled) return
        aiAdaptive = enabled
        refresh(telemetry, previewEffect)
    }

    fun applyAiSuggestion(
        suggestion: AiFilterSuggestion,
        telemetry: LiveTelemetry,
        previewEffect: PreviewFilterEffect?,
    ) {
        aiSuggestion = suggestion
        refresh(telemetry, previewEffect)
    }

    fun setPreviewRequested(enabled: Boolean) {
        previewRequested = enabled
    }
}
