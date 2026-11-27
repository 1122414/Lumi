package com.lumi.camera.filter

import com.lumi.camera.camera.LiveTelemetry
import kotlin.math.max

internal enum class FilterSelectionMode(val wire: String, val label: String) {
    AI_SMART("ai_smart", "AI智能"),
    MANUAL("manual", "手动");

    companion object {
        fun fromWire(raw: String?): FilterSelectionMode =
            entries.firstOrNull { it.wire == raw } ?: MANUAL
    }
}

internal data class FilterDelta(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val saturation: Float = 0f,
    val glow: Float = 0f,
    val grain: Float = 0f,
) {
    // Clamp AI/sensor deltas strictly so no scene swing can break image aesthetics.
    fun clamped(): FilterDelta = FilterDelta(
        exposure = exposure.coerceIn(-0.18f, 0.18f),
        contrast = contrast.coerceIn(-0.16f, 0.16f),
        highlights = highlights.coerceIn(-0.25f, 0.15f),
        shadows = shadows.coerceIn(-0.15f, 0.28f),
        temperature = temperature.coerceIn(-0.16f, 0.16f),
        tint = tint.coerceIn(-0.10f, 0.10f),
        saturation = saturation.coerceIn(-0.18f, 0.15f),
        glow = glow.coerceIn(-0.12f, 0.12f),
        grain = grain.coerceIn(-0.12f, 0.08f),
    )

    operator fun plus(other: FilterDelta): FilterDelta = FilterDelta(
        exposure = exposure + other.exposure,
        contrast = contrast + other.contrast,
        highlights = highlights + other.highlights,
        shadows = shadows + other.shadows,
        temperature = temperature + other.temperature,
        tint = tint + other.tint,
        saturation = saturation + other.saturation,
        glow = glow + other.glow,
        grain = grain + other.grain,
    )

    companion object {
        val ZERO = FilterDelta()

        fun parse(raw: Any?): FilterDelta {
            val map = raw as? Map<*, *> ?: return ZERO
            fun num(vararg keys: String): Float {
                for (k in keys) {
                    val v = (map[k] as? Number)?.toFloat()
                    if (v != null && v.isFinite()) return v
                }
                return 0f
            }
            return FilterDelta(
                exposure = num("exposure", "exp"),
                contrast = num("contrast"),
                highlights = num("highlights", "highlight"),
                shadows = num("shadows", "shadow"),
                temperature = num("temperature", "temp"),
                tint = num("tint"),
                saturation = num("saturation", "sat"),
                glow = num("glow"),
                grain = num("grain"),
            ).clamped()
        }
    }
}

internal data class AiFilterSuggestion(
    val preset: FilterPreset,
    val delta: FilterDelta = FilterDelta.ZERO,
    val reason: String? = null,
) {
    fun summaryLabel(): String = buildString {
        append(preset.displayName)
        if (!reason.isNullOrBlank()) {
            append(" · ")
            append(reason)
        }
    }

    companion object {
        fun parse(raw: Any?): AiFilterSuggestion? {
            val map = raw as? Map<*, *> ?: return null
            val preset = FilterPreset.fromId(map["preset"] as? String)
                ?.takeIf { it != FilterPreset.ORIGINAL } ?: return null
            val delta = FilterDelta.parse(map["delta"])
            val reason = (map["reason"] as? String)?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= 28 && it.none(Char::isISOControl) }
            return AiFilterSuggestion(preset = preset, delta = delta, reason = reason)
        }
    }
}

internal data class FilterUiState(
    val mode: FilterSelectionMode = FilterSelectionMode.MANUAL,
    val selectedPreset: FilterPreset = FilterPreset.ORIGINAL,
    val intensity: Float = 0.85f,
    val aiAdaptiveEnabled: Boolean = true,
    val aiSuggestion: AiFilterSuggestion? = null,
    val resolvedPreset: FilterPreset = FilterPreset.ORIGINAL,
    val resolvedGrade: FilterGrade = FilterGrade.IDENTITY,
    val reason: String? = null,
) {
    val isActive: Boolean
        get() = !resolvedGrade.isIdentity

    val badgeLabel: String
        get() = when {
            mode == FilterSelectionMode.AI_SMART && resolvedPreset != FilterPreset.ORIGINAL ->
                "AI·${resolvedPreset.shortLabel}"
            mode == FilterSelectionMode.AI_SMART -> "AI"
            else -> resolvedPreset.shortLabel
        }
}

internal object FilterAdjuster {
    const val DEFAULT_INTENSITY = 0.85f

    // Zero-latency local weather/environment heuristic used by AI_SMART before a DS scene reply arrives.
    fun localPresetForTelemetry(telemetry: LiveTelemetry): FilterPreset {
        val lux = telemetry.ambientLux
        val luma = telemetry.meanLuma
        val diff = telemetry.subjectVsBgDiff
        return when {
            lux != null && lux < 35f -> FilterPreset.NIGHT_MOOD
            lux != null && lux >= 15000f -> FilterPreset.OXYGEN
            diff != null && diff <= -26 && (lux == null || lux >= 1500f) -> FilterPreset.GOLDEN
            lux != null && lux in 35f..320f -> FilterPreset.SOFT_PORTRAIT
            lux != null && lux in 320f..3500f -> FilterPreset.MIST
            luma != null && luma < 72 -> FilterPreset.NIGHT_MOOD
            luma != null && luma > 165 -> FilterPreset.OXYGEN
            else -> FilterPreset.CLASSIC_FILM
        }
    }

    // Continuous end-side micro-adjustments from real-time camera telemetry.
    fun telemetryDelta(preset: FilterPreset, telemetry: LiveTelemetry): FilterDelta {
        if (preset == FilterPreset.ORIGINAL) return FilterDelta.ZERO

        var exposure = 0f
        var contrast = 0f
        var highlights = 0f
        var shadows = 0f
        var temperature = 0f
        var glow = 0f
        var grain = 0f

        val clipHi = telemetry.highlightClipPct ?: 0
        if (clipHi >= 8) {
            val strength = ((clipHi - 6).coerceAtMost(24) / 24f)
            highlights -= 0.18f * strength
            if (preset == FilterPreset.RETRO_CCD || preset == FilterPreset.OXYGEN) {
                glow -= 0.08f * strength
            }
        }

        val diff = telemetry.subjectVsBgDiff
        if (diff != null && diff <= -20) {
            val backlit = ((-diff - 16).coerceAtMost(36) / 36f)
            shadows += 0.26f * backlit
            contrast -= 0.08f * backlit
            highlights -= 0.08f * backlit
        }

        val clipLo = telemetry.shadowClipPct ?: 0
        val luma = telemetry.meanLuma
        if (clipLo >= 22 || (luma != null && luma < 80)) {
            shadows += 0.12f
            if (preset == FilterPreset.MIST || preset == FilterPreset.COOL_FILM) {
                // Prevent overcast/dark skin from turning muddy blue-gray.
                temperature += 0.05f
            }
        }

        val iso = telemetry.iso ?: 0
        val lux = telemetry.ambientLux
        if (iso >= 1600 || (lux != null && lux < 40f)) {
            // Suppress synthetic film grain when sensor noise is already elevated.
            grain -= preset.baseGrade.grain * 0.75f
        }

        if (luma != null && luma > 185) {
            exposure -= 0.06f
        }

        return FilterDelta(
            exposure = exposure,
            contrast = contrast,
            highlights = highlights,
            shadows = shadows,
            temperature = temperature,
            glow = glow,
            grain = grain,
        ).clamped()
    }

    fun resolve(
        mode: FilterSelectionMode,
        selectedPreset: FilterPreset,
        intensity: Float,
        aiAdaptiveEnabled: Boolean,
        aiSuggestion: AiFilterSuggestion?,
        telemetry: LiveTelemetry,
    ): FilterUiState {
        val clampedIntensity = intensity.coerceIn(0f, 1f)
        val resolvedPreset = when (mode) {
            FilterSelectionMode.AI_SMART -> aiSuggestion?.preset ?: localPresetForTelemetry(telemetry)
            FilterSelectionMode.MANUAL -> selectedPreset
        }

        if (resolvedPreset == FilterPreset.ORIGINAL || clampedIntensity <= 0.005f) {
            return FilterUiState(
                mode = mode,
                selectedPreset = selectedPreset,
                intensity = clampedIntensity,
                aiAdaptiveEnabled = aiAdaptiveEnabled,
                aiSuggestion = aiSuggestion,
                resolvedPreset = resolvedPreset,
                resolvedGrade = FilterGrade.IDENTITY,
                reason = null,
            )
        }

        val base = resolvedPreset.baseGrade
        val aiDelta = if (aiAdaptiveEnabled && aiSuggestion != null) {
            when {
                mode == FilterSelectionMode.AI_SMART -> aiSuggestion.delta
                aiSuggestion.preset == resolvedPreset -> aiSuggestion.delta
                else -> aiSuggestion.delta.copy(
                    temperature = aiSuggestion.delta.temperature * 0.5f,
                    tint = aiSuggestion.delta.tint * 0.5f,
                )
            }
        } else {
            FilterDelta.ZERO
        }
        val liveDelta = if (aiAdaptiveEnabled) telemetryDelta(resolvedPreset, telemetry) else FilterDelta.ZERO
        val totalDelta = (aiDelta + liveDelta).clamped()

        val finalSaturation = if (resolvedPreset == FilterPreset.MONOCHROME) {
            -1.0f
        } else {
            base.saturation + totalDelta.saturation
        }

        val grade = base.copy(
            exposure = base.exposure + totalDelta.exposure,
            contrast = base.contrast + totalDelta.contrast,
            highlights = base.highlights + totalDelta.highlights,
            shadows = base.shadows + totalDelta.shadows,
            temperature = base.temperature + totalDelta.temperature,
            tint = base.tint + totalDelta.tint,
            saturation = finalSaturation,
            glow = max(0f, base.glow + totalDelta.glow),
            grain = max(0f, base.grain + totalDelta.grain),
            intensity = clampedIntensity,
            lutPresetId = resolvedPreset.id,
            lutStrength = 0.80f,
        ).clamped()

        val reason = when {
            mode == FilterSelectionMode.AI_SMART && aiSuggestion != null -> aiSuggestion.summaryLabel()
            mode == FilterSelectionMode.AI_SMART -> "${resolvedPreset.displayName} · 环境光自动匹配"
            aiAdaptiveEnabled && aiSuggestion?.reason != null && aiSuggestion.preset == resolvedPreset ->
                aiSuggestion.summaryLabel()
            else -> null
        }

        return FilterUiState(
            mode = mode,
            selectedPreset = selectedPreset,
            intensity = clampedIntensity,
            aiAdaptiveEnabled = aiAdaptiveEnabled,
            aiSuggestion = aiSuggestion,
            resolvedPreset = resolvedPreset,
            resolvedGrade = grade,
            reason = reason,
        )
    }
}
