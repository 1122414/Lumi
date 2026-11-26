package com.lumi.camera.camera

import android.hardware.camera2.params.RggbChannelVector
import com.lumi.camera.shotplan.AiExposureSuggestion
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal enum class ExposureControlMode(val wire: String, val label: String) {
    AUTO("auto", "自动"),
    AI_AUTO("ai_auto", "AI自动"),
    MANUAL("manual", "M手动");

    fun next(): ExposureControlMode = when (this) {
        AUTO -> AI_AUTO
        AI_AUTO -> MANUAL
        MANUAL -> AUTO
    }

    companion object {
        fun fromWire(raw: String?): ExposureControlMode =
            entries.firstOrNull { it.wire == raw } ?: AUTO
    }
}

internal data class CameraCapabilities(
    val isoRange: IntRange = 100..3200,
    val shutterRangeNs: LongRange = 100_000L..1_000_000_000L,
    val evIndexRange: IntRange = -12..12,
    val evStep: Float = 1f / 6f,
    val apertures: List<Float> = emptyList(),
    val supportsManualSensor: Boolean = false,
    val supportsManualWb: Boolean = true,
) {
    val hasVariableAperture: Boolean get() = apertures.size >= 2
    val primaryAperture: Float? get() = apertures.firstOrNull()
    val minEv: Float get() = evIndexRange.first * evStep
    val maxEv: Float get() = evIndexRange.last * evStep
    val uiMaxShutterNs: Long get() = minOf(shutterRangeNs.last, 4_000_000_000L)
}

internal data class ExposureBaseline(
    val iso: Int? = null,
    val shutterNs: Long? = null,
    val aperture: Float? = null,
    val evIndex: Int = 0,
)

internal data class LumaStats(
    val meanLuma: Int,
    val highlightClipPct: Int,
    val shadowClipPct: Int,
    val subjectVsBgDiff: Int? = null,
)

internal data class LiveTelemetry(
    val iso: Int? = null,
    val shutterNs: Long? = null,
    val aperture: Float? = null,
    val evSteps: Float = 0f,
    val ambientLux: Float? = null,
    val meanLuma: Int? = null,
    val highlightClipPct: Int? = null,
    val shadowClipPct: Int? = null,
    val subjectVsBgDiff: Int? = null,
) {
    fun hudSummary(): String {
        val parts = mutableListOf<String>()
        aperture?.let { parts += String.format(Locale.ROOT, "f/%.2f", it).trimEnd('0').trimEnd('.') }
        iso?.let { parts += "ISO $it" }
        shutterNs?.let { parts += ExposureSolver.formatShutter(it) }
        ambientLux?.let { lux ->
            parts += if (lux >= 1000f) String.format(Locale.ROOT, "%.1fk lx", lux / 1000f)
            else "${lux.roundToInt()} lx"
        }
        return parts.joinToString(" · ")
    }

    fun promptSummary(capabilities: CameraCapabilities): String? {
        val parts = mutableListOf<String>()
        ambientLux?.let { lux ->
            val env = when {
                lux >= 15000f -> "强日光"
                lux >= 2000f -> "户外明亮"
                lux >= 250f -> "室内明亮"
                lux >= 40f -> "室内弱光"
                else -> "暗光/夜景"
            }
            parts += "照度${lux.roundToInt()}lux($env)"
        }
        iso?.let { parts += "ISO$it" }
        shutterNs?.let { parts += "快门${ExposureSolver.formatShutter(it)}" }
        aperture?.let { parts += String.format(Locale.ROOT, "f/%.2f", it).trimEnd('0').trimEnd('.') }
        if (abs(evSteps) >= 0.05f) {
            parts += String.format(Locale.ROOT, "EV%+.1f", evSteps)
        }
        meanLuma?.let { parts += "均亮$it/255" }
        highlightClipPct?.takeIf { it >= 5 }?.let { parts += "高光溢出$it%" }
        shadowClipPct?.takeIf { it >= 10 }?.let { parts += "暗部占比$it%" }
        subjectVsBgDiff?.let { diff ->
            val lightingTag = when {
                diff <= -22 -> "偏逆光(主体比背景暗$diff)"
                diff >= 22 -> "偏顺光(主体比背景亮+$diff)"
                else -> "光比均匀($diff)"
            }
            parts += lightingTag
        }
        if (parts.isEmpty()) return null
        val hw = "硬件支持ISO[${capabilities.isoRange.first}-${capabilities.isoRange.last}]、EV[${String.format(Locale.ROOT, "%.1f", capabilities.minEv)}~+${String.format(Locale.ROOT, "%.1f", capabilities.maxEv)}]" +
            if (capabilities.hasVariableAperture) "、光圈${capabilities.apertures}" else "、固定光圈"
        return "现场实时测光：${parts.joinToString("，")}（$hw）。"
    }
}

internal data class ManualExposureSettings(
    val evSteps: Float = 0f,
    val iso: Int? = null,          // null = AUTO
    val shutterNs: Long? = null,   // null = AUTO
    val wbKelvin: Int? = null,     // null = AUTO
    val aperture: Float? = null,   // null = AUTO / fixed lens
) {
    val isAllAuto: Boolean
        get() = abs(evSteps) < 0.05f && iso == null && shutterNs == null && wbKelvin == null && aperture == null

    fun summaryLabel(): String {
        val parts = mutableListOf<String>()
        if (iso == null && shutterNs == null && abs(evSteps) >= 0.05f) {
            parts += String.format(Locale.ROOT, "EV%+.1f", evSteps)
        }
        iso?.let { parts += "ISO $it" }
        shutterNs?.let { parts += ExposureSolver.formatShutter(it) }
        wbKelvin?.let { parts += "${it}K" }
        aperture?.let { parts += String.format(Locale.ROOT, "f/%.1f", it) }
        return if (parts.isEmpty()) "全自动" else parts.joinToString(" · ")
    }
}

internal data class ResolvedCamera2Params(
    val aeAuto: Boolean,
    val evIndex: Int,
    val manualIso: Int?,
    val manualShutterNs: Long?,
    val wbKelvin: Int?,
    val aperture: Float?,
)

internal object ExposureSolver {
    const val MIN_KELVIN = 2800
    const val MAX_KELVIN = 7500

    // Per-item AUTO solver:
    // - Both ISO & S AUTO -> Hardware AE ON + EV compensation
    // - Only S locked (ISO AUTO) -> Shutter-priority: derive matching ISO from live AE baseline
    // - Only ISO locked (S AUTO) -> ISO-priority: derive matching Shutter from live AE baseline
    // - Both locked -> Full manual clamped strictly to hardware ranges
    fun resolve(
        settings: ManualExposureSettings,
        capabilities: CameraCapabilities,
        baseline: ExposureBaseline,
    ): ResolvedCamera2Params {
        val evStep = capabilities.evStep.takeIf { it > 0f } ?: (1f / 6f)
        val clampedEvIndex = (settings.evSteps / evStep).roundToInt().coerceIn(capabilities.evIndexRange)
        val wb = settings.wbKelvin?.coerceIn(MIN_KELVIN, MAX_KELVIN)?.takeIf { capabilities.supportsManualWb }
        val chosenAperture = if (capabilities.hasVariableAperture) {
            settings.aperture?.let { target -> capabilities.apertures.minByOrNull { abs(it - target) } }
        } else null

        if (!capabilities.supportsManualSensor || (settings.iso == null && settings.shutterNs == null)) {
            return ResolvedCamera2Params(
                aeAuto = true,
                evIndex = clampedEvIndex,
                manualIso = null,
                manualShutterNs = null,
                wbKelvin = wb,
                aperture = chosenAperture,
            )
        }

        val baseIso = (baseline.iso ?: 200).coerceIn(capabilities.isoRange)
        val baseShutter = (baseline.shutterNs ?: 16_666_667L).coerceIn(capabilities.shutterRangeNs)
        val evFactor = 2.0.pow(settings.evSteps.toDouble().coerceIn(-3.0, 3.0))
        val targetExposureProduct = baseIso.toDouble() * baseShutter.toDouble() * evFactor
        val safeMaxAutoShutter = minOf(capabilities.shutterRangeNs.last, 500_000_000L)

        return when {
            settings.iso != null && settings.shutterNs != null -> ResolvedCamera2Params(
                aeAuto = false,
                evIndex = 0,
                manualIso = settings.iso.coerceIn(capabilities.isoRange),
                manualShutterNs = settings.shutterNs.coerceIn(capabilities.shutterRangeNs),
                wbKelvin = wb,
                aperture = chosenAperture,
            )
            settings.shutterNs != null -> {
                val lockedShutter = settings.shutterNs.coerceIn(capabilities.shutterRangeNs)
                val derivedIso = (targetExposureProduct / lockedShutter.toDouble()).roundToInt()
                    .coerceIn(capabilities.isoRange)
                ResolvedCamera2Params(
                    aeAuto = false,
                    evIndex = 0,
                    manualIso = derivedIso,
                    manualShutterNs = lockedShutter,
                    wbKelvin = wb,
                    aperture = chosenAperture,
                )
            }
            else -> {
                val lockedIso = settings.iso!!.coerceIn(capabilities.isoRange)
                val derivedShutter = (targetExposureProduct / lockedIso.toDouble()).roundToLong()
                    .coerceIn(capabilities.shutterRangeNs.first, maxOf(capabilities.shutterRangeNs.first, safeMaxAutoShutter))
                ResolvedCamera2Params(
                    aeAuto = false,
                    evIndex = 0,
                    manualIso = lockedIso,
                    manualShutterNs = derivedShutter,
                    wbKelvin = wb,
                    aperture = chosenAperture,
                )
            }
        }
    }

    fun fromAiSuggestion(
        suggestion: AiExposureSuggestion,
        capabilities: CameraCapabilities,
    ): ManualExposureSettings {
        val ev = (suggestion.evOffset ?: 0f).coerceIn(capabilities.minEv, capabilities.maxEv)
        val iso = suggestion.iso?.takeIf { capabilities.supportsManualSensor }?.coerceIn(capabilities.isoRange)
        val shutterNs = suggestion.shutterMs?.takeIf { capabilities.supportsManualSensor }?.let { ms ->
            (ms * 1_000_000.0).roundToLong().coerceIn(capabilities.shutterRangeNs)
        }
        val wb = suggestion.wbKelvin?.takeIf { capabilities.supportsManualWb }?.coerceIn(MIN_KELVIN, MAX_KELVIN)
        return ManualExposureSettings(
            evSteps = ev,
            iso = iso,
            shutterNs = shutterNs,
            wbKelvin = wb,
        )
    }

    // Compute approximate white-balance RGB channel multipliers relative to neutral 5200K daylight.
    fun kelvinToRgbGains(kelvin: Int): Triple<Float, Float, Float> {
        val t = kelvin.coerceIn(MIN_KELVIN, MAX_KELVIN).toDouble() / 100.0
        fun rgbAt(temp100: Double): Triple<Double, Double, Double> {
            val r = if (temp100 <= 66.0) 255.0 else {
                (329.698727446 * (temp100 - 60.0).pow(-0.1332047592)).coerceIn(0.0, 255.0)
            }
            val g = if (temp100 <= 66.0) {
                (99.4708025861 * ln(temp100) - 161.1195681661).coerceIn(0.0, 255.0)
            } else {
                (288.1221695283 * (temp100 - 60.0).pow(-0.0755148492)).coerceIn(0.0, 255.0)
            }
            val b = when {
                temp100 >= 66.0 -> 255.0
                temp100 <= 19.0 -> 0.0
                else -> (138.5177312231 * ln(temp100 - 10.0) - 305.0447927307).coerceIn(0.0, 255.0)
            }
            return Triple(r, g, b)
        }
        // Higher target Kelvin makes the photo warmer (higher R gain, lower B gain relative to neutral daylight).
        val (r0, g0, b0) = rgbAt(52.0)
        val (rT, gT, bT) = rgbAt(t)
        // Invert source tint so setting 6500K compensates for cool ambient and yields warm tones, while 3200K cools warm tungsten.
        val rGain = ((r0 / g0) / (rT / gT)).toFloat().coerceIn(0.55f, 2.2f)
        val bGain = ((b0 / g0) / (bT / gT)).toFloat().coerceIn(0.55f, 2.2f)
        return Triple(rGain, 1.0f, bGain)
    }

    fun kelvinToRggbVector(kelvin: Int): RggbChannelVector {
        val (r, g, b) = kelvinToRgbGains(kelvin)
        return RggbChannelVector(r, g, g, b)
    }

    fun formatShutter(ns: Long): String {
        val sec = ns / 1_000_000_000.0
        return when {
            sec >= 0.4 -> String.format(Locale.ROOT, "%.1fs", sec).replace(".0s", "s")
            sec > 0.0 -> "1/${(1.0 / sec).roundToInt().coerceAtLeast(1)}s"
            else -> "AUTO"
        }
    }

    // Standard photography shutter stops from 1/4000s to 2s filtered to device capabilities.
    fun shutterStopsNs(range: LongRange): List<Long> = listOf(
        250_000L,       // 1/4000s
        500_000L,       // 1/2000s
        1_000_000L,     // 1/1000s
        2_000_000L,     // 1/500s
        4_000_000L,     // 1/250s
        8_000_000L,     // 1/125s
        16_666_667L,    // 1/60s
        33_333_333L,    // 1/30s
        66_666_667L,    // 1/15s
        125_000_000L,   // 1/8s
        250_000_000L,   // 1/4s
        500_000_000L,   // 1/2s
        1_000_000_000L, // 1s
        2_000_000_000L, // 2s
    ).filter { it in range }

    fun isoStops(range: IntRange): List<Int> = listOf(
        50, 100, 160, 200, 320, 400, 640, 800, 1250, 1600, 2500, 3200, 6400
    ).filter { it in range }
}
