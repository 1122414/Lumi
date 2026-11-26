package com.lumi.camera.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumi.camera.camera.CameraCapabilities
import com.lumi.camera.camera.ExposureControlMode
import com.lumi.camera.camera.ExposureSolver
import com.lumi.camera.camera.LiveTelemetry
import com.lumi.camera.camera.ManualExposureSettings
import com.lumi.camera.shotplan.AiExposureSuggestion
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class ExposureUiState(
    val mode: ExposureControlMode = ExposureControlMode.AUTO,
    val capabilities: CameraCapabilities = CameraCapabilities(),
    val telemetry: LiveTelemetry = LiveTelemetry(),
    val manual: ManualExposureSettings = ManualExposureSettings(),
    val pendingAiSuggestion: AiExposureSuggestion? = null,
    val appliedAiSuggestion: AiExposureSuggestion? = null,
    val latestAiSuggestion: AiExposureSuggestion? = null,
)

private enum class ManualParamTab(val label: String) {
    EV("EV"),
    ISO("ISO"),
    SHUTTER("S"),
    WB("WB"),
    APERTURE("F"),
}

@Composable
internal fun AiExposureActionPill(
    state: ExposureUiState,
    onApplyPending: () -> Unit,
    onRevertApplied: () -> Unit,
) {
    val pending = state.pendingAiSuggestion
    val applied = state.appliedAiSuggestion
    when {
        state.mode == ExposureControlMode.AI_AUTO && pending != null -> {
            val summary = pending.summaryLabel()
            val text = buildString {
                append("AI建议: ")
                append(summary)
                pending.reason?.let { append(" · ").append(it) }
            }
            Surface(
                onClick = onApplyPending,
                shape = RoundedCornerShape(16.dp),
                color = Color(0xCC112018),
                border = BorderStroke(1.dp, Color(0xFF38DF85)),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text, color = Color.White, fontSize = 11.sp, maxLines = 1)
                    Text("应用", color = Color(0xFF38DF85), fontSize = 11.sp, maxLines = 1)
                }
            }
        }
        state.mode == ExposureControlMode.AI_AUTO && applied != null -> {
            val summary = applied.summaryLabel()
            Surface(
                onClick = onRevertApplied,
                shape = RoundedCornerShape(16.dp),
                color = Color(0xCC101C22),
                border = BorderStroke(1.dp, Color(0xFF4FC3F7)),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("AI已调: $summary", color = Color(0xFFE0F7FA), fontSize = 11.sp, maxLines = 1)
                    Text("还原", color = Color(0xFFFFD54F), fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun ManualExposurePanel(
    state: ExposureUiState,
    onUpdateManual: (ManualExposureSettings) -> Unit,
    onApplyAiToManual: () -> Unit,
    onResetManual: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeTab by remember { mutableStateOf(ManualParamTab.EV) }
    val caps = state.capabilities
    val manual = state.manual
    val hud = state.telemetry.hudSummary()

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xD8111513),
        border = BorderStroke(1.dp, Color(0x33FFFFFF)),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (hud.isNotEmpty()) "实测 $hud" else "M手动 · 单项支持 AUTO 优先",
                    color = Color(0xFFA8BDB2),
                    fontSize = 10.sp,
                    maxLines = 1,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.latestAiSuggestion != null) {
                        SmallActionChip("抄AI参数", Color(0xFF38DF85), onApplyAiToManual)
                    }
                    if (!manual.isAllAuto) {
                        SmallActionChip("全AUTO", Color(0xFFFFD54F), onResetManual)
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ParamTabChip(
                    title = "EV",
                    value = if (abs(manual.evSteps) < 0.05f) "0.0" else String.format(Locale.ROOT, "%+.1f", manual.evSteps),
                    selected = activeTab == ManualParamTab.EV,
                    locked = abs(manual.evSteps) >= 0.05f,
                    onClick = { activeTab = ManualParamTab.EV },
                )
                ParamTabChip(
                    title = "ISO",
                    value = manual.iso?.toString() ?: "AUTO",
                    selected = activeTab == ManualParamTab.ISO,
                    locked = manual.iso != null,
                    enabled = caps.supportsManualSensor,
                    onClick = { activeTab = ManualParamTab.ISO },
                )
                ParamTabChip(
                    title = "S",
                    value = manual.shutterNs?.let(ExposureSolver::formatShutter) ?: "AUTO",
                    selected = activeTab == ManualParamTab.SHUTTER,
                    locked = manual.shutterNs != null,
                    enabled = caps.supportsManualSensor,
                    onClick = { activeTab = ManualParamTab.SHUTTER },
                )
                ParamTabChip(
                    title = "WB",
                    value = manual.wbKelvin?.let { "${it}K" } ?: "AUTO",
                    selected = activeTab == ManualParamTab.WB,
                    locked = manual.wbKelvin != null,
                    enabled = caps.supportsManualWb,
                    onClick = { activeTab = ManualParamTab.WB },
                )
                val apertureText = when {
                    manual.aperture != null -> String.format(Locale.ROOT, "f/%.2f", manual.aperture).trimEnd('0').trimEnd('.')
                    caps.primaryAperture != null -> String.format(Locale.ROOT, "f/%.2f", caps.primaryAperture).trimEnd('0').trimEnd('.')
                    else -> "固定"
                }
                ParamTabChip(
                    title = "F",
                    value = apertureText,
                    selected = activeTab == ManualParamTab.APERTURE,
                    locked = manual.aperture != null,
                    onClick = { activeTab = ManualParamTab.APERTURE },
                )
            }

            when (activeTab) {
                ManualParamTab.EV -> {
                    val minEv = caps.minEv.coerceAtLeast(-2.5f)
                    val maxEv = caps.maxEv.coerceAtMost(2.5f)
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AutoToggleChip(active = abs(manual.evSteps) < 0.05f) {
                            onUpdateManual(manual.copy(evSteps = 0f))
                        }
                        Slider(
                            value = manual.evSteps.coerceIn(minEv, maxEv),
                            onValueChange = { raw ->
                                val snapped = (raw * 10f).roundToInt() / 10f
                                onUpdateManual(manual.copy(evSteps = snapped))
                            },
                            valueRange = minEv..maxEv,
                            colors = SliderDefaults.colors(thumbColor = Color(0xFF38DF85), activeTrackColor = Color(0xFF38DF85)),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                ManualParamTab.ISO -> {
                    val stops = remember(caps.isoRange) { ExposureSolver.isoStops(caps.isoRange) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AutoToggleChip(active = manual.iso == null) {
                            onUpdateManual(manual.copy(iso = null))
                        }
                        stops.forEach { stop ->
                            StopChip(
                                label = stop.toString(),
                                selected = manual.iso == stop,
                                onClick = { onUpdateManual(manual.copy(iso = stop)) },
                            )
                        }
                    }
                }
                ManualParamTab.SHUTTER -> {
                    val stops = remember(caps.shutterRangeNs) { ExposureSolver.shutterStopsNs(caps.shutterRangeNs) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AutoToggleChip(active = manual.shutterNs == null) {
                            onUpdateManual(manual.copy(shutterNs = null))
                        }
                        stops.forEach { ns ->
                            StopChip(
                                label = ExposureSolver.formatShutter(ns),
                                selected = manual.shutterNs == ns,
                                onClick = { onUpdateManual(manual.copy(shutterNs = ns)) },
                            )
                        }
                    }
                }
                ManualParamTab.WB -> {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AutoToggleChip(active = manual.wbKelvin == null) {
                            onUpdateManual(manual.copy(wbKelvin = null))
                        }
                        Slider(
                            value = (manual.wbKelvin ?: 5200).toFloat(),
                            onValueChange = { raw ->
                                val snapped = ((raw / 100f).roundToInt() * 100)
                                    .coerceIn(ExposureSolver.MIN_KELVIN, ExposureSolver.MAX_KELVIN)
                                onUpdateManual(manual.copy(wbKelvin = snapped))
                            },
                            valueRange = ExposureSolver.MIN_KELVIN.toFloat()..ExposureSolver.MAX_KELVIN.toFloat(),
                            colors = SliderDefaults.colors(thumbColor = Color(0xFFFFD54F), activeTrackColor = Color(0xFFFFD54F)),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = when {
                                manual.wbKelvin == null -> "自动"
                                manual.wbKelvin <= 4200 -> "冷调"
                                manual.wbKelvin >= 5800 -> "暖调"
                                else -> "自然"
                            },
                            color = Color(0xFFFFD54F),
                            fontSize = 11.sp,
                        )
                    }
                }
                ManualParamTab.APERTURE -> {
                    if (caps.hasVariableAperture) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AutoToggleChip(active = manual.aperture == null) {
                                onUpdateManual(manual.copy(aperture = null))
                            }
                            caps.apertures.forEach { f ->
                                val label = String.format(Locale.ROOT, "f/%.2f", f).trimEnd('0').trimEnd('.')
                                StopChip(
                                    label = label,
                                    selected = manual.aperture != null && abs(manual.aperture - f) < 0.05f,
                                    onClick = { onUpdateManual(manual.copy(aperture = f)) },
                                )
                            }
                        }
                    } else {
                        val fixedText = caps.primaryAperture?.let {
                            String.format(Locale.ROOT, "f/%.2f", it).trimEnd('0').trimEnd('.')
                        } ?: "固定光圈"
                        Text(
                            text = "当前镜头为物理固定光圈（$fixedText），如需更强背景虚化可切 2×/4× 长焦或靠近主体",
                            color = Color(0xFFB0BEC5),
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ParamTabChip(
    title: String,
    value: String,
    selected: Boolean,
    locked: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = when {
        selected -> Color(0x4438DF85)
        locked -> Color(0x2838DF85)
        else -> Color(0x1EFFFFFF)
    }
    val border = if (selected) Color(0xFF38DF85) else Color.Transparent
    val valColor = when {
        !enabled -> Color(0x66FFFFFF)
        locked -> Color(0xFF38DF85)
        else -> Color.White
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, color = Color(0xFF9EB0A6), fontSize = 9.sp, maxLines = 1)
            Text(value, color = valColor, fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
private fun AutoToggleChip(active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) Color(0xFFB3DBBE) else Color(0x28FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "AUTO",
            color = if (active) Color(0xFF102016) else Color.White,
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun StopChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFF38DF85) else Color(0x22FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Color(0xFF102016) else Color.White,
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun SmallActionChip(label: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.18f))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(label, color = tint, fontSize = 10.sp, maxLines = 1)
    }
}
