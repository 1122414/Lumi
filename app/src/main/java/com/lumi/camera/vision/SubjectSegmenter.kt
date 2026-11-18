package com.lumi.camera.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import com.lumi.camera.guidance.Uv

// MediaPipe Android 0.10.35 omits the ROI proto's normalized flag. Its native graph
// interprets this keypoint in pixels of the bitmap passed to segment(), including crops.
internal fun magicTouchKeypoint(seed: Uv, width: Int, height: Int): NormalizedKeypoint =
    NormalizedKeypoint.create((seed.x * width).toInt().toFloat(), (seed.y * height).toInt().toFloat())

// Confined to the analysis executor, including initialization and close.
internal class SubjectSegmenter(private val context: Context) : AutoCloseable {
    private var engine: InteractiveSegmenter? = null
    fun segment(pixels: SubjectPixels, seed: Uv): ForegroundMask? {
        val task = engine ?: InteractiveSegmenter.createFromOptions(context,
            InteractiveSegmenter.InteractiveSegmenterOptions.builder().setBaseOptions(
                BaseOptions.builder().setModelAssetPath("magic_touch.tflite").build())
                .setOutputCategoryMask(true).setOutputConfidenceMasks(false).build()).also { engine = it }
        val bitmap = Bitmap.createBitmap(pixels.argb(), pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
        try {
            BitmapImageBuilder(bitmap).build().use { image ->
                val roi = InteractiveSegmenter.RegionOfInterest.create(magicTouchKeypoint(seed, pixels.width, pixels.height))
                val result = task.segment(image, roi)
                result.categoryMask().get().use { mask ->
                    val bytes = ByteArray(mask.width * mask.height)
                    ByteBufferExtractor.extract(mask).duplicate().get(bytes)
                    return ForegroundMask.at(mask.width, mask.height, bytes, seed)
                }
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
    override fun close() { engine?.close(); engine = null }
}
