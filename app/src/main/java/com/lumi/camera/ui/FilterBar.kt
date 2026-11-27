package com.lumi.camera.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
    liveThumbnails: Map<FilterPreset, Bitmap> = emptyMap(),
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
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AiSmartFilterCard(
                selected = state.mode == FilterSelectionMode.AI_SMART,
                activePreset = state.resolvedPreset,
                previewBitmap = liveThumbnails[state.resolvedPreset] ?: liveThumbnails[FilterPreset.ORIGINAL],
                onClick = onSelectAiSmart,
            )
            FilterPreset.selectablePresets.forEach { preset ->
                FilterPreviewCard(
                    preset = preset,
                    selected = state.mode == FilterSelectionMode.MANUAL && state.selectedPreset == preset,
                    previewBitmap = liveThumbnails[preset],
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
private fun AiSmartFilterCard(
    selected: Boolean,
    activePreset: FilterPreset,
    previewBitmap: Bitmap?,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val borderColor = if (selected) LumiColors.Ivory else LumiColors.Hairline
    val fallbackBrush = Brush.linearGradient(
        listOf(Color(0xFF34D399), Color(0xFFF2E6C9), Color(0xFF7DD3FC)),
    )
    Box(
        modifier = Modifier
            .size(width = 54.dp, height = 64.dp)
            .clip(shape)
            .border(if (selected) 1.8.dp else 1.dp, borderColor, shape)
            .clickable(role = Role.RadioButton, onClick = onClick),
    ) {
        if (previewBitmap != null) {
            Image(
                bitmap = previewBitmap.asImageBitmap(),
                contentDescription = "AI 智能滤镜实时预览",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize().background(fallbackBrush))
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(4.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (selected) LumiColors.Ivory else Color(0xBF09090B))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        ) {
            Text(
                text = "AI",
                color = if (selected) LumiColors.IvoryInk else LumiColors.Ivory,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xD909090B)),
                    ),
                )
                .padding(vertical = 3.dp, horizontal = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (selected && activePreset != FilterPreset.ORIGINAL) activePreset.shortLabel else "智能",
                color = if (selected) LumiColors.Ivory else LumiColors.TextPrimary,
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun FilterPreviewCard(
    preset: FilterPreset,
    selected: Boolean,
    previewBitmap: Bitmap?,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val borderColor = if (selected) LumiColors.Ivory else LumiColors.Hairline
    val fallbackBrush = Brush.linearGradient(
        listOf(Color(preset.swatchPrimaryArgb), Color(preset.swatchSecondaryArgb)),
    )
    Box(
        modifier = Modifier
            .size(width = 54.dp, height = 64.dp)
            .clip(shape)
            .border(if (selected) 1.8.dp else 1.dp, borderColor, shape)
            .clickable(role = Role.RadioButton, onClick = onClick),
    ) {
        if (previewBitmap != null) {
            Image(
                bitmap = previewBitmap.asImageBitmap(),
                contentDescription = "${preset.displayName} 实时预览",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize().background(fallbackBrush))
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xD909090B)),
                    ),
                )
                .padding(vertical = 3.dp, horizontal = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = preset.shortLabel,
                color = if (selected) LumiColors.Ivory else LumiColors.TextPrimary,
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}
