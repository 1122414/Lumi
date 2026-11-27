package com.lumi.camera.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumi.camera.camera.ViewfinderZoom
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

private const val RADIANS_PER_LOG = 0.63

internal fun dragWheel(ratio: Double, deltaInRadii: Double, min: Float, max: Float): Double =
    (ratio * exp(-deltaInRadii / RADIANS_PER_LOG)).coerceIn(min.toDouble(), max.toDouble())

internal fun wheelStop(raw: Double, min: Float, max: Float): Float = when {
    raw <= min -> min
    raw >= max -> max
    else -> (round(raw * 10) / 10).toFloat().coerceIn(min, max)
}

private fun zoomLabel(ratio: Float) = String.format(Locale.ROOT, "%.1f", ratio).removeSuffix(".0") + "×"

@Composable
internal fun ZoomWheel(
    zoom: ViewfinderZoom,
    select: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(zoom.ratio)
    val selectRatio by rememberUpdatedState(select)
    val limits = zoom.min..zoom.max
    val density = LocalDensity.current
    val dragRadiusPx = with(density) { 180.dp.toPx() }

    var dragging by remember { mutableStateOf(false) }
    var rulerVisible by remember { mutableStateOf(false) }
    var lastInteractMs by remember { mutableLongStateOf(0L) }
    var dragRatio by remember { mutableDoubleStateOf(1.0) }

    LaunchedEffect(dragging, lastInteractMs) {
        if (!dragging && rulerVisible) {
            delay(650L)
            rulerVisible = false
        }
    }

    val dragState = rememberDraggableState { dx ->
        dragRatio = dragWheel(dragRatio, dx / dragRadiusPx.toDouble(), zoom.min, zoom.max)
        selectRatio(wheelStop(dragRatio, zoom.min, zoom.max))
        lastInteractMs = System.currentTimeMillis()
    }

    val stops = remember(zoom.keyRatios, zoom.min, zoom.max) {
        val base = if (zoom.keyRatios.isNotEmpty()) zoom.keyRatios else listOf(1f)
        base.distinctBy { zoomLabel(it) }.take(4)
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AnimatedVisibility(
            visible = rulerVisible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = LumiColors.GlassDark,
                border = BorderStroke(1.dp, LumiColors.Hairline),
                modifier = Modifier
                    .width(210.dp)
                    .height(44.dp),
            ) {
                Box(Modifier.fillMaxSize()) {
                    Canvas(Modifier.fillMaxSize()) {
                        val centerX = size.width / 2f
                        val scalePx = size.width * 0.42f
                        val first = max(zoom.min.toDouble(), zoom.ratio * exp(-1.15 / RADIANS_PER_LOG))
                        val last = min(zoom.max.toDouble(), zoom.ratio * exp(1.15 / RADIANS_PER_LOG))
                        for (decade in floor(log10(first)).toInt()..floor(log10(last)).toInt()) {
                            val step = 10.0.pow(decade - 1)
                            for (i in 10..99) {
                                val value = (i * step).toFloat()
                                if (value !in limits) continue
                                val offset = (RADIANS_PER_LOG * ln(value.toDouble() / zoom.ratio)).toFloat()
                                val x = centerX + offset * scalePx
                                if (x !in 12f..(size.width - 12f)) continue
                                val major = i % 10 == 0
                                val h = if (major) 10.dp.toPx() else 5.dp.toPx()
                                drawLine(
                                    color = Color.White.copy(alpha = if (major) 0.72f else 0.30f),
                                    start = Offset(x, size.height - 6.dp.toPx()),
                                    end = Offset(x, size.height - 6.dp.toPx() - h),
                                    strokeWidth = 1.dp.toPx(),
                                    cap = StrokeCap.Round,
                                )
                            }
                        }
                        drawLine(
                            color = LumiColors.Ivory,
                            start = Offset(centerX, size.height - 4.dp.toPx()),
                            end = Offset(centerX, size.height - 17.dp.toPx()),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                    Text(
                        text = zoomLabel(zoom.ratio),
                        color = LumiColors.Ivory,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 4.dp),
                    )
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = LumiColors.GlassDark,
            border = BorderStroke(1.dp, LumiColors.Hairline),
            modifier = Modifier
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStarted = {
                        dragRatio = current.toDouble()
                        dragging = true
                        rulerVisible = true
                    },
                    onDragStopped = {
                        dragging = false
                        lastInteractMs = System.currentTimeMillis()
                    },
                )
                .semantics {
                    contentDescription = "变焦切换条"
                    stateDescription = zoomLabel(zoom.ratio)
                    progressBarRangeInfo = ProgressBarRangeInfo(zoom.ratio, limits)
                    setProgress { selectRatio(wheelStop(it.toDouble(), zoom.min, zoom.max)); true }
                },
        ) {
            val activeStop = stops.minByOrNull { abs(it - zoom.ratio) }
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                stops.forEach { stop ->
                    val isSelected = activeStop != null && abs(stop - activeStop) < 0.005f
                    val displayText = if (isSelected && abs(zoom.ratio - stop) >= 0.08f) {
                        zoomLabel(zoom.ratio)
                    } else {
                        zoomLabel(stop)
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isSelected) LumiColors.Ivory else Color.Transparent)
                            .clickable(role = Role.Button) { selectRatio(stop) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = displayText,
                            color = if (isSelected) LumiColors.IvoryInk else LumiColors.TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
