package com.lumi.camera.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumi.camera.vision.PostShotReview
import kotlin.math.roundToInt

@Composable
internal fun OnboardingOverlay(
    flow: OnboardingFlow,
    anchorsInRoot: Map<OnboardingTarget, Rect>,
    firstShotReview: PostShotReview?,
    onStartGuide: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSkip: () -> Unit,
    onOpenFirstShotReview: () -> Unit,
    onDismissFirstShot: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!flow.isOverlayVisible) return
    val density = LocalDensity.current
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coords -> overlayOrigin = coords.positionInRoot() }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val maxHeightPx = with(density) { maxHeight.toPx() }
        val containerMaxHeight = maxHeight

        val rawTargetRect = flow.step.target?.let { anchorsInRoot[it] }
        val targetRect = rawTargetRect?.let { rect ->
            val padPx = with(density) {
                when (flow.step.target) {
                    OnboardingTarget.AI_GUIDANCE -> 8.dp.toPx()
                    OnboardingTarget.COMPOSITION_MODES -> 6.dp.toPx()
                    OnboardingTarget.REFERENCE_PHOTO -> 6.dp.toPx()
                    OnboardingTarget.SHUTTER -> 8.dp.toPx()
                    null -> 6.dp.toPx()
                }
            }
            Rect(
                left = (rect.left - overlayOrigin.x - padPx).coerceIn(4f, maxWidthPx - 4f),
                top = (rect.top - overlayOrigin.y - padPx).coerceIn(4f, maxHeightPx - 4f),
                right = (rect.right - overlayOrigin.x + padPx).coerceIn(8f, maxWidthPx - 4f),
                bottom = (rect.bottom - overlayOrigin.y + padPx).coerceIn(8f, maxHeightPx - 4f),
            )
        }

        // Spotlight scrim: leaves live viewfinder visible, dims non-target zones gently
        val scrimAlpha = when (flow.step) {
            OnboardingStep.WELCOME -> 0.44f
            OnboardingStep.FIRST_SHOT_COMPLETE -> 0.56f
            else -> 0.64f
        }
        val cutoutCornerPx = with(density) {
            when (flow.step.target) {
                OnboardingTarget.SHUTTER, OnboardingTarget.REFERENCE_PHOTO -> 28.dp.toPx()
                else -> 16.dp.toPx()
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            drawRect(color = LumiColors.Obsidian.copy(alpha = scrimAlpha))
            if (targetRect != null) {
                val topLeft = Offset(targetRect.left, targetRect.top)
                val rectSize = Size(targetRect.width, targetRect.height)
                val radius = CornerRadius(cutoutCornerPx, cutoutCornerPx)
                drawRoundRect(
                    color = Color.Transparent,
                    topLeft = topLeft,
                    size = rectSize,
                    cornerRadius = radius,
                    blendMode = BlendMode.Clear,
                )
                drawRoundRect(
                    color = LumiColors.Ivory.copy(alpha = 0.88f),
                    topLeft = topLeft,
                    size = rectSize,
                    cornerRadius = radius,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }

        when (flow.step) {
            OnboardingStep.WELCOME -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 28.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    WelcomeCard(
                        onStartGuide = onStartGuide,
                        onSkip = onSkip,
                    )
                }
            }

            OnboardingStep.FIRST_SHOT_COMPLETE -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    FirstShotCompleteCard(
                        review = firstShotReview,
                        onOpenReview = onOpenFirstShotReview,
                        onContinue = onDismissFirstShot,
                    )
                }
            }

            OnboardingStep.AI_GUIDANCE,
            OnboardingStep.COMPOSITION_MODES,
            OnboardingStep.REFERENCE_PHOTO,
            OnboardingStep.SHUTTER_AND_AUTO,
            -> {
                var cardHeightPx by remember(flow.step) { mutableIntStateOf(0) }
                val marginPx = with(density) { 14.dp.toPx() }
                val edgePadPx = with(density) { 16.dp.toPx() }
                val targetCenterY = targetRect?.center?.y ?: (maxHeightPx * 0.5f)
                val placeBelow = targetCenterY < maxHeightPx * 0.48f
                val desiredTopPx = if (targetRect == null) {
                    ((maxHeightPx - cardHeightPx) * 0.5f)
                } else if (placeBelow) {
                    targetRect.bottom + marginPx
                } else {
                    targetRect.top - marginPx - cardHeightPx
                }
                val clampedTopPx = desiredTopPx.coerceIn(
                    edgePadPx,
                    (maxHeightPx - cardHeightPx - edgePadPx).coerceAtLeast(edgePadPx),
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(0, clampedTopPx.roundToInt()) }
                        .padding(horizontal = 18.dp)
                        .onSizeChanged { cardHeightPx = it.height },
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = flow.step,
                        transitionSpec = {
                            fadeIn(tween(180)) togetherWith fadeOut(tween(140))
                        },
                        label = "onboarding step card",
                    ) { activeStep ->
                        TourStepCard(
                            step = activeStep,
                            maxCardHeightDp = (containerMaxHeight * 0.52f).coerceAtLeast(220.dp),
                            onPrevious = onPrevious,
                            onNext = onNext,
                            onSkip = onSkip,
                        )
                    }
                }
            }

            OnboardingStep.NONE -> Unit
        }
    }
}

@Composable
private fun WelcomeCard(
    onStartGuide: () -> Unit,
    onSkip: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = LumiColors.SurfaceDark.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, LumiColors.HairlineStrong),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(LumiColors.Emerald),
                )
                Text(
                    text = "LUMI · AI 摄影助手",
                    color = LumiColors.Ivory,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.1.sp,
                )
            }

            Text(
                text = "让每一张照片，都拍得更好。",
                color = LumiColors.TextPrimary,
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 28.sp,
            )

            Text(
                text = "Lumi 是你的 AI 摄影助手。实时分析取景画面，告诉你如何调整位置、角度和构图，让拍照变得更简单。",
                color = LumiColors.TextSecondary,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier.weight(0.85f),
                ) {
                    Text(
                        text = "稍后再说",
                        color = LumiColors.TextSecondary,
                        fontSize = 13.sp,
                    )
                }
                Button(
                    onClick = onStartGuide,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LumiColors.Ivory,
                        contentColor = LumiColors.IvoryInk,
                    ),
                    modifier = Modifier.weight(1.35f),
                ) {
                    Text(
                        text = "开始体验",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TourStepCard(
    step: OnboardingStep,
    maxCardHeightDp: androidx.compose.ui.unit.Dp,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    val stepIndex = step.stepIndex ?: 1
    val isLast = step == OnboardingStep.SHUTTER_AND_AUTO

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = LumiColors.SurfaceDark.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, LumiColors.Ivory.copy(alpha = 0.42f)),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxCardHeightDp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    repeat(OnboardingStep.TOTAL_TOUR_STEPS) { idx ->
                        val active = idx + 1 == stepIndex
                        Box(
                            modifier = Modifier
                                .width(if (active) 16.dp else 5.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (active) LumiColors.Ivory else LumiColors.HairlineStrong,
                                ),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "$stepIndex / ${OnboardingStep.TOTAL_TOUR_STEPS}",
                        color = LumiColors.TextMuted,
                        fontSize = 11.sp,
                    )
                }
                Text(
                    text = "跳过",
                    color = LumiColors.TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onSkip)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }

            when (step) {
                OnboardingStep.AI_GUIDANCE -> {
                    Text(
                        text = "实时 AI 拍摄指导",
                        color = LumiColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Lumi 会实时观察取景画面，分析主体和构图，并告诉你下一步怎么调整。点按右下角「AI 构图 / 离线构图」即可立即获取可执行引导。",
                        color = LumiColors.TextSecondary,
                        fontSize = 12.5.sp,
                        lineHeight = 19.sp,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(
                            "向左移动一点",
                            "镜头稍微抬高",
                            "让人物靠近三分线",
                            "调整拍摄距离",
                        ).forEach { example ->
                            OnboardingHintChip(example)
                        }
                    }
                }

                OnboardingStep.COMPOSITION_MODES -> {
                    Text(
                        text = "选择你喜欢的构图方式",
                        color = LumiColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "不知道怎么构图？试试不同的构图方式。新手直接保持默认的「智能」模式即可。",
                        color = LumiColors.TextSecondary,
                        fontSize = 12.5.sp,
                        lineHeight = 19.sp,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CompositionModeLine("智能", "由 AI 推荐合适的构图（默认推荐）", accent = true)
                        CompositionModeLine("三分", "使用经典三分法，可切换左 / 右三分")
                        CompositionModeLine("对称", "突出画面的居中与对称关系")
                        CompositionModeLine("紧凑", "减少多余空间，更聚焦主体")
                        CompositionModeLine("留白", "保留环境空间，让画面更有呼吸感")
                    }
                }

                OnboardingStep.REFERENCE_PHOTO -> {
                    Text(
                        text = "导入参考图，复刻构图",
                        color = LumiColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "看到喜欢的照片？点击右上角参考图入口导入它，Lumi 会在取景器中叠放半透明参考轮廓，协助你复刻主体位置与构图。",
                        color = LumiColors.TextSecondary,
                        fontSize = 12.5.sp,
                        lineHeight = 19.sp,
                    )
                }

                OnboardingStep.SHUTTER_AND_AUTO -> {
                    Text(
                        text = "快门与到位自动拍摄",
                        color = LumiColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "调整好画面后，按下快门记录这一刻。也可以点击右上角 AI 准星菜单开启「到位自动快门」，让 Lumi 在构图就绪时自动帮你按下快门。",
                        color = LumiColors.TextSecondary,
                        fontSize = 12.5.sp,
                        lineHeight = 19.sp,
                    )
                }

                else -> Unit
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onPrevious) {
                    Text(
                        text = "上一步",
                        color = LumiColors.TextSecondary,
                        fontSize = 13.sp,
                    )
                }
                Button(
                    onClick = onNext,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LumiColors.Ivory,
                        contentColor = LumiColors.IvoryInk,
                    ),
                ) {
                    Text(
                        text = if (isLast) "开始拍摄" else "下一步",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingHintChip(text: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0x1434D399),
        border = BorderStroke(1.dp, LumiColors.Emerald.copy(alpha = 0.35f)),
    ) {
        Text(
            text = text,
            color = LumiColors.TextPrimary,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun CompositionModeLine(
    name: String,
    desc: String,
    accent: Boolean = false,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            color = if (accent) LumiColors.Ivory else LumiColors.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(34.dp),
        )
        Text(
            text = desc,
            color = LumiColors.TextSecondary,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun FirstShotCompleteCard(
    review: PostShotReview?,
    onOpenReview: () -> Unit,
    onContinue: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = LumiColors.SurfaceDark.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, LumiColors.Emerald.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(LumiColors.Emerald),
                )
                Text(
                    text = "首次拍摄完成",
                    color = LumiColors.Emerald,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Text(
                text = "拍得不错！继续尝试，让每一张照片都更好。",
                color = LumiColors.TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 24.sp,
            )

            review?.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                Text(
                    text = summary,
                    color = LumiColors.TextSecondary,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                )
            }

            // Only show real computed quality dimensions when available from PostShotRecrop.
            review?.qualityScore?.let { score ->
                QualityScoreCard(score)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (review != null) {
                    TextButton(onClick = onOpenReview) {
                        Text(
                            text = "查看复盘与精裁",
                            color = LumiColors.Ivory,
                            fontSize = 13.sp,
                        )
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                Button(
                    onClick = onContinue,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LumiColors.Ivory,
                        contentColor = LumiColors.IvoryInk,
                    ),
                ) {
                    Text(
                        text = "继续拍摄",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
internal fun CameraPermissionGate(
    requested: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = LumiColors.SurfaceDark,
            border = BorderStroke(1.dp, LumiColors.HairlineStrong),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "LUMI",
                    color = LumiColors.Ivory,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.6.sp,
                )
                Text(
                    text = if (requested) "需要开启相机权限才能继续" else "允许 Lumi 使用相机",
                    color = LumiColors.TextPrimary,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (requested) {
                        "当前未获得相机权限，无法打开实时取景与 AI 构图指导。请前往系统设置中为 Lumi 开启相机权限。"
                    } else {
                        "Lumi 需要相机权限来呈现实时取景画面、提供构图与角度指导，并完成拍摄。所有预览分析均仅在你使用时运行。"
                    },
                    color = LumiColors.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onRequestPermission,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LumiColors.Ivory,
                        contentColor = LumiColors.IvoryInk,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("允许使用相机", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                if (requested) {
                    TextButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("前往系统设置", color = LumiColors.Ivory, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
