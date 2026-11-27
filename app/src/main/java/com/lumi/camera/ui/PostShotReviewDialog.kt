package com.lumi.camera.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lumi.camera.shotplan.CompositionDiagnostic
import com.lumi.camera.shotplan.PhotoQualityScore
import com.lumi.camera.shotplan.SavedDiagnostic
import com.lumi.camera.vision.NormalizedCrop
import com.lumi.camera.vision.PostShotRecrop
import com.lumi.camera.vision.PostShotReview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DiagnosticDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val saved by produceState<SavedDiagnostic?>(null) {
        value = withContext(Dispatchers.IO) { CompositionDiagnostic.loadLatest(context) }
    }
    val bitmap by produceState<android.graphics.Bitmap?>(null, saved) {
        val file = saved?.imageFile?.takeIf { it.isFile }
        value = file?.let { withContext(Dispatchers.IO) { BitmapFactory.decodeFile(it.absolutePath) } }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(18.dp), color = LumiColors.SurfaceDark) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("上次推荐构图诊断", style = MaterialTheme.typography.titleMedium, color = LumiColors.TextPrimary)
                Text(
                    "黄框/点=模型主体  蓝线=本地边缘  红十字=原中心  绿框/环=建议成片",
                    fontSize = 11.sp,
                    color = LumiColors.TextMuted,
                )
                if (saved == null) {
                    Text("暂无推荐诊断记录，请先点击一次「AI 构图」。", fontSize = 13.sp, color = LumiColors.TextSecondary)
                } else {
                    bitmap?.let { bmp ->
                        Image(
                            bmp.asImageBitmap(),
                            contentDescription = "推荐构图叠加诊断图",
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat()),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    Text(saved?.report.orEmpty(), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = LumiColors.TextSecondary)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss) { Text("关闭", color = LumiColors.Ivory) }
                }
            }
        }
    }
}

@Composable
internal fun QualityScoreCard(score: PhotoQualityScore) {
    val gradeColor = when (score.grade) {
        "S" -> LumiColors.Emerald
        "A" -> LumiColors.Ivory
        "B" -> LumiColors.Amber
        else -> Color(0xFFFFAB91)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0x14FFFFFF),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("成片四维评估", color = LumiColors.TextPrimary, fontSize = 13.sp)
                Text(
                    text = "${score.overall} 分 · ${score.grade} 级（待提升：${score.weakestDimension}）",
                    color = gradeColor,
                    fontSize = 12.sp,
                )
            }
            val dims = listOf(
                "主体突出" to score.subjectClarity,
                "画面平衡" to score.frameBalance,
                "背景干净" to score.backgroundCleanliness,
                "自然度" to score.naturalness,
            )
            dims.forEach { (label, value) ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(label, color = LumiColors.TextMuted, fontSize = 11.sp, modifier = Modifier.width(52.dp))
                    Box(
                        Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0x24FFFFFF)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth((value / 100f).coerceIn(0.05f, 1f))
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (value >= 80) LumiColors.Emerald else LumiColors.Ivory),
                        )
                    }
                    Text("$value", color = LumiColors.TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(24.dp))
                }
            }
        }
    }
}

@Composable
internal fun PostShotReviewDialog(
    review: PostShotReview,
    preferenceSummary: String,
    onDismiss: () -> Unit,
    onSavedCopy: (String, String) -> Unit,
    onResetPreferences: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val previewBitmap by produceState<android.graphics.Bitmap?>(null, review.photoUri) {
        value = withContext(Dispatchers.IO) { PostShotRecrop.loadPreviewBitmap(context, review.photoUri, maxEdge = 1080) }
    }
    var selectedProposalId by remember(review.photoUri) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var prefText by remember(preferenceSummary) { mutableStateOf(preferenceSummary) }
    val activeProposal = review.proposals.firstOrNull { it.id == selectedProposalId }
    val activeCrop: NormalizedCrop? = activeProposal?.crop

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = LumiColors.SurfaceDark,
            border = BorderStroke(1.dp, LumiColors.Hairline),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("拍后轻复盘 · 二次构图", style = MaterialTheme.typography.titleMedium, color = LumiColors.TextPrimary)
                Text(review.summary, fontSize = 13.sp, color = LumiColors.TextSecondary)
                review.qualityScore?.let { QualityScoreCard(it) }
                val bmp = previewBitmap
                if (bmp != null) {
                    val displayBmp = remember(bmp, activeCrop) {
                        if (activeCrop == null) bmp else {
                            val x = (activeCrop.left * bmp.width).toInt().coerceIn(0, bmp.width - 1)
                            val y = (activeCrop.top * bmp.height).toInt().coerceIn(0, bmp.height - 1)
                            val w = (activeCrop.width * bmp.width).toInt().coerceIn(1, bmp.width - x)
                            val h = (activeCrop.height * bmp.height).toInt().coerceIn(1, bmp.height - y)
                            android.graphics.Bitmap.createBitmap(bmp, x, y, w, h)
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(displayBmp.width.toFloat() / displayBmp.height.toFloat())
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black),
                    ) {
                        Image(
                            bitmap = displayBmp.asImageBitmap(),
                            contentDescription = "二次构图预览",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(LumiColors.Obsidian),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("正在加载照片…", color = LumiColors.TextMuted, fontSize = 13.sp)
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedProposalId == null,
                        onClick = { selectedProposalId = null },
                        label = { Text("原图底片", fontSize = 12.sp) },
                    )
                    review.proposals.forEach { proposal ->
                        FilterChip(
                            selected = selectedProposalId == proposal.id,
                            onClick = { selectedProposalId = proposal.id },
                            label = { Text(proposal.title, fontSize = 12.sp) },
                        )
                    }
                }
                activeProposal?.let {
                    Text(it.subtitle, fontSize = 12.sp, color = LumiColors.Ivory)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = prefText,
                        color = LumiColors.TextMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            onResetPreferences()
                            prefText = "正在学习你的拍摄偏好（已记录 0 次）"
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) {
                        Text("重置偏好", fontSize = 11.sp, color = LumiColors.Ivory)
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("关闭", color = LumiColors.TextSecondary) }
                    if (activeProposal != null) {
                        Button(
                            enabled = !saving,
                            onClick = {
                                saving = true
                                scope.launch {
                                    val saved = withContext(Dispatchers.IO) {
                                        PostShotRecrop.saveCroppedCopy(
                                            context = context,
                                            sourceUri = review.photoUri,
                                            crop = activeProposal.crop,
                                            suffix = activeProposal.id,
                                        )
                                    }
                                    saving = false
                                    onSavedCopy(
                                        activeProposal.id,
                                        if (saved != null) "已保存「${activeProposal.title}」副本（原图已保留）"
                                        else "保存裁切副本失败，请检查存储权限。",
                                    )
                                    if (saved != null) onDismiss()
                                }
                            },
                        ) {
                            Text(if (saving) "保存中…" else "另存精裁副本")
                        }
                    }
                }
            }
        }
    }
}
