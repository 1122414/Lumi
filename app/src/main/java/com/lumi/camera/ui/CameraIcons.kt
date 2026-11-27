package com.lumi.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun LensFlipIcon(tint: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = 1.6.dp.toPx()
        val stroke = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val inset = sw * 1.1f
        val arcSize = Size(w - inset * 2f, h - inset * 2f)
        val tl = Offset(inset, inset)
        drawArc(tint, -145f, 125f, false, tl, arcSize, style = stroke)
        drawPath(
            Path().apply {
                moveTo(w * 0.72f, h * 0.25f)
                lineTo(w * 0.90f, h * 0.34f)
                lineTo(w * 0.85f, h * 0.14f)
            },
            tint,
            style = stroke,
        )
        drawArc(tint, 35f, 125f, false, tl, arcSize, style = stroke)
        drawPath(
            Path().apply {
                moveTo(w * 0.28f, h * 0.75f)
                lineTo(w * 0.10f, h * 0.66f)
                lineTo(w * 0.15f, h * 0.86f)
            },
            tint,
            style = stroke,
        )
    }
}

@Composable
internal fun ReferenceFrameIcon(tint: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = 1.5.dp.toPx()
        val stroke = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val pad = sw
        drawRoundRect(
            color = tint,
            topLeft = Offset(pad, h * 0.14f),
            size = Size(w - pad * 2f, h * 0.72f),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
            style = stroke,
        )
        val mountain = Path().apply {
            moveTo(w * 0.18f, h * 0.72f)
            lineTo(w * 0.42f, h * 0.44f)
            lineTo(w * 0.58f, h * 0.62f)
            lineTo(w * 0.72f, h * 0.49f)
            lineTo(w * 0.84f, h * 0.72f)
        }
        drawPath(mountain, tint, style = stroke)
        drawCircle(tint, radius = 1.4.dp.toPx(), center = Offset(w * 0.70f, h * 0.33f))
    }
}

@Composable
internal fun AiModeReticleIcon(tint: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = 1.55.dp.toPx()
        val stroke = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val pad = sw
        val c = w * 0.26f
        // Four minimal viewfinder corners
        drawLine(tint, Offset(pad, pad), Offset(pad + c, pad), sw, StrokeCap.Round)
        drawLine(tint, Offset(pad, pad), Offset(pad, pad + c), sw, StrokeCap.Round)
        drawLine(tint, Offset(w - pad, pad), Offset(w - pad - c, pad), sw, StrokeCap.Round)
        drawLine(tint, Offset(w - pad, pad), Offset(w - pad, pad + c), sw, StrokeCap.Round)
        drawLine(tint, Offset(pad, h - pad), Offset(pad + c, h - pad), sw, StrokeCap.Round)
        drawLine(tint, Offset(pad, h - pad), Offset(pad, h - pad - c), sw, StrokeCap.Round)
        drawLine(tint, Offset(w - pad, h - pad), Offset(w - pad - c, h - pad), sw, StrokeCap.Round)
        drawLine(tint, Offset(w - pad, h - pad), Offset(w - pad, h - pad - c), sw, StrokeCap.Round)
        // Minimal central focus ring + dot
        drawCircle(tint, radius = w * 0.19f, center = center, style = stroke)
        drawCircle(tint, radius = 1.6.dp.toPx(), center = center)
    }
}

@Composable
internal fun SettingsHexIcon(tint: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension * 0.44f
        val sw = 1.55.dp.toPx()
        val path = Path()
        for (i in 0 until 6) {
            val angle = Math.toRadians((60 * i - 30).toDouble())
            val x = center.x + (r * cos(angle)).toFloat()
            val y = center.y + (r * sin(angle)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, tint, style = Stroke(sw, join = StrokeJoin.Round))
        drawCircle(tint, radius = r * 0.34f, center = center, style = Stroke(sw))
    }
}

@Composable
internal fun FilterRingsIcon(tint: Color, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension * 0.27f
        val sw = 1.35.dp.toPx()
        val stroke = Stroke(sw)
        val cx = center.x
        val cy = center.y
        drawCircle(tint, r, Offset(cx, cy - r * 0.56f), style = stroke)
        drawCircle(tint, r, Offset(cx - r * 0.54f, cy + r * 0.42f), style = stroke)
        drawCircle(tint, r, Offset(cx + r * 0.54f, cy + r * 0.42f), style = stroke)
    }
}

@Composable
internal fun ExposurePlusMinusIcon(tint: Color, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = 1.55.dp.toPx()
        // Plus (upper)
        val arm = w * 0.24f
        val plusY = h * 0.36f
        drawLine(tint, Offset(center.x - arm, plusY), Offset(center.x + arm, plusY), sw, StrokeCap.Round)
        drawLine(tint, Offset(center.x, plusY - arm), Offset(center.x, plusY + arm), sw, StrokeCap.Round)
        // Minus (lower)
        val minusY = h * 0.76f
        drawLine(tint, Offset(center.x - arm, minusY), Offset(center.x + arm, minusY), sw, StrokeCap.Round)
    }
}
