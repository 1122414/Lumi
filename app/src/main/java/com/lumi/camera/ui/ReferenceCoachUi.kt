package com.lumi.camera.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lumi.camera.shotplan.ReferenceGuidance
import com.lumi.camera.shotplan.ReferenceRecipe
import kotlin.math.roundToInt

internal data class ReferenceUiState(
    val previewBitmap: Bitmap? = null,
    val overlayEnabled: Boolean = false,
    val overlayAlpha: Float = 0.25f,
    val analyzing: Boolean = false,
    val recipe: ReferenceRecipe? = null,
    val errorMessage: String? = null,
) {
    val hasReference: Boolean get() = previewBitmap != null
}

@Composable
internal fun ReferenceOverlayLayer(
    state: ReferenceUiState,
    modifier: Modifier = Modifier,
) {
    val bmp = state.previewBitmap ?: return
    if (!state.overlayEnabled) return
    val imageBitmap = remember(bmp) { bmp.asImageBitmap() }
    Image(
        bitmap = imageBitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxSize()
            .alpha(state.overlayAlpha.coerceIn(0.10f, 0.50f)),
    )
}

@Composable
internal fun ReferenceThumbnailDock(
    state: ReferenceUiState,
    onToggleOverlay: () -> Unit,
    onOpenSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bmp = state.previewBitmap ?: return
    val imageBitmap = remember(bmp) { bmp.asImageBitmap() }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            onClick = onToggleOverlay,
            shape = RoundedCornerShape(10.dp),
            color = Color(0xCC121714),
            border = BorderStroke(
                width = if (state.overlayEnabled) 1.6.dp else 1.dp,
                color = if (state.overlayEnabled) Color(0xFF38DF85) else Color(0x88FFFFFF),
            ),
            modifier = Modifier.size(width = 56.dp, height = 74.dp),
        ) {
            Box(Modifier.fillMaxSize()) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = "参考图缩略图，轻点切换半透明叠图",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color(0xB30E1310))
                        .padding(vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when {
                            state.analyzing -> "拆解中"
                            state.overlayEnabled -> "叠图开"
                            else -> "点叠图"
                        },
                        color = if (state.overlayEnabled) Color(0xFF38DF85) else Color.White,
                        fontSize = 9.sp,
                        maxLines = 1,
                    )
                }
            }
        }
        Surface(
            onClick = onOpenSheet,
            shape = RoundedCornerShape(10.dp),
            color = Color(0xCC141A16),
            border = BorderStroke(1.dp, if (state.recipe != null) Color(0x99FFD54F) else Color(0x55FFFFFF)),
        ) {
            Text(
                text = if (state.recipe != null) "处方 ›" else "参考 ›",
                color = if (state.recipe != null) Color(0xFFFFE082) else Color(0xFFE0E5E2),
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
internal fun ReferenceCoachBanner(
    guidance: ReferenceGuidance,
    modifier: Modifier = Modifier,
) {
    if (guidance.isEmpty) return
    Column(
        modifier = modifier.padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        guidance.poseCue?.let { pose ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xD9101C18),
                border = BorderStroke(1.dp, Color(0x994FC3F7)),
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF4FC3F7))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = "直接复述",
                            color = Color(0xFF0A1A22),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = "「$pose」",
                        color = Color.White,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        guidance.beyondTip?.let { beyond ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xD91D1910),
                border = BorderStroke(1.dp, Color(0x99FFD54F)),
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFFFD54F))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = "超越复刻",
                            color = Color(0xFF211A06),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = beyond,
                        color = Color(0xFFFFF8E1),
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ReferenceRecipeDialog(
    state: ReferenceUiState,
    modelConfigured: Boolean,
    onDismiss: () -> Unit,
    onToggleOverlay: () -> Unit,
    onAlphaChange: (Float) -> Unit,
    onRetryAnalyze: () -> Unit,
    onReplaceImage: () -> Unit,
    onClearReference: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, color = Color(0xFF171C19)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "参考图拍摄处方",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    Text(
                        text = if (state.recipe?.isPortrait == true) "同机位人像模式" else "构图光影参考",
                        fontSize = 11.sp,
                        color = Color(0xFFFFD54F),
                    )
                }

                state.previewBitmap?.let { bmp ->
                    val img = remember(bmp) { bmp.asImageBitmap() }
                    Image(
                        bitmap = img,
                        contentDescription = "参考图预览",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(170.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                }

                // Overlay toggle + opacity slider
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF202723),
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text("半透明叠图对照", color = Color.White, fontSize = 13.sp)
                                Text(
                                    text = "透明度 ${(state.overlayAlpha * 100f).roundToInt()}%（点取景器缩略图可秒开关）",
                                    color = Color(0xFF9EB0A5),
                                    fontSize = 11.sp,
                                )
                            }
                            Switch(
                                checked = state.overlayEnabled,
                                onCheckedChange = { onToggleOverlay() },
                            )
                        }
                        if (state.overlayEnabled) {
                            Slider(
                                value = state.overlayAlpha,
                                onValueChange = onAlphaChange,
                                valueRange = 0.15f..0.45f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                // Recipe breakdown
                when {
                    state.analyzing -> {
                        Text("正在拆解参考图构图、光影与动作要点…", color = Color(0xFFB3DBBE), fontSize = 12.sp)
                    }
                    state.recipe != null -> {
                        val r = state.recipe
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF1F2522),
                            border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                RecipeRow("景别题材", r.shotType)
                                RecipeRow("构图结构", r.composition)
                                RecipeRow("机位角度", r.cameraAngle)
                                RecipeRow("光影基调", r.lightingMood)
                                r.poseSummary?.let { RecipeRow("动作要点", it, highlight = true) }
                                r.targetExposure?.let { RecipeRow("参考曝光", it.summaryLabel()) }
                            }
                        }
                        Text(
                            text = "拍摄时点击「推荐构图」或轻点人物，AI 将同时给出可直接念给模特听的复刻口令，以及结合现场条件的「超越复刻」升级建议。",
                            color = Color(0xFF9EB0A5),
                            fontSize = 11.sp,
                        )
                    }
                    else -> {
                        val hint = state.errorMessage
                            ?: if (!modelConfigured) "未配置视觉模型，当前可直接使用半透明叠图对照拍摄。"
                            else "暂未生成拍摄处方，可点击重新拆解或直接叠图对照。"
                        Text(hint, color = Color(0xFFB0BEC5), fontSize = 12.sp)
                        if (modelConfigured) {
                            OutlinedButton(onClick = onRetryAnalyze) {
                                Text("拆解参考图处方", fontSize = 12.sp)
                            }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {
                        onClearReference()
                        onDismiss()
                    }) {
                        Text("清除参考图", color = Color(0xFFFF8A80))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = {
                            onDismiss()
                            onReplaceImage()
                        }) {
                            Text("换一张", fontSize = 12.sp)
                        }
                        TextButton(onClick = onDismiss) {
                            Text("完成")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipeRow(label: String, value: String, highlight: Boolean = false) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            color = if (highlight) Color(0xFF4FC3F7) else Color(0xFF9EB0A5),
            fontSize = 11.sp,
            modifier = Modifier.width(54.dp),
        )
        Text(
            text = value,
            color = if (highlight) Color(0xFFE1F5FE) else Color.White,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun ReferencePickerChip(
    hasReference: Boolean,
    overlayEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(
                when {
                    hasReference && overlayEnabled -> Color(0xFF38DF85)
                    hasReference -> Color(0x55FFD54F)
                    else -> Color(0x2EFFFFFF)
                }
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (hasReference) "参考·已载" else "参考图",
            color = if (hasReference && overlayEnabled) Color(0xFF102016) else Color.White,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}
