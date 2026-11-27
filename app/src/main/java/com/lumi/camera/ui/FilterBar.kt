package com.lumi.camera.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumi.camera.filter.FilterPreset
import com.lumi.camera.filter.FilterSelectionMode
import com.lumi.camera.filter.FilterUiState
import kotlin.math.roundToInt

@Composable
internal fun FilterBar(
    state: FilterUiState,
    onSelectAiSmart: () -> Unit,
    onSelectPreset: (FilterPreset) -> Unit,
    onIntensityChange: (Float) -> Unit,
    onToggleAiAdaptive: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
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
            val titleText = state.reason ?: when {
                state.mode == FilterSelectionMode.AI_SMART -> "AI智能 · ${state.resolvedPreset.displayName}"
                else -> "${state.resolvedPreset.displayName} · ${state.resolvedPreset.sceneHint}"
            }
            Text(
                text = titleText,
                color = LumiColors.TextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.resolvedPreset != FilterPreset.ORIGINAL) {
                    Surface(
                        onClick = { onToggleAiAdaptive(!state.aiAdaptiveEnabled) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (state.aiAdaptiveEnabled) LumiColors.IvoryMuted else Color(0x18FFFFFF),
                        border = BorderStroke(
                            1.dp,
                            if (state.aiAdaptiveEnabled) LumiColors.Ivory else LumiColors.Hairline,
                        ),
                    ) {
                        Text(
                            text = if (state.aiAdaptiveEnabled) "AI微调 · 开" else "AI微调 · 关",
                            color = if (state.aiAdaptiveEnabled) LumiColors.Ivory else LumiColors.TextMuted,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    Text(
                        text = "${(state.intensity * 100f).roundToInt()}%",
                        color = LumiColors.Ivory,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AiSmartFilterChip(
                selected = state.mode == FilterSelectionMode.AI_SMART,
                activePreset = state.resolvedPreset,
                onClick = onSelectAiSmart,
            )
            FilterPreset.selectablePresets.forEach { preset ->
                FilterSwatchItem(
                    preset = preset,
                    selected = state.mode == FilterSelectionMode.MANUAL && state.selectedPreset == preset,
                    onClick = { onSelectPreset(preset) },
                )
            }
        }

        if (state.resolvedPreset != FilterPreset.ORIGINAL) {
            Slider(
                value = state.intensity,
                onValueChange = onIntensityChange,
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = LumiColors.Ivory,
                    activeTrackColor = LumiColors.Ivory,
                    inactiveTrackColor = Color(0x2EFFFFFF),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp),
            )
        }
    }
}

@Composable
private fun AiSmartFilterChip(
    selected: Boolean,
    activePreset: FilterPreset,
    onClick: () -> Unit,
) {
    val ringColor = if (selected) LumiColors.Ivory else LumiColors.Hairline
    val brush = Brush.linearGradient(
        listOf(Color(0xFF34D399), Color(0xFFF2E6C9), Color(0xFF7DD3FC)),
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.clickable(role = Role.RadioButton, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .border(if (selected) 1.8.dp else 1.dp, ringColor, CircleShape)
                .padding(3.dp)
                .clip(CircleShape)
                .background(brush),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "AI",
                color = LumiColors.IvoryInk,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = if (selected && activePreset != FilterPreset.ORIGINAL) activePreset.shortLabel else "AI智能",
            color = if (selected) LumiColors.Ivory else LumiColors.TextMuted,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun FilterSwatchItem(
    preset: FilterPreset,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val ringColor = if (selected) LumiColors.Ivory else LumiColors.Hairline
    val brush = Brush.linearGradient(
        listOf(Color(preset.swatchPrimaryArgb), Color(preset.swatchSecondaryArgb)),
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.clickable(role = Role.RadioButton, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .border(if (selected) 1.8.dp else 1.dp, ringColor, CircleShape)
                .padding(3.dp)
                .clip(CircleShape)
                .background(brush),
            contentAlignment = Alignment.Center,
        ) {
            if (preset == FilterPreset.ORIGINAL) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(LumiColors.Obsidian),
                )
            }
        }
        Text(
            text = preset.shortLabel,
            color = if (selected) LumiColors.Ivory else LumiColors.TextMuted,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}
