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
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
    SHUTTER("快门"),
    WB("白平衡"),
    APERTURE("光圈"),
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
                append("AI曝光 ")
                append(summary)
                pending.reason?.let { append(" · ").append(it) }
            }
            Surface(
                onClick = onApplyPending,
                shape = RoundedCornerShape(18.dp),
                color = LumiColors.GlassDark,
                border = BorderStroke(1.dp, LumiColors.Ivory),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text, color = LumiColors.TextPrimary, fontSize = 11.sp, maxLines = 1)
                    Text("应用", color = LumiColors.Ivory, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
        state.mode == ExposureControlMode.AI_AUTO && applied != null -> {
            val summary = applied.summaryLabel()
            Surface(
                onClick = onRevertApplied,
                shape = RoundedCornerShape(18.dp),
                color = LumiColors.GlassDark,
                border = BorderStroke(1.dp, LumiColors.Emerald.copy(alpha = 0.7f)),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("AI已调: $summary", color = LumiColors.TextPrimary, fontSize = 11.sp, maxLines = 1)
                    Text("还原", color = LumiColors.Ivory, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun ManualExposurePanel(
    state: ExposureUiState,
    onSelectMode: (ExposureControlMode) -> Unit,
    onUpdateManual: (ManualExposureSettings) -> Unit,
    onApplyAiToManual: () -> Unit,
    onResetManual: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeTab by remember { mutableStateOf(ManualParamTab.EV) }
    val caps = state.capabilities
    val manual = state.manual

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(LumiColors.Obsidian)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x18FFFFFF))
                    .padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ExposureControlMode.entries.forEach { mode ->
                    val selected = state.mode == mode
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) LumiColors.Ivory else Color.Transparent)
                            .clickable(role = Role.RadioButton) { onSelectMode(mode) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = mode.label,
                            color = if (selected) LumiColors.IvoryInk else LumiColors.TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.latestAiSuggestion != null) {
                    SmallActionChip("抄AI参数", LumiColors.Emerald, onApplyAiToManual)
                }
                if (state.mode == ExposureControlMode.MANUAL && !manual.isAllAuto) {
                    SmallActionChip("全AUTO", LumiColors.Ivory, onResetManual)
                }
            }
        }

        if (state.mode != ExposureControlMode.MANUAL) {
            val hint = when (state.mode) {
                ExposureControlMode.AUTO -> "系统自动测光 · 轻点画面任意区域可锁定对焦与测光"
                ExposureControlMode.AI_AUTO -> "AI 自动挡 · 结合实时照度与顺逆光，在推荐构图时同步建议曝光参数"
                ExposureControlMode.MANUAL -> ""
            }
            Text(
                text = hint,
                color = LumiColors.TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            return
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
                title = "快门",
                value = manual.shutterNs?.let(ExposureSolver::formatShutter) ?: "AUTO",
                selected = activeTab == ManualParamTab.SHUTTER,
                locked = manual.shutterNs != null,
                enabled = caps.supportsManualSensor,
                onClick = { activeTab = ManualParamTab.SHUTTER },
            )
            ParamTabChip(
                title = "白平衡",
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
                title = "光圈",
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
                        colors = SliderDefaults.colors(
                            thumbColor = LumiColors.Ivory,
                            activeTrackColor = LumiColors.Ivory,
                            inactiveTrackColor = Color(0x2EFFFFFF),
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp),
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
                        colors = SliderDefaults.colors(
                            thumbColor = LumiColors.Ivory,
                            activeTrackColor = LumiColors.Ivory,
                            inactiveTrackColor = Color(0x2EFFFFFF),
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp),
                    )
                    Text(
                        text = when {
                            manual.wbKelvin == null -> "自动"
                            manual.wbKelvin <= 4200 -> "冷调"
                            manual.wbKelvin >= 5800 -> "暖调"
                            else -> "自然"
                        },
                        color = LumiColors.Ivory,
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
                        text = "当前镜头为物理固定光圈（$fixedText），如需更强虚化可切 2×/3× 长焦或靠近主体",
                        color = LumiColors.TextMuted,
                        fontSize = 11.sp,
                    )
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
        selected -> LumiColors.IvoryMuted
        locked -> Color(0x22F2E6C9)
        else -> Color(0x14FFFFFF)
    }
    val border = if (selected) LumiColors.Ivory else LumiColors.Hairline
    val valColor = when {
        !enabled -> Color(0x44FFFFFF)
        locked || selected -> LumiColors.Ivory
        else -> LumiColors.TextPrimary
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
    ) {
        Column(
            Modifier.padding(horizontal = 11.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, color = LumiColors.TextMuted, fontSize = 9.sp, maxLines = 1)
            Text(value, color = valColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        }
    }
}

@Composable
private fun AutoToggleChip(active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) LumiColors.Ivory else Color(0x1EFFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "AUTO",
            color = if (active) LumiColors.IvoryInk else LumiColors.TextPrimary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun StopChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) LumiColors.Ivory else Color(0x1AFFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) LumiColors.IvoryInk else LumiColors.TextPrimary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

@Composable
private fun SmallActionChip(label: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(label, color = tint, fontSize = 10.sp, maxLines = 1)
    }
}
