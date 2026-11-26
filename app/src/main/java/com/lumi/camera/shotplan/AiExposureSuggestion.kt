package com.lumi.camera.shotplan

import com.lumi.camera.camera.ExposureSolver
import java.util.Locale
import kotlin.math.abs

internal data class AiExposureSuggestion(
    val evOffset: Float? = null,
    val iso: Int? = null,
    val shutterMs: Double? = null,
    val wbKelvin: Int? = null,
    val reason: String? = null,
) {
    val hasAdjustment: Boolean
        get() = (evOffset != null && abs(evOffset) >= 0.05f) || iso != null || shutterMs != null || wbKelvin != null

    fun summaryLabel(): String {
        val parts = mutableListOf<String>()
        evOffset?.takeIf { abs(it) >= 0.05f }?.let { parts += String.format(Locale.ROOT, "EV%+.1f", it) }
        iso?.let { parts += "ISO $it" }
        shutterMs?.let { ms -> parts += ExposureSolver.formatShutter((ms * 1_000_000.0).toLong()) }
        wbKelvin?.let { parts += "${it}K" }
        return parts.joinToString(" · ")
    }

    companion object {
        fun parse(raw: Any?): AiExposureSuggestion? {
            val map = raw as? Map<*, *> ?: return null
            val ev = (map["ev"] as? Number)?.toDouble()
                ?.takeIf { it.isFinite() }?.toFloat()?.coerceIn(-2.5f, 2.5f)
            val iso = (map["iso"] as? Number)?.toDouble()
                ?.takeIf { it.isFinite() && it >= 25.0 && it <= 51200.0 }?.toInt()
            val shutterMs = (map["shutter_ms"] as? Number)?.toDouble()
                ?.takeIf { it.isFinite() && it >= 0.05 && it <= 4000.0 }
            val wb = (map["wb"] as? Number)?.toDouble()
                ?.takeIf { it.isFinite() && it >= 2200.0 && it <= 9000.0 }?.toInt()
                ?.coerceIn(ExposureSolver.MIN_KELVIN, ExposureSolver.MAX_KELVIN)
            val reason = (map["reason"] as? String)?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= 40 && it.none { c -> c.isISOControl() } }
            val suggestion = AiExposureSuggestion(ev, iso, shutterMs, wb, reason)
            return suggestion.takeIf { it.hasAdjustment }
        }
    }
}
