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
import com.lumi.camera.camera.FlashOption
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

@Composable
internal fun FlashIcon(option: FlashOption, tint: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = 1.55.dp.toPx()
        val stroke = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val bolt = Path().apply {
            moveTo(w * 0.56f, h * 0.10f)
            lineTo(w * 0.25f, h * 0.54f)
            lineTo(w * 0.50f, h * 0.54f)
            lineTo(w * 0.42f, h * 0.90f)
            lineTo(w * 0.76f, h * 0.44f)
            lineTo(w * 0.51f, h * 0.44f)
            close()
        }
        if (option == FlashOption.TORCH) {
            drawPath(bolt, tint)
            // Small torch glow rays on upper-right
            drawLine(tint, Offset(w * 0.76f, h * 0.16f), Offset(w * 0.88f, h * 0.12f), sw * 0.9f, StrokeCap.Round)
            drawLine(tint, Offset(w * 0.80f, h * 0.28f), Offset(w * 0.92f, h * 0.28f), sw * 0.9f, StrokeCap.Round)
        } else {
            drawPath(bolt, tint, style = stroke)
        }
        when (option) {
            FlashOption.OFF -> {
                drawLine(tint, Offset(w * 0.16f, h * 0.18f), Offset(w * 0.84f, h * 0.84f), sw, StrokeCap.Round)
            }
            FlashOption.AUTO -> {
                // Tiny crisp "A" indicator dot + chevron on bottom-right
                val ax = w * 0.78f
                val ay = h * 0.82f
                val aPath = Path().apply {
                    moveTo(ax - w * 0.09f, ay + h * 0.08f)
                    lineTo(ax, ay - h * 0.10f)
                    lineTo(ax + w * 0.09f, ay + h * 0.08f)
                }
                drawPath(aPath, tint, style = Stroke(sw * 0.85f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            FlashOption.ON, FlashOption.TORCH -> Unit
        }
    }
}

@Composable
internal fun SunEvIcon(tint: Color, size: Dp = 15.dp) {
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension * 0.20f
        val inner = r * 1.48f
        val outer = this.size.minDimension * 0.46f
        val sw = 1.25.dp.toPx()
        drawCircle(tint, radius = r, center = center)
        for (i in 0 until 8) {
            val angle = Math.toRadians(i * 45.0)
            val cosA = cos(angle).toFloat()
            val sinA = sin(angle).toFloat()
            drawLine(
                color = tint,
                start = Offset(center.x + inner * cosA, center.y + inner * sinA),
                end = Offset(center.x + outer * cosA, center.y + outer * sinA),
                strokeWidth = sw,
                cap = StrokeCap.Round,
            )
        }
    }
}
