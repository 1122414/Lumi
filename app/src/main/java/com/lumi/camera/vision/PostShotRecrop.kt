package com.lumi.camera.vision

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.lumi.camera.shotplan.DirectorCue
import com.lumi.camera.shotplan.PhotoQualityEvaluator
import com.lumi.camera.shotplan.PhotoQualityScore
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class NormalizedCrop(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)

    fun isMeaningfullyDifferentFrom(other: NormalizedCrop, minDelta: Float = 0.055f): Boolean =
        abs(left - other.left) + abs(top - other.top) + abs(right - other.right) + abs(bottom - other.bottom) >= minDelta * 2f

    fun isNearFullFrame(): Boolean = width >= 0.94f && height >= 0.94f
}

internal data class RecropProposal(
    val id: String,
    val title: String,
    val subtitle: String,
    val crop: NormalizedCrop,
)

internal data class PostShotReview(
    val photoUri: Uri,
    val summary: String,
    val proposals: List<RecropProposal>,
    val qualityScore: PhotoQualityScore? = null,
    val capturedAtMs: Long = System.currentTimeMillis(),
)

internal object PostShotRecrop {
    fun buildReview(
        photoUri: Uri,
        subjectBounds: FaceBox?,
        rollDeg: Double?,
        wasReady: Boolean,
        reason: String?,
        directorCue: DirectorCue?,
        meanLuma: Int? = null,
        zoomRatio: Float = 1f,
        backgroundClutter: Double? = null,
        unresolvedStep: Boolean = false,
        faceBox: FaceBox? = null,
    ): PostShotReview {
        val summary = reviewSummary(subjectBounds, rollDeg, wasReady, reason, directorCue)
        val proposals = generateProposals(subjectBounds, faceBox)
        val quality = PhotoQualityEvaluator.evaluate(
            subjectBounds = subjectBounds,
            rollDeg = rollDeg,
            wasReady = wasReady,
            meanLuma = meanLuma,
            zoomRatio = zoomRatio,
            backgroundClutter = backgroundClutter,
            unresolvedStep = unresolvedStep,
            directorCue = directorCue,
        )
        return PostShotReview(photoUri, summary, proposals, qualityScore = quality)
    }

    internal fun reviewSummary(
        subjectBounds: FaceBox?,
        rollDeg: Double?,
        wasReady: Boolean,
        reason: String?,
        directorCue: DirectorCue?,
    ): String {
        val tilt = rollDeg?.let { abs(it) }
        return when {
            tilt != null && tilt > 3.5 ->
                "画面略倾斜 ${"%.1f".format(tilt)}°，试试下方二次构图收紧边缘"
            directorCue != null && !wasReady ->
                "${directorCue.category.badge}提示：${directorCue.tip} · 可先看二次精裁"
            subjectBounds != null && (subjectBounds.left < 0.06 || subjectBounds.right > 0.94) ->
                "主体略贴边，已生成三分与聚焦精裁方案"
            wasReady && !reason.isNullOrBlank() ->
                "成片已对齐（$reason）· 可对比 2 种精裁效果"
            wasReady ->
                "构图稳定自然 · 可一键对比二次构图方案"
            else ->
                "已保存原图 · 试试下方 2 种美学二次裁剪"
        }
    }

    internal fun generateProposals(subjectBounds: FaceBox?, faceBox: FaceBox? = null): List<RecropProposal> {
        val box = subjectBounds ?: faceBox ?: FaceBox(0.32, 0.24, 0.68, 0.78)
        val cx = ((box.left + box.right) / 2.0).toFloat().coerceIn(0.10f, 0.90f)
        val cy = ((box.top + box.bottom) / 2.0).toFloat().coerceIn(0.12f, 0.88f)
        val sw = (box.right - box.left).toFloat().coerceIn(0.08f, 0.90f)
        val sh = (box.bottom - box.top).toFloat().coerceIn(0.10f, 0.90f)

        val candidates = mutableListOf<RecropProposal>()

        // Portrait close-up proposals when a face is detected in a panoramic/full-body shot.
        if (faceBox != null) {
            val fw = (faceBox.right - faceBox.left).toFloat().coerceIn(0.04f, 0.90f)
            val fh = (faceBox.bottom - faceBox.top).toFloat().coerceIn(0.04f, 0.90f)
            val fcx = ((faceBox.left + faceBox.right) / 2.0).toFloat()
            val fcy = ((faceBox.top + faceBox.bottom) / 2.0).toFloat()
            if (fw <= 0.30f && fh <= 0.32f) {
                val halfSize = max(fw * 3.1f, fh * 2.7f).coerceIn(0.42f, 0.66f)
                val halfCrop = fitCropContainingSubject(
                    desiredCenterX = fcx,
                    desiredCenterY = (fcy + fh * 0.72f).coerceIn(0.18f, 0.85f),
                    cropW = halfSize,
                    cropH = halfSize,
                    box = faceBox,
                )
                if (!halfCrop.isNearFullFrame()) {
                    candidates += RecropProposal(
                        id = "half_body_closeup",
                        title = "半身特写",
                        subtitle = "从全景裁出人物半身，更有故事感",
                        crop = halfCrop,
                    )
                }
            }
            if (fw <= 0.24f && fh <= 0.26f) {
                val faceSize = max(fw * 2.1f, fh * 1.9f).coerceIn(0.35f, 0.48f)
                val faceCrop = fitCropContainingSubject(
                    desiredCenterX = fcx,
                    desiredCenterY = (fcy + fh * 0.12f).coerceIn(0.14f, 0.86f),
                    cropW = faceSize,
                    cropH = faceSize,
                    box = faceBox,
                )
                if (!faceCrop.isNearFullFrame() && candidates.none { !faceCrop.isMeaningfullyDifferentFrom(it.crop) }) {
                    candidates += RecropProposal(
                        id = "face_closeup",
                        title = "脸部特写",
                        subtitle = "聚焦神态与面部光影细节",
                        crop = faceCrop,
                    )
                }
            }
        }

        // Proposal 1: Rule-of-thirds balance (places subject onto left-third or right-third with clean headroom).
        val targetThirdX = if (cx <= 0.5f) 0.36f else 0.64f
        val thirdScale = max(0.74f, min(0.90f, max(sw / 0.52f, sh / 0.68f)))
        val thirdCrop = fitCropContainingSubject(
            desiredCenterX = cx + (0.5f - targetThirdX) * thirdScale,
            desiredCenterY = cy + (0.5f - 0.46f) * thirdScale,
            cropW = thirdScale,
            cropH = thirdScale,
            box = box,
        )
        if (!thirdCrop.isNearFullFrame()) {
            val sideLabel = if (targetThirdX < 0.5f) "左三分" else "右三分"
            candidates += RecropProposal(
                id = "rule_of_thirds",
                title = "${sideLabel}精裁",
                subtitle = "主体落三分线，留白更舒展",
                crop = thirdCrop,
            )
        }

        // Proposal 2: Tight subject-focused crop (removes cluttered edges while keeping full subject + breathing room).
        val tightScale = max(0.62f, min(0.84f, max(sw * 1.48f, sh * 1.32f)))
        val tightCrop = fitCropContainingSubject(
            desiredCenterX = cx,
            desiredCenterY = (cy - sh * 0.04f).coerceIn(0.15f, 0.85f),
            cropW = tightScale,
            cropH = tightScale,
            box = box,
        )
        if (!tightCrop.isNearFullFrame() && candidates.none { !tightCrop.isMeaningfullyDifferentFrom(it.crop) }) {
            candidates += RecropProposal(
                id = "tight_focus",
                title = "聚焦特写",
                subtitle = "裁去边缘杂物，更突出主体",
                crop = tightCrop,
            )
        }

        // Fallback Proposal if subject already filled most of the frame: symmetric balanced trim.
        if (candidates.size < 2) {
            val centerScale = max(0.78f, min(0.88f, max(sw * 1.22f, sh * 1.18f)))
            val centerCrop = fitCropContainingSubject(
                desiredCenterX = cx,
                desiredCenterY = cy,
                cropW = centerScale,
                cropH = centerScale,
                box = box,
            )
            if (!centerCrop.isNearFullFrame() && candidates.none { !centerCrop.isMeaningfullyDifferentFrom(it.crop) }) {
                candidates += RecropProposal(
                    id = "balanced_center",
                    title = "居中平衡",
                    subtitle = "收紧四周边界，重心更稳",
                    crop = centerCrop,
                )
            }
        }

        return candidates.take(3)
    }

    private fun fitCropContainingSubject(
        desiredCenterX: Float,
        desiredCenterY: Float,
        cropW: Float,
        cropH: Float,
        box: FaceBox,
    ): NormalizedCrop {
        val margin = 0.035f
        val minW = ((box.right - box.left).toFloat() + margin * 2f).coerceIn(0.30f, 1.0f)
        val minH = ((box.bottom - box.top).toFloat() + margin * 2f).coerceIn(0.30f, 1.0f)
        val w = max(cropW, minW).coerceIn(0.35f, 1.0f)
        val h = max(cropH, minH).coerceIn(0.35f, 1.0f)

        var left = (desiredCenterX - w / 2f).coerceIn(0f, 1f - w)
        var top = (desiredCenterY - h / 2f).coerceIn(0f, 1f - h)

        val subjLeft = (box.left.toFloat() - margin).coerceAtLeast(0f)
        val subjRight = (box.right.toFloat() + margin).coerceAtMost(1f)
        val subjTop = (box.top.toFloat() - margin).coerceAtLeast(0f)
        val subjBottom = (box.bottom.toFloat() + margin).coerceAtMost(1f)

        if (subjLeft < left) left = subjLeft
        if (subjRight > left + w) left = (subjRight - w).coerceIn(0f, 1f - w)
        if (subjTop < top) top = subjTop
        if (subjBottom > top + h) top = (subjBottom - h).coerceIn(0f, 1f - h)

        return NormalizedCrop(
            left = left.coerceIn(0f, 1f - w),
            top = top.coerceIn(0f, 1f - h),
            right = (left + w).coerceIn(w, 1f),
            bottom = (top + h).coerceIn(h, 1f),
        )
    }

    fun loadPreviewBitmap(context: Context, uri: Uri, maxEdge: Int = 1080): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val decode = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decode) } ?: return null
        return applyExifOrientation(context, uri, raw)
    }

    fun saveCroppedCopy(context: Context, sourceUri: Uri, crop: NormalizedCrop, suffix: String): Uri? {
        val resolver = context.contentResolver
        val full = resolver.openInputStream(sourceUri)?.use { BitmapFactory.decodeStream(it) } ?: return null
        val upright = applyExifOrientation(context, sourceUri, full)
        return try {
            val x = (crop.left * upright.width).toInt().coerceIn(0, upright.width - 1)
            val y = (crop.top * upright.height).toInt().coerceIn(0, upright.height - 1)
            val w = (crop.width * upright.width).toInt().coerceIn(1, upright.width - x)
            val h = (crop.height * upright.height).toInt().coerceIn(1, upright.height - y)
            val cropped = Bitmap.createBitmap(upright, x, y, w, h)
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "Lumi_${suffix}_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Lumi")
                    }
                }
                val dest = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                val ok = resolver.openOutputStream(dest)?.use { out ->
                    cropped.compress(Bitmap.CompressFormat.JPEG, 94, out)
                } == true
                if (ok) dest else {
                    resolver.delete(dest, null, null)
                    null
                }
            } finally {
                if (cropped !== upright) cropped.recycle()
            }
        } finally {
            upright.recycle()
        }
    }

    private fun applyExifOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }
}
