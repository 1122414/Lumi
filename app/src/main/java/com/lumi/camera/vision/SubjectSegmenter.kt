package com.lumi.camera.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenterResult
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import com.lumi.camera.guidance.Uv

// MediaPipe Android 0.10.35 omits the ROI proto's normalized flag. Its native graph
// interprets this keypoint in pixels of the bitmap passed to segment(), including crops.
internal fun magicTouchKeypoint(seed: Uv, width: Int, height: Int): NormalizedKeypoint =
    NormalizedKeypoint.create((seed.x * width).toInt().toFloat(), (seed.y * height).toInt().toFloat())

// Confined to the analysis executor, including initialization and close.
internal class SubjectSegmenter(private val context: Context) : AutoCloseable {
    private var engine: InteractiveSegmenter? = null
    // Use CPU delegate by default: magic_touch runs on a 256px frame (~35-60ms on ARM64)
    // and avoids native OpenCL/EGL SIGABRT/SIGSEGV crashes on Adreno/ColorOS/HyperOS devices.
    private var delegate = Delegate.CPU
    private var unavailable = false

    private fun task() = engine ?: InteractiveSegmenter.createFromOptions(context,
                InteractiveSegmenter.InteractiveSegmenterOptions.builder().setBaseOptions(
                    BaseOptions.builder().setModelAssetPath("magic_touch.tflite").setDelegate(delegate).build())
                    .setOutputCategoryMask(true).setOutputConfidenceMasks(false).build()).also { engine = it }
    fun prepare() {
        if (unavailable) return
        try {
            withDelegate { task() }
        } catch (t: Throwable) {
            unavailable = true
            close()
            Log.w("LumiCamera", "Subject segmenter unavailable on this device", t)
        }
    }

    private fun <T> withDelegate(runTask: () -> T): T {
        if (delegate == Delegate.CPU) return runTask()
        try {
            return runTask()
        } catch (t: Throwable) {
            Log.w("LumiCamera", "GPU segmentation unavailable; switching to CPU", t)
        }
        close()
        delegate = Delegate.CPU
        return runTask()
    }

    fun segment(pixels: SubjectPixels, seed: Uv): ForegroundMask? {
        if (unavailable) return null
        val bitmap = try {
            Bitmap.createBitmap(pixels.argb(), pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            Log.w("LumiCamera", "Failed to allocate segmentation bitmap", t)
            return null
        }
        return try {
            BitmapImageBuilder(bitmap).build().use { image ->
                val roi = InteractiveSegmenter.RegionOfInterest.create(magicTouchKeypoint(seed, pixels.width, pixels.height))
                val result: ImageSegmenterResult = withDelegate { task().segment(image, roi) }
                val categoryMask = result.categoryMask()
                if (!categoryMask.isPresent) return null
                categoryMask.get().use { mask ->
                    val bytes = ByteArray(mask.width * mask.height)
                    ByteBufferExtractor.extract(mask).duplicate().get(bytes)
                    ForegroundMask.at(mask.width, mask.height, bytes, seed)
                }
            }
        } catch (t: Throwable) {
            Log.w("LumiCamera", "Subject segmentation failed; falling back gracefully", t)
            close()
            null
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
    override fun close() { runCatching { engine?.close() }; engine = null }
}
