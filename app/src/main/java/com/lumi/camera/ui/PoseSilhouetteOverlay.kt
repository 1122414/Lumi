package com.lumi.camera.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumi.camera.shotplan.PoseSilhouette

/**
 * Draws a subtle, editorial dashed vector pose silhouette in the viewfinder to guide portrait
 * posture and placement without cluttering the image.
 */
internal fun DrawScope.drawPoseSilhouette(silhouette: PoseSilhouette) {
    val strokeColor = LumiColors.Ivory.copy(alpha = 0.48f)
    val guideColor = LumiColors.Ivory.copy(alpha = 0.22f)
    val dash = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))
    val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash)
    val fineStroke = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash)

    when (silhouette) {
        PoseSilhouette.HALF_BODY_LEFT -> drawHalfBodyFigure(
            centerX = size.width * 0.34f,
            headTopY = size.height * 0.20f,
            headRadius = size.width * 0.095f,
            shoulderSpan = size.width * 0.42f,
            tilt = -0.06f,
            strokeColor = strokeColor,
            guideColor = guideColor,
            stroke = stroke,
            fineStroke = fineStroke,
        )
        PoseSilhouette.HALF_BODY_RIGHT -> drawHalfBodyFigure(
            centerX = size.width * 0.66f,
            headTopY = size.height * 0.20f,
            headRadius = size.width * 0.095f,
            shoulderSpan = size.width * 0.42f,
            tilt = 0.06f,
            strokeColor = strokeColor,
            guideColor = guideColor,
            stroke = stroke,
            fineStroke = fineStroke,
        )
        PoseSilhouette.CENTER_PORTRAIT -> drawHalfBodyFigure(
            centerX = size.width * 0.50f,
            headTopY = size.height * 0.18f,
            headRadius = size.width * 0.105f,
            shoulderSpan = size.width * 0.48f,
            tilt = 0f,
            strokeColor = strokeColor,
            guideColor = guideColor,
            stroke = stroke,
            fineStroke = fineStroke,
        )
        PoseSilhouette.FULL_BODY_SCENIC -> drawFullBodyFigure(
            centerX = size.width * 0.50f,
            headTopY = size.height * 0.46f,
            height = size.height * 0.42f,
            strokeColor = strokeColor,
            guideColor = guideColor,
            stroke = stroke,
            fineStroke = fineStroke,
        )
        PoseSilhouette.COUPLE_GROUP -> {
            drawHalfBodyFigure(
                centerX = size.width * 0.37f,
                headTopY = size.height * 0.22f,
                headRadius = size.width * 0.082f,
                shoulderSpan = size.width * 0.34f,
                tilt = 0.04f,
                strokeColor = strokeColor,
                guideColor = guideColor,
                stroke = stroke,
                fineStroke = fineStroke,
            )
            drawHalfBodyFigure(
                centerX = size.width * 0.63f,
                headTopY = size.height * 0.25f,
                headRadius = size.width * 0.078f,
                shoulderSpan = size.width * 0.32f,
                tilt = -0.04f,
                strokeColor = strokeColor,
                guideColor = guideColor,
                stroke = stroke,
                fineStroke = fineStroke,
            )
        }
    }
}

private fun DrawScope.drawHalfBodyFigure(
    centerX: Float,
    headTopY: Float,
    headRadius: Float,
    shoulderSpan: Float,
    tilt: Float,
    strokeColor: Color,
    guideColor: Color,
    stroke: Stroke,
    fineStroke: Stroke,
) {
    val headW = headRadius * 1.75f
    val headH = headRadius * 2.25f
    val headCenterY = headTopY + headH * 0.5f

    // Subtle eye-line tick
    val eyeY = headTopY + headH * 0.46f
    drawLine(
        color = guideColor,
        start = Offset(centerX - headW * 0.72f, eyeY),
        end = Offset(centerX + headW * 0.72f, eyeY),
        strokeWidth = 1.dp.toPx(),
    )

    // Head oval
    drawOval(
        color = strokeColor,
        topLeft = Offset(centerX - headW * 0.5f, headTopY),
        size = Size(headW, headH),
        style = stroke,
    )

    // Neck + relaxed shoulder arc down to lower frame
    val neckY = headTopY + headH + headRadius * 0.22f
    val shoulderY = neckY + headRadius * 0.58f
    val halfSpan = shoulderSpan * 0.5f
    val bottomY = (headCenterY + headH * 2.55f).coerceAtMost(size.height * 0.96f)
    val leftShift = tilt * shoulderSpan

    val bodyPath = Path().apply {
        moveTo(centerX - halfSpan * 0.96f, bottomY)
        cubicTo(
            centerX - halfSpan * 1.02f, shoulderY + (bottomY - shoulderY) * 0.25f,
            centerX - halfSpan * 0.88f, shoulderY + leftShift,
            centerX - headW * 0.26f, neckY,
        )
        moveTo(centerX + headW * 0.26f, neckY)
        cubicTo(
            centerX + halfSpan * 0.88f, shoulderY - leftShift,
            centerX + halfSpan * 1.02f, shoulderY + (bottomY - shoulderY) * 0.25f,
            centerX + halfSpan * 0.96f, bottomY,
        )
    }
    drawPath(bodyPath, color = strokeColor, style = stroke)

    // Soft shoulder balance horizon line
    drawLine(
        color = guideColor,
        start = Offset(centerX - halfSpan * 0.75f, shoulderY + leftShift * 0.5f),
        end = Offset(centerX + halfSpan * 0.75f, shoulderY - leftShift * 0.5f),
        strokeWidth = fineStroke.width,
    )
}

private fun DrawScope.drawFullBodyFigure(
    centerX: Float,
    headTopY: Float,
    height: Float,
    strokeColor: Color,
    guideColor: Color,
    stroke: Stroke,
    fineStroke: Stroke,
) {
    val headH = height * 0.145f
    val headW = headH * 0.78f
    drawOval(
        color = strokeColor,
        topLeft = Offset(centerX - headW * 0.5f, headTopY),
        size = Size(headW, headH),
        style = stroke,
    )
    val shoulderY = headTopY + headH * 1.28f
    val shoulderHalf = headW * 1.35f
    val waistY = headTopY + height * 0.44f
    val waistHalf = headW * 0.88f
    val feetY = headTopY + height

    val torso = Path().apply {
        moveTo(centerX - shoulderHalf, shoulderY + headH * 0.45f)
        quadraticTo(centerX - shoulderHalf * 0.85f, shoulderY, centerX - headW * 0.24f, shoulderY - headH * 0.14f)
        moveTo(centerX + headW * 0.24f, shoulderY - headH * 0.14f)
        quadraticTo(centerX + shoulderHalf * 0.85f, shoulderY, centerX + shoulderHalf, shoulderY + headH * 0.45f)
        // Tapered silhouette legs
        moveTo(centerX - waistHalf, waistY)
        lineTo(centerX - waistHalf * 1.15f, feetY)
        moveTo(centerX + waistHalf, waistY)
        lineTo(centerX + waistHalf * 1.15f, feetY)
    }
    drawPath(torso, color = strokeColor, style = stroke)

    // Subtle ground plane baseline so user keeps feet inside the lower safety margin
    drawLine(
        color = guideColor,
        start = Offset(centerX - shoulderHalf * 1.6f, feetY),
        end = Offset(centerX + shoulderHalf * 1.6f, feetY),
        strokeWidth = fineStroke.width,
    )
}

@Composable
internal fun PoseSilhouetteToggleChip(
    silhouette: PoseSilhouette,
    visible: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(16.dp),
        color = if (visible) LumiColors.Ivory.copy(alpha = 0.18f) else LumiColors.GlassDark,
        border = BorderStroke(
            width = 1.dp,
            color = if (visible) LumiColors.Ivory.copy(alpha = 0.72f) else LumiColors.HairlineStrong,
        ),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (visible) "姿态线框 · ${silhouette.label}" else "显示姿态线框",
                color = if (visible) LumiColors.Ivory else LumiColors.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
