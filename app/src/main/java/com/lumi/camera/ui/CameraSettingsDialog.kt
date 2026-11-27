package com.lumi.camera.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lumi.camera.BuildConfig
import com.lumi.camera.camera.NativeProcessingMode
import com.lumi.camera.camera.NativeProcessingUiState

@Composable
internal fun CameraSettingsDialog(
    nativeProcessingState: NativeProcessingUiState,
    showGrid: Boolean,
    showModelConfigEntry: Boolean,
    modelSummary: String,
    modelStatusLabel: String,
    modelStatusColor: Color,
    preferenceSummary: String,
    telemetrySummary: String,
    canRememberComposition: Boolean,
    onSetNativeProcessing: (NativeProcessingMode) -> Unit,
    onToggleGrid: (Boolean) -> Unit,
    onOpenModelSettings: () -> Unit,
    onOpenPrivacyHealth: () -> Unit,
    onResetPreferences: () -> Unit,
    onRememberComposition: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = LumiColors.SurfaceDark,
            border = BorderStroke(1.dp, LumiColors.Hairline),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "相机设置",
                        color = LumiColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    TextButton(onClick = onDismiss) {
                        Text("完成", color = LumiColors.Ivory, fontSize = 13.sp)
                    }
                }

                // 1. Native AI Processing Toggle
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "本机 AI 成像优化",
                                color = LumiColors.TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = nativeProcessingState.statusSummary,
                                color = LumiColors.TextMuted,
                                fontSize = 11.sp,
                            )
                        }
                        Switch(
                            checked = nativeProcessingState.mode == NativeProcessingMode.NATIVE_AI,
                            onCheckedChange = { enabled ->
                                onSetNativeProcessing(
                                    if (enabled) NativeProcessingMode.NATIVE_AI else NativeProcessingMode.PURE,
                                )
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = LumiColors.IvoryInk,
                                checkedTrackColor = LumiColors.Ivory,
                                uncheckedThumbColor = LumiColors.TextSecondary,
                                uncheckedTrackColor = Color(0x28FFFFFF),
                            ),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProcessingModeBadge(
                            label = "本机优化",
                            selected = nativeProcessingState.mode == NativeProcessingMode.NATIVE_AI,
                            onClick = { onSetNativeProcessing(NativeProcessingMode.NATIVE_AI) },
                        )
                        ProcessingModeBadge(
                            label = "纯净原片（关本机AI）",
                            selected = nativeProcessingState.mode == NativeProcessingMode.PURE,
                            onClick = { onSetNativeProcessing(NativeProcessingMode.PURE) },
                        )
                    }
                }

                HorizontalDivider(color = LumiColors.Hairline)

                // 2. Viewfinder 3x3 grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("取景九宫格", color = LumiColors.TextPrimary, fontSize = 14.sp)
                        Text("极细 18% 辅助线，帮助判断水平与三分", color = LumiColors.TextMuted, fontSize = 11.sp)
                    }
                    Switch(
                        checked = showGrid,
                        onCheckedChange = onToggleGrid,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = LumiColors.IvoryInk,
                            checkedTrackColor = LumiColors.Ivory,
                            uncheckedThumbColor = LumiColors.TextSecondary,
                            uncheckedTrackColor = Color(0x28FFFFFF),
                        ),
                    )
                }

                HorizontalDivider(color = LumiColors.Hairline)

                // 3. Vision Model configuration (hidden in Release when managed BFF gateway is active)
                if (showModelConfigEntry) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x12FFFFFF))
                            .clickable {
                                onDismiss()
                                onOpenModelSettings()
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("云端视觉模型", color = LumiColors.TextPrimary, fontSize = 14.sp)
                            Text(
                                text = modelSummary,
                                color = LumiColors.TextMuted,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = "$modelStatusLabel ›",
                            color = modelStatusColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                // 4. Local aesthetic preference memory
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("本地构图偏好记忆", color = LumiColors.TextPrimary, fontSize = 13.sp)
                        Text(
                            text = preferenceSummary,
                            color = LumiColors.TextMuted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = onResetPreferences) {
                        Text("重置", color = LumiColors.Ivory, fontSize = 12.sp)
                    }
                }

                HorizontalDivider(color = LumiColors.Hairline)

                // 5. Privacy, Device Compatibility & Session Health
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x10FFFFFF))
                        .clickable {
                            onDismiss()
                            onOpenPrivacyHealth()
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("隐私说明与运行健康", color = LumiColors.TextPrimary, fontSize = 13.sp)
                        Text(
                            text = telemetrySummary,
                            color = LumiColors.TextMuted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = "v${BuildConfig.VERSION_NAME} ›",
                        color = LumiColors.Ivory,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                if (BuildConfig.DEBUG) {
                    HorizontalDivider(color = LumiColors.Hairline)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("调试工具（Debug）", color = LumiColors.TextMuted, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                onClick = {
                                    onDismiss()
                                    onRememberComposition()
                                },
                                enabled = canRememberComposition,
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0x18FFFFFF),
                                border = BorderStroke(1.dp, LumiColors.Hairline),
                            ) {
                                Text(
                                    text = "记住当前构图",
                                    color = if (canRememberComposition) LumiColors.TextPrimary else LumiColors.TextMuted,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                            Surface(
                                onClick = {
                                    onDismiss()
                                    onOpenDiagnostics()
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0x18FFFFFF),
                                border = BorderStroke(1.dp, LumiColors.Hairline),
                            ) {
                                Text(
                                    text = "查看上次诊断",
                                    color = LumiColors.TextPrimary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessingModeBadge(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) LumiColors.IvoryMuted else Color(0x12FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            color = if (selected) LumiColors.Ivory else LumiColors.TextMuted,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}
