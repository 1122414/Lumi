package com.lumi.camera.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumi.camera.camera.CaptureAspectRatio
import com.lumi.camera.camera.TimerOption
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun QuickControlsStrip(
    aspectRatio: CaptureAspectRatio,
    timer: TimerOption,
    onSelectAspectRatio: (CaptureAspectRatio) -> Unit,
    onSelectTimer: (TimerOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = LumiColors.GlassDark,
        border = BorderStroke(1.dp, LumiColors.HairlineStrong),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                CaptureAspectRatio.entries.forEach { ratio ->
                    val selected = ratio == aspectRatio
                    QuickOptionChip(
                        text = ratio.label,
                        selected = selected,
                        onClick = { onSelectAspectRatio(ratio) },
                    )
                }
            }
            Box(
                Modifier
                    .width(1.dp)
                    .height(16.dp)
                    .background(LumiColors.HairlineStrong),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TimerOption.entries.forEach { opt ->
                    val selected = opt == timer
                    QuickOptionChip(
                        text = if (opt == TimerOption.OFF) "定时关" else opt.label,
                        selected = selected,
                        onClick = { onSelectTimer(opt) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickOptionChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) LumiColors.Ivory else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (selected) LumiColors.IvoryInk else LumiColors.TextSecondary,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

internal data class FocusTapState(
    val x: Float,
    val y: Float,
    val token: Long,
)

@Composable
internal fun FocusEvOverlay(
    focus: FocusTapState,
    viewWidthPx: Float,
    viewHeightPx: Float,
    evSteps: Float,
    minEv: Float,
    maxEv: Float,
    canAdjustEv: Boolean,
    evActive: Boolean,
) {
    if (viewWidthPx <= 0f || viewHeightPx <= 0f) return
    val density = LocalDensity.current
    val reticleSizeDp = 58.dp
    val reticleSizePx = with(density) { reticleSizeDp.toPx() }
    val half = reticleSizePx / 2f
    val clampedX = focus.x.coerceIn(half + 8f, (viewWidthPx - half - 8f).coerceAtLeast(half + 8f))
    val clampedY = focus.y.coerceIn(half + 8f, (viewHeightPx - half - 8f).coerceAtLeast(half + 8f))

    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(160),
        label = "focus reticle scale",
    )

    val placeSliderOnLeft = clampedX > viewWidthPx - reticleSizePx * 1.45f
    val sliderGapPx = with(density) { 14.dp.toPx() }
    val sliderWidthDp = 28.dp
    val sliderWidthPx = with(density) { sliderWidthDp.toPx() }
    val sliderHeightDp = 96.dp
    val sliderHeightPx = with(density) { sliderHeightDp.toPx() }
    val sliderX = if (placeSliderOnLeft) {
        (clampedX - half - sliderGapPx - sliderWidthPx).coerceAtLeast(6f)
    } else {
        (clampedX + half + sliderGapPx).coerceAtMost(viewWidthPx - sliderWidthPx - 6f)
    }
    val sliderY = (clampedY - sliderHeightPx / 2f).coerceIn(10f, (viewHeightPx - sliderHeightPx - 10f).coerceAtLeast(10f))

    Box(Modifier.fillMaxSize()) {
        // Warm ivory precision corner focus box
        Canvas(
            modifier = Modifier
                .offset { IntOffset((clampedX - half).roundToInt(), (clampedY - half).roundToInt()) }
                .size(reticleSizeDp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            val w = size.width
            val h = size.height
            val c = w * 0.24f
            val stroke = 1.6.dp.toPx()
            val color = LumiColors.Ivory
            drawLine(color, Offset(0f, 0f), Offset(c, 0f), stroke, StrokeCap.Round)
            drawLine(color, Offset(0f, 0f), Offset(0f, c), stroke, StrokeCap.Round)
            drawLine(color, Offset(w, 0f), Offset(w - c, 0f), stroke, StrokeCap.Round)
            drawLine(color, Offset(w, 0f), Offset(w, c), stroke, StrokeCap.Round)
            drawLine(color, Offset(0f, h), Offset(c, h), stroke, StrokeCap.Round)
            drawLine(color, Offset(0f, h), Offset(0f, h - c), stroke, StrokeCap.Round)
            drawLine(color, Offset(w, h), Offset(w - c, h), stroke, StrokeCap.Round)
            drawLine(color, Offset(w, h), Offset(w, h - c), stroke, StrokeCap.Round)
            // Subtle center crosshair ticks
            val tick = 4.dp.toPx()
            drawLine(color.copy(alpha = 0.72f), Offset(w / 2f, 0f), Offset(w / 2f, tick), 1.1.dp.toPx())
            drawLine(color.copy(alpha = 0.72f), Offset(w / 2f, h - tick), Offset(w / 2f, h), 1.1.dp.toPx())
            drawLine(color.copy(alpha = 0.72f), Offset(0f, h / 2f), Offset(tick, h / 2f), 1.1.dp.toPx())
            drawLine(color.copy(alpha = 0.72f), Offset(w - tick, h / 2f), Offset(w, h / 2f), 1.1.dp.toPx())
        }

        if (canAdjustEv) {
            val span = (maxEv - minEv).coerceAtLeast(0.5f)
            val norm = ((evSteps - minEv) / span).coerceIn(0f, 1f)
            Column(
                modifier = Modifier
                    .offset { IntOffset(sliderX.roundToInt(), sliderY.roundToInt()) }
                    .width(sliderWidthDp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .width(sliderWidthDp)
                        .height(sliderHeightDp),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val trackX = size.width / 2f
                        val padY = 10.dp.toPx()
                        val trackTop = padY
                        val trackBottom = size.height - padY
                        val sunY = trackBottom - norm * (trackBottom - trackTop)
                        val gap = 10.dp.toPx()
                        val trackColor = LumiColors.Ivory.copy(alpha = if (evActive) 0.85f else 0.48f)
                        if (sunY - gap > trackTop) {
                            drawLine(trackColor, Offset(trackX, trackTop), Offset(trackX, sunY - gap), 1.3.dp.toPx(), StrokeCap.Round)
                        }
                        if (sunY + gap < trackBottom) {
                            drawLine(trackColor, Offset(trackX, sunY + gap), Offset(trackX, trackBottom), 1.3.dp.toPx(), StrokeCap.Round)
                        }
                    }
                    val padDp = 10.dp
                    val usableDp = sliderHeightDp - padDp * 2
                    val sunOffsetY = padDp + usableDp * (1f - norm) - 7.5.dp
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = sunOffsetY),
                    ) {
                        SunEvIcon(tint = LumiColors.Ivory, size = 15.dp)
                    }
                }
                if (evActive || abs(evSteps) >= 0.08f) {
                    Text(
                        text = String.format(Locale.ROOT, "%+.1f", evSteps),
                        color = LumiColors.Ivory,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
internal fun CountdownOverlay(
    secondsRemaining: Int,
    rotationDegrees: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer { rotationZ = rotationDegrees },
        ) {
            Text(
                text = secondsRemaining.toString(),
                color = LumiColors.Ivory,
                fontSize = 86.sp,
                fontWeight = FontWeight.Light,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "点击快门取消",
                color = LumiColors.TextSecondary,
                fontSize = 11.sp,
            )
        }
    }
}

internal fun DrawScope.drawAspectRatioMask(aspectRatio: CaptureAspectRatio) {
    if (aspectRatio == CaptureAspectRatio.RATIO_3_4) return
    val maskColor = Color(0xBD09090B)
    val lineColor = Color(0x45F2E6C9)
    val stroke = 1.dp.toPx()
    when (aspectRatio) {
        CaptureAspectRatio.RATIO_1_1 -> {
            val side = minOf(size.width, size.height)
            val topBar = ((size.height - side) / 2f).coerceAtLeast(0f)
            if (topBar > 0f) {
                drawRect(maskColor, Offset.Zero, Size(size.width, topBar))
                drawRect(maskColor, Offset(0f, size.height - topBar), Size(size.width, topBar))
                drawLine(lineColor, Offset(0f, topBar), Offset(size.width, topBar), stroke)
                drawLine(lineColor, Offset(0f, size.height - topBar), Offset(size.width, size.height - topBar), stroke)
            }
        }
        CaptureAspectRatio.RATIO_9_16 -> {
            val targetW = (size.height * (9f / 16f)).coerceAtMost(size.width)
            val sideBar = ((size.width - targetW) / 2f).coerceAtLeast(0f)
            if (sideBar > 0f) {
                drawRect(maskColor, Offset.Zero, Size(sideBar, size.height))
                drawRect(maskColor, Offset(size.width - sideBar, 0f), Size(sideBar, size.height))
                drawLine(lineColor, Offset(sideBar, 0f), Offset(sideBar, size.height), stroke)
                drawLine(lineColor, Offset(size.width - sideBar, 0f), Offset(size.width - sideBar, size.height), stroke)
            }
        }
        CaptureAspectRatio.RATIO_3_4 -> Unit
    }
}
