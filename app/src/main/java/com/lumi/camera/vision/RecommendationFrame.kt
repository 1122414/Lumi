package com.lumi.camera.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.lumi.camera.guidance.Uv
import com.lumi.camera.shotplan.SubjectCandidate
import java.io.ByteArrayOutputStream

internal class RecommendationFrame(
    val scene: SceneFrame,
    private val pixels: Bitmap,
    val candidates: List<SubjectCandidate> = emptyList(),
    val tapUv: Uv? = null,
) : AutoCloseable {

    fun jpeg(): ByteArray {
        val geometry = scene.geometry
        val crop = geometry.crop
        val safeLeft = crop.left.coerceIn(0, (pixels.width - 1).coerceAtLeast(0))
        val safeTop = crop.top.coerceIn(0, (pixels.height - 1).coerceAtLeast(0))
        val safeWidth = crop.width.coerceIn(1, (pixels.width - safeLeft).coerceAtLeast(1))
        val safeHeight = crop.height.coerceIn(1, (pixels.height - safeTop).coerceAtLeast(1))
        val scale = MAX_EDGE_PX / maxOf(safeWidth, safeHeight).coerceAtLeast(1)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postRotate(geometry.rotation.toFloat())
            if (geometry.mirrored) postScale(-1f, 1f)
        }
        val upright = Bitmap.createBitmap(pixels, safeLeft, safeTop, safeWidth, safeHeight, matrix, true)
        val annotated = if (candidates.isNotEmpty() || tapUv != null) {
            upright.copy(Bitmap.Config.ARGB_8888, true).also { bmp ->
                drawCandidateOverlay(bmp, candidates, tapUv)
            }
        } else upright
        return try {
            ByteArrayOutputStream().use { out ->
                check(annotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out))
                out.toByteArray()
            }
        } finally {
            if (annotated !== upright && !annotated.isRecycled) annotated.recycle()
            if (upright !== pixels && !upright.isRecycled) upright.recycle()
        }
    }

    private fun drawCandidateOverlay(bitmap: Bitmap, list: List<SubjectCandidate>, tap: Uv?) {
        val canvas = Canvas(bitmap)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val strokeWidth = maxOf(2.5f, w * 0.0065f)
        val textSize = maxOf(16f, w * 0.042f)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
        }
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            this.textSize = textSize
            typeface = Typeface.DEFAULT_BOLD
        }

        val palette = intArrayOf(
            0xFFFFD54F.toInt(), // #1 warm amber
            0xFF4FC3F7.toInt(), // #2 sky blue
            0xFF81C784.toInt(), // #3 soft green
            0xFFFF8A65.toInt(), // #4 coral
        )

        for (c in list) {
            val color = if (c.userPicked) 0xFFFFD54F.toInt() else palette[(c.id - 1) % palette.size]
            val rect = RectF(
                (c.box.left * w).toFloat(),
                (c.box.top * h).toFloat(),
                (c.box.right * w).toFloat(),
                (c.box.bottom * h).toFloat(),
            )
            borderPaint.color = color
            canvas.drawRect(rect, borderPaint)

            val label = if (c.userPicked) "${c.id}★" else "${c.id}"
            val padX = textSize * 0.28f
            val padY = textSize * 0.18f
            val labelW = textPaint.measureText(label) + padX * 2
            val labelH = textSize + padY * 2
            val badgeLeft = rect.left.coerceIn(0f, (w - labelW).coerceAtLeast(0f))
            val badgeTop = rect.top.coerceIn(0f, (h - labelH).coerceAtLeast(0f))
            badgePaint.color = color
            canvas.drawRect(badgeLeft, badgeTop, badgeLeft + labelW, badgeTop + labelH, badgePaint)
            canvas.drawText(label, badgeLeft + padX, badgeTop + padY + textSize * 0.85f, textPaint)
        }

        if (tap != null) {
            val tx = (tap.x * w).toFloat()
            val ty = (tap.y * h).toFloat()
            val radius = maxOf(8f, w * 0.022f)
            val tapStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFD54F.toInt()
                style = Paint.Style.STROKE
                this.strokeWidth = maxOf(2.5f, w * 0.006f)
            }
            val tapFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFD54F.toInt()
                style = Paint.Style.FILL
            }
            canvas.drawCircle(tx, ty, radius, tapStroke)
            canvas.drawCircle(tx, ty, radius * 0.28f, tapFill)
        }
    }

    override fun close() {
        if (!pixels.isRecycled) pixels.recycle()
    }

    companion object {
        private const val MAX_EDGE_PX = 512f
        private const val JPEG_QUALITY = 75
    }
}
