package com.lumi.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumi.camera.camera.ViewfinderZoom
import java.util.Locale
import kotlin.math.*

private val Pointer = Color(0xFFFFBE49)
private const val RADIANS_PER_LOG = 0.63

internal fun dragWheel(ratio: Double, deltaInRadii: Double, min: Float, max: Float): Double =
    (ratio * exp(-deltaInRadii / RADIANS_PER_LOG)).coerceIn(min.toDouble(), max.toDouble())

internal fun wheelStop(raw: Double, min: Float, max: Float): Float = when {
    raw <= min -> min
    raw >= max -> max
    else -> (round(raw * 10) / 10).toFloat().coerceIn(min, max)
}

private fun label(ratio: Float) = String.format(Locale.ROOT, "%.1f", ratio).removeSuffix(".0") + "×"

@Composable
internal fun ZoomWheel(zoom: ViewfinderZoom, select: (Float) -> Unit, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(zoom.ratio)
    val selectRatio by rememberUpdatedState(select)
    val limits = zoom.min..zoom.max
    BoxWithConstraints(modifier.fillMaxWidth().height(96.dp).clipToBounds()) {
        val radius = with(LocalDensity.current) { maxWidth.toPx() * 0.61f }
        val wheelWidth = maxWidth
        val keyWidth = minOf(48.dp, wheelWidth / max(1, zoom.keyRatios.size))
        var dragRatio by remember { mutableDoubleStateOf(1.0) }
        val drag = rememberDraggableState { dx ->
            dragRatio = dragWheel(dragRatio, dx / radius.toDouble(), zoom.min, zoom.max)
            selectRatio(wheelStop(dragRatio, zoom.min, zoom.max))
        }
        Box(Modifier.fillMaxSize().draggable(drag, Orientation.Horizontal,
            onDragStarted = { dragRatio = current.toDouble() })) {
            Canvas(Modifier.fillMaxSize().semantics {
                contentDescription = "变焦轮盘"
                stateDescription = label(zoom.ratio)
                progressBarRangeInfo = ProgressBarRangeInfo(zoom.ratio, limits)
                setProgress { selectRatio(wheelStop(it.toDouble(), zoom.min, zoom.max)); true }
            }) {
                val hub = Offset(center.x, radius + 3.dp.toPx())
                drawCircle(Color.Black.copy(alpha = 0.69f), radius, hub)
                drawCircle(Color.White.copy(alpha = 0.18f), radius, hub, style = Stroke(1.dp.toPx()))
                val rim = radius - 5.dp.toPx()
                fun tick(value: Float, length: Float, color: Color, width: Float) {
                    val angle = (RADIANS_PER_LOG * ln(value.toDouble() / zoom.ratio)).toFloat()
                    if (abs(angle) > 1.2f) return
                    val ray = Offset(sin(angle), -cos(angle))
                    drawLine(color, hub + ray * rim, hub + ray * (rim - length), width)
                }

                // Each decade has the same arc length on this logarithmic scale.
                val first = max(zoom.min.toDouble(), zoom.ratio * exp(-1.2 / RADIANS_PER_LOG))
                val last = min(zoom.max.toDouble(), zoom.ratio * exp(1.2 / RADIANS_PER_LOG))
                for (decade in floor(log10(first)).toInt()..floor(log10(last)).toInt()) {
                    val step = 10.0.pow(decade - 1)
                    for (i in 10..99) {
                        val value = (i * step).toFloat()
                        if (value !in limits) continue
                        val major = i % 10 == 0
                        tick(value, (if (major) 10.dp else 5.dp).toPx(),
                            Color.White.copy(alpha = if (major) 0.82f else 0.38f), 1.dp.toPx())
                    }
                }
                for (key in zoom.keyRatios) tick(key, 11.dp.toPx(), Color.White, 1.4.dp.toPx())
                tick(zoom.ratio, 13.dp.toPx(), Pointer, 2.5.dp.toPx())
            }
            Text(label(zoom.ratio), Modifier.align(Alignment.TopCenter).padding(top = 21.dp),
                color = Pointer, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            var nextLeft = 0.dp
            zoom.keyRatios.forEachIndexed { index, key ->
                val angle = (RADIANS_PER_LOG * ln(key.toDouble() / current)).coerceIn(-PI / 2, PI / 2)
                val idealLeft = wheelWidth * (0.5f + 0.44f * sin(angle).toFloat()) - keyWidth / 2
                // Keep a tap target for each lens even when its tick has rotated past the rim.
                val lastLeft = wheelWidth - keyWidth * (zoom.keyRatios.size - index)
                val left = idealLeft.coerceIn(minOf(nextLeft, lastLeft), lastLeft)
                nextLeft = left + keyWidth
                val side = ((left + keyWidth / 2) / wheelWidth - 0.5f) * 2
                Box(Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset(x = left, y = (42f + 6f * side * side).dp)
                    .width(keyWidth).height(48.dp).clickable(role = Role.Button) { selectRatio(key) },
                    contentAlignment = Alignment.Center) {
                    Text(label(key), color = if (abs(current - key) < 0.005f) Pointer else Color.White,
                        fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
