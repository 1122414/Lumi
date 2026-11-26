package com.lumi.camera.shotplan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import com.lumi.camera.guidance.Observation
import com.lumi.camera.guidance.RotationProjection
import com.lumi.camera.guidance.Uv
import com.lumi.camera.vision.OutlineAttempt
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class SavedDiagnostic(val imageFile: File, val report: String)

internal data class DiagnosticInput(
    val config: ModelConfig,
    val jpeg: ByteArray?,
    val outcome: String,
    val frontCamera: Boolean,
    val zoomRatio: Float,
    val prepareMs: Long,
    val modelMs: Long,
    val verifyMs: Long,
    val sourceObservation: Observation?,
    val reply: ModelReply?,
    val failure: Throwable?,
    val outlineAttempt: OutlineAttempt?,
    val candidateCount: Int = 0,
    val userTapped: Boolean = false,
)

internal object CompositionDiagnostic {
    private const val MAX_HISTORY = 10

    fun directory(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "diagnostics")

    fun loadLatest(context: Context): SavedDiagnostic? {
        val dir = directory(context)
        val image = File(dir, "latest_overlay.jpg")
        val report = File(dir, "latest_report.txt")
        if (!image.isFile && !report.isFile) return null
        val text = if (report.isFile) runCatching { report.readText() }.getOrDefault("") else ""
        return SavedDiagnostic(image, text)
    }

    fun save(context: Context, input: DiagnosticInput) {
        try {
            val dir = directory(context)
            if (!dir.exists() && !dir.mkdirs()) return
            val nowMs = System.currentTimeMillis()
            val report = buildReport(nowMs, input)
            File(dir, "latest_report.txt").writeText(report)
            File(dir, "diag_${nowMs}_report.txt").writeText(report)

            val jpeg = input.jpeg
            if (jpeg != null) {
                val annotated = renderOverlay(jpeg, input)
                if (annotated != null) {
                    try {
                        writeJpeg(annotated, File(dir, "latest_overlay.jpg"))
                        writeJpeg(annotated, File(dir, "diag_${nowMs}_overlay.jpg"))
                    } finally {
                        annotated.recycle()
                    }
                }
            }
            prune(dir)
            Log.i("LumiRecommendation", "diagnostic saved to ${dir.absolutePath}/latest_overlay.jpg")
        } catch (e: Exception) {
            Log.w("LumiCamera", "Failed to save diagnostic", e)
        }
    }

    private fun writeJpeg(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
    }

    private fun prune(dir: File) {
        val reports = dir.listFiles { f -> f.name.startsWith("diag_") && f.name.endsWith("_report.txt") }
            ?.sortedByDescending { it.name }.orEmpty()
        for (old in reports.drop(MAX_HISTORY)) {
            old.delete()
            File(dir, old.name.replace("_report.txt", "_overlay.jpg")).delete()
        }
    }

    private fun renderOverlay(jpeg: ByteArray, input: DiagnosticInput): Bitmap? {
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        val bitmap = decoded.copy(Bitmap.Config.ARGB_8888, true)
        if (decoded !== bitmap) decoded.recycle()
        val canvas = Canvas(bitmap)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        fun px(uv: Uv) = (uv.x * w).toFloat() to (uv.y * h).toFloat()

        val subject = input.reply?.subject
        // 1. Model subject box/polygon (yellow)
        if (subject != null && subject.outline.size >= 3) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFD54F")
                style = Paint.Style.STROKE
                strokeWidth = maxOf(2.5f, w * 0.0045f)
            }
            val path = Path()
            subject.outline.forEachIndexed { i, p ->
                val (x, y) = px(p)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            canvas.drawPath(path, paint)
        }

        // 2. Local segmented subject contour (blue dashed)
        val contour = input.outlineAttempt?.outline?.capturePoints.orEmpty()
        if (contour.isNotEmpty()) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#46A9FF")
                style = Paint.Style.STROKE
                strokeWidth = maxOf(3f, w * 0.005f)
                pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
            }
            val path = Path()
            val gap = contour.indexOf(null)
            var drawing = false
            for (i in contour.indices) {
                val p = contour[(i + gap + 1) % contour.size]
                if (p == null) drawing = false
                else {
                    val (x, y) = px(p)
                    if (drawing) path.lineTo(x, y) else { path.moveTo(x, y); drawing = true }
                }
            }
            if (gap == -1) path.close()
            canvas.drawPath(path, paint)
        }

        // 3. Model seed point (yellow dot) & fallback seed if different (cyan dot)
        subject?.seed?.let { drawDot(canvas, px(it), Color.parseColor("#FFD54F"), maxOf(6f, w * 0.011f)) }
        input.outlineAttempt?.usedSeed?.let { used ->
            if (subject?.seed == null || subject.seed.distance(used) > 0.005) {
                drawDot(canvas, px(used), Color.parseColor("#00E5FF"), maxOf(6f, w * 0.011f))
            }
        }

        // 4. Projected target composition frame (green dashed quad) & aimPoint arrow
        val acceptedPlan = (input.reply?.decision as? PlanResult.Accepted)?.value
        val resolvedPlan = acceptedPlan?.let { p ->
            input.outlineAttempt?.outline?.bounds?.let(p::withMeasuredBounds) ?: p
        }
        val aim = resolvedPlan?.aimPoint ?: input.reply?.rawAimPoint
        val source = input.sourceObservation
        if (aim != null && aim.x in 0.0..1.0 && aim.y in 0.0..1.0) {
            if (source != null) {
                val targetPose = source.pose * (source.lens.lookAt(aim) * source.lens.lookAt(Uv.CENTER).inverse())
                val target = source.copy(pose = targetPose)
                val toSource = RotationProjection(target, source)
                val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#38DF85")
                    style = Paint.Style.STROKE
                    strokeWidth = maxOf(3f, w * 0.005f)
                    pathEffect = DashPathEffect(floatArrayOf(14f, 8f), 0f)
                }
                val perimeter = buildList {
                    val steps = 8
                    for (i in 0 until steps) add(Uv(i.toDouble() / steps, 0.0))
                    for (i in 0 until steps) add(Uv(1.0, i.toDouble() / steps))
                    for (i in 0 until steps) add(Uv(1.0 - i.toDouble() / steps, 1.0))
                    for (i in 0 until steps) add(Uv(0.0, 1.0 - i.toDouble() / steps))
                }
                val quad = Path()
                var started = false
                for (corner in perimeter + perimeter.first()) {
                    val projected = toSource.project(corner)
                    if (projected == null) { started = false; continue }
                    val (x, y) = px(projected)
                    if (!started) { quad.moveTo(x, y); started = true } else quad.lineTo(x, y)
                }
                canvas.drawPath(quad, framePaint)
            }
            val (cx, cy) = px(Uv.CENTER)
            val (ax, ay) = px(aim)
            val greenLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#38DF85")
                style = Paint.Style.STROKE
                strokeWidth = maxOf(3f, w * 0.005f)
            }
            canvas.drawLine(cx, cy, ax, ay, greenLine)
            canvas.drawCircle(ax, ay, maxOf(10f, w * 0.02f), greenLine)
        }

        // 5. Original center crosshair (red)
        val (cx, cy) = px(Uv.CENTER)
        val red = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF5252")
            style = Paint.Style.STROKE
            strokeWidth = maxOf(2.5f, w * 0.0045f)
        }
        val arm = maxOf(10f, w * 0.022f)
        canvas.drawLine(cx - arm, cy, cx + arm, cy, red)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, red)

        // 6. Info strip at bottom
        val plan = resolvedPlan
        val line1 = "${input.outcome} | ${input.config.safeText(input.config.model)} | ${input.modelMs}ms | ${if (input.frontCamera) "前置" else "后置"} ${"%.1f".format(Locale.US, input.zoomRatio)}x"
        val line2 = buildString {
            append(input.reply?.name?.let { "主体:$it" } ?: "主体:无")
            val choice = plan?.choice ?: input.reply?.rawChoice
            if (choice != null) append(" | ${choice.wireName}(${choice.label})")
            if (plan != null) {
                append(" | conf=${"%.2f".format(Locale.US, plan.confidence)}")
                append(" | ${input.config.safeText(plan.reason)}")
            }
        }
        val line3 = buildString {
            append("边缘:${input.outlineAttempt?.status ?: "-"}")
            val sz = plan?.suggestedZoom ?: input.reply?.suggestedZoom
            if (sz != null) append(" | 建议倍率:${SubjectCandidates.formatZoomLabel(sz)}")
            if (input.candidateCount > 0) {
                val cid = input.reply?.candidateId
                append(" | 候选:${input.candidateCount}${if (cid != null) "(选#$cid)" else "(回退)"}")
            }
            if (aim != null) append(" | aim=(${"%.2f".format(Locale.US, aim.x)}, ${"%.2f".format(Locale.US, aim.y)})")
        }
        val textSize = maxOf(16f, w * 0.031f)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
        }
        val pad = textSize * 0.6f
        val lineH = textSize * 1.35f
        val barTop = h - pad * 2 - lineH * 3
        val bgPaint = Paint().apply { color = Color.argb(185, 0, 0, 0) }
        canvas.drawRect(0f, barTop, w, h, bgPaint)
        canvas.drawText(line1, pad, barTop + pad + textSize, textPaint)
        canvas.drawText(line2, pad, barTop + pad + textSize + lineH, textPaint)
        canvas.drawText(line3, pad, barTop + pad + textSize + lineH * 2, textPaint)
        return bitmap
    }

    private fun drawDot(canvas: Canvas, pos: Pair<Float, Float>, fillColor: Int, radius: Float) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor; style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = maxOf(2f, radius * 0.3f)
        }
        canvas.drawCircle(pos.first, pos.second, radius, fill)
        canvas.drawCircle(pos.first, pos.second, radius, stroke)
    }

    private fun buildReport(nowMs: Long, input: DiagnosticInput): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(nowMs))
        val accepted = (input.reply?.decision as? PlanResult.Accepted)?.value
        val plan = accepted?.let { p -> input.outlineAttempt?.outline?.bounds?.let(p::withMeasuredBounds) ?: p }
        val subject = input.reply?.subject
        val raw = input.reply?.rawContent?.ifBlank { null }
            ?: (input.failure as? ModelFailure)?.rawReply
            ?: input.failure?.message
            ?: ""
        return buildString {
            appendLine("时间: $stamp")
            appendLine("结果: ${input.outcome}")
            appendLine("模型: ${input.config.safeText(input.config.model)} (${chatEndpoint(input.config.address, true)?.host.orEmpty()})")
            appendLine("镜头: ${if (input.frontCamera) "前置" else "后置"} ${"%.2f".format(Locale.US, input.zoomRatio)}x")
            appendLine("耗时: prepare=${input.prepareMs}ms, model=${input.modelMs}ms, verify=${input.verifyMs}ms")
            appendLine("候选模式: 候选数=${input.candidateCount}, 用户点选=${input.userTapped}, 选中编号=${input.reply?.candidateId ?: "-"}, 回退=${input.reply?.usedFallback ?: (input.candidateCount == 0)}")
            appendLine("边缘分割: ${input.outlineAttempt?.status ?: "-"}")
            val suggestedZoom = plan?.suggestedZoom ?: input.reply?.suggestedZoom
            if (suggestedZoom != null) {
                appendLine("建议焦段: ${SubjectCandidates.formatZoomLabel(suggestedZoom)}")
            }
            if (subject != null) {
                val b = subject.bounds
                appendLine("主体: ${subject.name} (${if (subject.person) "person" else "object"}) box=[${fmt(b.left)}, ${fmt(b.top)}, ${fmt(b.right)}, ${fmt(b.bottom)}] seed=${subject.seed?.let { "(${fmt(it.x)}, ${fmt(it.y)})" } ?: "-"}")
            }
            val choice = plan?.choice ?: input.reply?.rawChoice
            if (choice != null) {
                appendLine("构图选项: ${choice.wireName} (${choice.label})")
            }
            if (plan != null) {
                appendLine("手机计算 aimPoint: (${fmt(plan.aimPoint.x)}, ${fmt(plan.aimPoint.y)}) conf=${fmt(plan.confidence)} alreadyGood=${plan.alreadyGood}")
                appendLine("理由: ${input.config.safeText(plan.reason)}")
            } else if (input.reply?.rawAimPoint != null) {
                val aim = input.reply.rawAimPoint
                appendLine("原始 aimPoint: (${fmt(aim.x)}, ${fmt(aim.y)})")
            }
            appendLine()
            appendLine("--- 模型原始返回 ---")
            appendLine(input.config.safeText(raw))
        }
    }

    private fun fmt(v: Double) = "%.3f".format(Locale.US, v)
}
