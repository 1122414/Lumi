package com.lumi.camera.ui

import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumi.camera.camera.CandidateOverlay
import com.lumi.camera.camera.CloseupOverlay
import com.lumi.camera.camera.ExposureSolver
import com.lumi.camera.camera.LiveTelemetry
import com.lumi.camera.camera.Point
import com.lumi.camera.camera.Viewfinder
import com.lumi.camera.guidance.GuideEvidence
import com.lumi.camera.guidance.PoseGuidance
import com.lumi.camera.guidance.StepProgress
import com.lumi.camera.shotplan.CompositionChoice
import com.lumi.camera.shotplan.CompositionPreset
import com.lumi.camera.shotplan.PresetSelection
import com.lumi.camera.shotplan.ProactivityMode
import com.lumi.camera.vision.PostShotRecrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal data class GuidanceBanner(
    val text: String,
    val subtitle: String? = null,
    val ready: Boolean = false,
    val subtle: Boolean = false,
)

internal fun resolveGuidanceBanner(
    frame: Viewfinder,
    bound: Boolean,
    front: Boolean,
    recommendationStatus: String?,
    autoShoot: Boolean,
    autoShotFired: Boolean,
    proactivityMode: ProactivityMode,
): GuidanceBanner? {
    val rollWarn = frame.rollDeg?.let { abs(it) > PoseGuidance.READY_MAX_ROLL_DEG } == true
    val pendingStep = frame.stepProgress?.takeIf { !it.satisfied }
    val secondaryTip = when {
        frame.directorCue != null -> "${frame.directorCue.category.badge} · ${frame.directorCue.tip}"
        frame.lightGuidance?.isActionable == true -> frame.lightGuidance.tip
        else -> null
    }
    return when {
        !bound -> GuidanceBanner(if (front) "正在打开前置相机" else "正在打开后置相机", subtle = true)
        recommendationStatus != null && frame.hasTarget && frame.isOfflineBaseline -> GuidanceBanner(
            text = frame.reason ?: "端侧构图已就绪",
            subtitle = recommendationStatus,
            ready = frame.ready,
        )
        recommendationStatus != null -> GuidanceBanner(recommendationStatus)
        frame.ready && autoShoot && !autoShotFired -> GuidanceBanner("构图就绪 · 自动拍摄中", ready = true)
        frame.ready && pendingStep != null -> GuidanceBanner("构图已对齐 · ${pendingStep.direction.label}更干净", ready = true)
        frame.ready -> GuidanceBanner(
            text = frame.reason?.let { "构图就绪 · $it" } ?: "构图就绪 · 可以拍",
            subtitle = secondaryTip,
            ready = true,
        )
        frame.viewpointDrifted -> GuidanceBanner(
            text = "视角已偏离，请回到推荐仰俯角",
            subtitle = frame.reason,
        )
        frame.evidence == GuideEvidence.RETURN_DIRECTION -> GuidanceBanner(
            text = frame.reason?.let { "返回推荐方向 · $it" } ?: "返回推荐构图方向",
        )
        frame.hasTarget && frame.ring == null -> GuidanceBanner("正在找回主体")
        frame.hasTarget && rollWarn -> GuidanceBanner(
            text = frame.reason?.let { "$it · 调平画面" } ?: "轻轻调平画面",
            subtitle = secondaryTip,
        )
        frame.reason != null -> GuidanceBanner(
            text = frame.reason,
            subtitle = secondaryTip,
        )
        frame.directorCue != null -> GuidanceBanner("${frame.directorCue.category.badge} · ${frame.directorCue.tip}")
        proactivityMode == ProactivityMode.QUIET -> null
        else -> null
    }
}

@Composable
internal fun ViewfinderGuidancePill(banner: GuidanceBanner) {
    val borderColor = when {
        banner.ready -> LumiColors.Emerald.copy(alpha = 0.72f)
        banner.subtle -> LumiColors.Hairline
        else -> LumiColors.HairlineStrong
    }
    val dotColor = if (banner.ready) LumiColors.Emerald else LumiColors.Ivory
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = LumiColors.GlassDark,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (banner.ready) {
                Text("✓", color = LumiColors.Emerald, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            } else {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .border(1.2.dp, dotColor, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(3.5.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = banner.text,
                    color = LumiColors.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                banner.subtitle?.let { sub ->
                    Text(
                        text = sub,
                        color = LumiColors.Ivory.copy(alpha = 0.88f),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun TopIconButton(
    description: String,
    active: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (active) Color(0x1AF2E6C9) else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
internal fun ViewfinderCornerCircle(
    active: Boolean,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = LumiColors.GlassDark,
        border = BorderStroke(1.dp, if (active) LumiColors.Ivory else LumiColors.Hairline),
        modifier = modifier
            .size(46.dp)
            .semantics { contentDescription = description },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

@Composable
internal fun MinimalPresetTabRow(
    selection: PresetSelection,
    onSelect: (CompositionPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionPreset.entries.forEach { preset ->
            val selected = selection.preset == preset
            val text = when {
                preset == CompositionPreset.RULE_OF_THIRDS && selected ->
                    if (selection.thirdChoice == CompositionChoice.LEFT_THIRD) "三分·左" else "三分·右"
                else -> preset.label
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.RadioButton) { onSelect(preset) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = text,
                    color = if (selected) LumiColors.TextPrimary else LumiColors.TextMuted,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                )
                Box(
                    Modifier
                        .width(16.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (selected) LumiColors.Ivory else Color.Transparent),
                )
            }
        }
    }
}

@Composable
internal fun TelemetryStrip(telemetry: LiveTelemetry) {
    val parts = buildList {
        telemetry.aperture?.let {
            add(String.format(Locale.ROOT, "f/%.2f", it).trimEnd('0').trimEnd('.'))
        }
        telemetry.shutterNs?.let { add(ExposureSolver.formatShutter(it)) }
        telemetry.iso?.let { add("ISO $it") }
    }
    val text = if (parts.isNotEmpty()) parts.joinToString("  |  ") else "AUTO  |  测光就绪"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 42.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(LumiColors.Hairline),
        )
        Text(
            text = text,
            color = LumiColors.TextSecondary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(LumiColors.Hairline),
        )
    }
}

@Composable
internal fun TactileShutterButton(
    enabled: Boolean,
    ready: Boolean,
    autoShootProgress: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val innerScale by animateFloatAsState(
        targetValue = if (pressed) 0.89f else 1f,
        animationSpec = tween(90),
        label = "shutter press scale",
    )
    val outerColor = when {
        !enabled -> LumiColors.Hairline
        ready -> LumiColors.Emerald
        else -> LumiColors.TextPrimary
    }
    Box(
        modifier = modifier
            .size(74.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = "拍照快门" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val ringStroke = 2.4.dp.toPx()
            val radius = size.minDimension / 2f - ringStroke
            drawCircle(
                color = outerColor,
                radius = radius,
                center = center,
                style = Stroke(ringStroke),
            )
            if (autoShootProgress > 0f) {
                drawArc(
                    color = LumiColors.Ivory,
                    startAngle = -90f,
                    sweepAngle = 360f * autoShootProgress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = Offset(ringStroke, ringStroke),
                    size = Size(size.width - ringStroke * 2f, size.height - ringStroke * 2f),
                    style = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            drawCircle(
                color = if (enabled) Color.White else Color(0x66FFFFFF),
                radius = (radius - 5.dp.toPx()) * innerScale,
                center = center,
            )
        }
    }
}

@Composable
internal fun LastPhotoThumbSlot(
    photoUri: Uri?,
    cacheKey: Long,
    processingCount: Int,
    onClick: () -> Unit,
) {
    val infinite = rememberInfiniteTransition(label = "capture queue ring")
    val spinDeg by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "capture spin",
    )
    val context = LocalContext.current
    val thumb by produceState<android.graphics.Bitmap?>(null, photoUri, cacheKey) {
        value = if (photoUri == null) null else withContext(Dispatchers.IO) {
            PostShotRecrop.loadPreviewBitmap(context, photoUri, maxEdge = 160)
        }
    }

    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        if (photoUri == null) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x16FFFFFF))
                    .border(1.dp, LumiColors.Hairline, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                ReferenceFrameIcon(tint = LumiColors.TextMuted, size = 18.dp)
            }
        } else {
            Surface(
                onClick = onClick,
                shape = RoundedCornerShape(12.dp),
                color = Color(0x22FFFFFF),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (processingCount > 0) LumiColors.Ivory else LumiColors.HairlineStrong,
                ),
                modifier = Modifier
                    .size(46.dp)
                    .semantics { contentDescription = "打开 Lumi 相册与精裁复盘" },
            ) {
                val bmp = thumb
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "最新照片缩略图",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ReferenceFrameIcon(tint = LumiColors.Ivory, size = 18.dp)
                    }
                }
            }
        }

        if (processingCount > 0) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 2.dp.toPx()
                drawArc(
                    color = LumiColors.Ivory,
                    startAngle = spinDeg,
                    sweepAngle = 110f,
                    useCenter = false,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
internal fun StepProgressPill(step: StepProgress) {
    val accent = if (step.satisfied) LumiColors.Emerald else LumiColors.Ivory
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = LumiColors.GlassDark,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.72f)),
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = if (step.satisfied) "✓ 机位已到位" else "${step.direction.arrow} ${step.direction.label}",
                color = accent,
                fontSize = 11.sp,
                maxLines = 1,
            )
            Box(
                Modifier
                    .width(96.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0x28FFFFFF)),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(step.progress.coerceIn(0.08f, 1f))
                        .clip(RoundedCornerShape(2.dp))
                        .background(accent),
                )
            }
        }
    }
}

internal fun DrawScope.drawHorizonLevel(mid: Offset, rollDeg: Double) {
    if (abs(rollDeg) > 35.0) return
    val level = abs(rollDeg) <= PoseGuidance.LEVEL_GREEN_DEG
    val color = when {
        level -> LumiColors.Emerald
        abs(rollDeg) <= 5.0 -> Color.White.copy(alpha = 0.78f)
        else -> LumiColors.Amber.copy(alpha = 0.88f)
    }
    val stroke = 1.5.dp.toPx()
    val refStroke = 1.dp.toPx()
    val innerGap = 11.dp.toPx()
    val armLen = 16.dp.toPx()
    val refGap = innerGap + armLen + 4.dp.toPx()
    val refLen = 6.dp.toPx()
    val refColor = if (level) LumiColors.Emerald.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.30f)
    drawLine(refColor, Offset(mid.x - refGap - refLen, mid.y), Offset(mid.x - refGap, mid.y), refStroke, cap = StrokeCap.Round)
    drawLine(refColor, Offset(mid.x + refGap, mid.y), Offset(mid.x + refGap + refLen, mid.y), refStroke, cap = StrokeCap.Round)
    val rad = if (level) 0.0 else Math.toRadians(rollDeg)
    val cx = cos(rad).toFloat()
    val sy = sin(rad).toFloat()
    drawLine(
        color,
        Offset(mid.x - (innerGap + armLen) * cx, mid.y - (innerGap + armLen) * sy),
        Offset(mid.x - innerGap * cx, mid.y - innerGap * sy),
        stroke,
        cap = StrokeCap.Round,
    )
    drawLine(
        color,
        Offset(mid.x + innerGap * cx, mid.y + innerGap * sy),
        Offset(mid.x + (innerGap + armLen) * cx, mid.y + (innerGap + armLen) * sy),
        stroke,
        cap = StrokeCap.Round,
    )
}

internal fun DrawScope.drawCloseupFrames(closeups: List<CloseupOverlay>) {
    if (closeups.isEmpty()) return
    val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
    val thin = 1.dp.toPx()
    val cornerStroke = 1.8.dp.toPx()
    closeups.forEachIndexed { idx, box ->
        val alpha = if (idx == 0) 0.78f else 0.48f
        val color = LumiColors.Ivory.copy(alpha = alpha)
        val w = (box.right - box.left).coerceAtLeast(1f)
        val h = (box.bottom - box.top).coerceAtLeast(1f)
        drawRect(
            color = color.copy(alpha = alpha * 0.55f),
            topLeft = Offset(box.left, box.top),
            size = Size(w, h),
            style = Stroke(width = thin, pathEffect = dash),
        )
        val corner = minOf(12.dp.toPx(), w * 0.22f, h * 0.22f)
        drawLine(color, Offset(box.left, box.top), Offset(box.left + corner, box.top), cornerStroke, cap = StrokeCap.Round)
        drawLine(color, Offset(box.left, box.top), Offset(box.left, box.top + corner), cornerStroke, cap = StrokeCap.Round)
        drawLine(color, Offset(box.right, box.top), Offset(box.right - corner, box.top), cornerStroke, cap = StrokeCap.Round)
        drawLine(color, Offset(box.right, box.top), Offset(box.right, box.top + corner), cornerStroke, cap = StrokeCap.Round)
        drawLine(color, Offset(box.left, box.bottom), Offset(box.left + corner, box.bottom), cornerStroke, cap = StrokeCap.Round)
        drawLine(color, Offset(box.left, box.bottom), Offset(box.left, box.bottom - corner), cornerStroke, cap = StrokeCap.Round)
        drawLine(color, Offset(box.right, box.bottom), Offset(box.right - corner, box.bottom), cornerStroke, cap = StrokeCap.Round)
        drawLine(color, Offset(box.right, box.bottom), Offset(box.right, box.bottom - corner), cornerStroke, cap = StrokeCap.Round)
        if (idx == 0) {
            drawCircle(color, 2.2.dp.toPx(), Offset((box.left + box.right) * 0.5f, (box.top + box.bottom) * 0.5f))
        }
    }
}

internal fun DrawScope.drawCandidateBrackets(candidates: List<CandidateOverlay>) {
    if (candidates.isEmpty()) return
    val stroke = 1.8.dp.toPx()
    for (c in candidates) {
        val color = if (c.userPicked) LumiColors.Ivory else LumiColors.Emerald.copy(alpha = 0.78f)
        val w = c.right - c.left
        val h = c.bottom - c.top
        val corner = minOf(14.dp.toPx(), w * 0.25f, h * 0.25f)
        drawLine(color, Offset(c.left, c.top), Offset(c.left + corner, c.top), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(c.left, c.top), Offset(c.left, c.top + corner), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(c.right, c.top), Offset(c.right - corner, c.top), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(c.right, c.top), Offset(c.right, c.top + corner), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(c.left, c.bottom), Offset(c.left + corner, c.bottom), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(c.left, c.bottom), Offset(c.left, c.bottom - corner), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(c.right, c.bottom), Offset(c.right - corner, c.bottom), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(c.right, c.bottom), Offset(c.right, c.bottom - corner), stroke, cap = StrokeCap.Round)
    }
}

internal fun DrawScope.drawTapReticle(tap: Point) {
    val color = LumiColors.Ivory
    val radius = 18.dp.toPx()
    val stroke = 1.6.dp.toPx()
    val center = Offset(tap.x, tap.y)
    drawCircle(color, radius, center, style = Stroke(stroke))
    drawCircle(color, 2.8.dp.toPx(), center)
}

internal fun DrawScope.drawCompositionGuides(selection: PresetSelection, showBaseGrid: Boolean) {
    val w = size.width
    val h = size.height
    val baseGridColor = Color.White.copy(alpha = 0.16f)
    val lineColor = Color.White.copy(alpha = 0.24f)
    val accentColor = LumiColors.Ivory.copy(alpha = 0.62f)
    val stroke = 0.85.dp.toPx()
    val dash = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
    when (selection.preset) {
        CompositionPreset.AUTO -> {
            if (showBaseGrid) {
                drawLine(baseGridColor, Offset(w / 3f, 0f), Offset(w / 3f, h), stroke)
                drawLine(baseGridColor, Offset(w * 2f / 3f, 0f), Offset(w * 2f / 3f, h), stroke)
                drawLine(baseGridColor, Offset(0f, h / 3f), Offset(w, h / 3f), stroke)
                drawLine(baseGridColor, Offset(0f, h * 2f / 3f), Offset(w, h * 2f / 3f), stroke)
            }
        }
        CompositionPreset.RULE_OF_THIRDS -> {
            val leftColor = if (selection.activeThirdSide == CompositionChoice.LEFT_THIRD) accentColor else lineColor
            val rightColor = if (selection.activeThirdSide == CompositionChoice.RIGHT_THIRD) accentColor else lineColor
            drawLine(leftColor, Offset(w / 3f, 0f), Offset(w / 3f, h), stroke)
            drawLine(rightColor, Offset(w * 2f / 3f, 0f), Offset(w * 2f / 3f, h), stroke)
            drawLine(lineColor, Offset(0f, h / 3f), Offset(w, h / 3f), stroke)
            drawLine(lineColor, Offset(0f, h * 2f / 3f), Offset(w, h * 2f / 3f), stroke)
        }
        CompositionPreset.SYMMETRY -> {
            drawLine(accentColor, Offset(w / 2f, 0f), Offset(w / 2f, h), stroke)
            drawLine(lineColor, Offset(0f, h / 2f), Offset(w, h / 2f), stroke)
        }
        CompositionPreset.TIGHT -> {
            drawLine(accentColor, Offset(0f, h * 0.14f), Offset(w, h * 0.14f), stroke, pathEffect = dash)
        }
        CompositionPreset.HEADROOM -> {
            drawLine(accentColor, Offset(0f, h * 0.24f), Offset(w, h * 0.24f), stroke, pathEffect = dash)
        }
    }
}
