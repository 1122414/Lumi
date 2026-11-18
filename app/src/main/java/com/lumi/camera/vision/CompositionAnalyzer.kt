package com.lumi.camera.vision

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.lumi.camera.camera.CameraOptics
import com.lumi.camera.camera.CameraPose
import com.lumi.camera.camera.Crop
import com.lumi.camera.camera.Extent
import com.lumi.camera.camera.FrameGeometry
import com.lumi.camera.camera.Point
import com.lumi.camera.guidance.SubjectPosition
import com.lumi.camera.guidance.Observation
import com.lumi.camera.guidance.RotationGuidance
import com.lumi.camera.guidance.Uv
import java.util.concurrent.atomic.AtomicReference

internal data class FaceBox(val left: Double, val top: Double, val right: Double, val bottom: Double)
internal data class SceneFrame(val sequence: Long, val capturedNs: Long, val geometry: FrameGeometry,
    val observation: Observation?, val box: FaceBox?, val background: Background?,
    val pixels: SubjectPixels? = null, val follower: SubjectOutline? = null, val outline: SeenOutline? = null)

internal class CompositionAnalyzer(private val optics: CameraOptics, private val poses: CameraPose,
    private val keepModelFrame: Boolean, private val deliver: (SceneFrame) -> Unit) : ImageAnalysis.Analyzer, AutoCloseable {
    private val detector = FaceDetection.getClient(FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).enableTracking().build())
    private var reportedFailure = false
    private val modelFrame = AtomicReference<RecommendationFrame?>()

    @Volatile var selected: SubjectOutline? = null

    // Taking transfers ownership; the analysis worker can immediately replace the slot.
    fun takeModelFrame(): RecommendationFrame? = modelFrame.getAndSet(null)

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        try {
            val ts = image.imageInfo.timestamp
            val captured = optics.captureTimeNs(ts)
            val r = image.cropRect
            val frame = FrameGeometry(Extent(image.width, image.height), Crop(r.left, r.top, r.right, r.bottom),
                image.imageInfo.rotationDegrees)
            val empty = SceneFrame(ts, captured, frame, null, null, null)
            val result = try {
                val pose = poses.at(captured)
                val lens = optics.lens(image, frame)
                val media = image.image
                if (media == null || pose == null || lens == null ||
                    SystemClock.elapsedRealtimeNanos() - captured !in 0..RotationGuidance.FRESH_NS) empty else {
                    // The single CameraX analysis worker owns the proxy until ML Kit finishes.
                    val faces = Tasks.await(detector.process(InputImage.fromMediaImage(media, frame.rotation)))
                    val face = faces.singleOrNull()
                    val id = face?.trackingId
                    val box = if (face != null && id != null) {
                        val bounds = face.boundingBox
                        val size = frame.referenceSize
                        val p = frame.uprightToReference(Point(bounds.left.toFloat(), bounds.top.toFloat()))
                        FaceBox(p.x.toDouble()/size.width, p.y.toDouble()/size.height,
                            (p.x + bounds.width()).toDouble()/size.width, (p.y + bounds.height()).toDouble()/size.height)
                    } else null
                    val detected = box?.takeIf { it.right > it.left && it.bottom > it.top }?.let {
                        val center = Uv((it.left + it.right)/2, (it.top + it.bottom)/2)
                        if (center.x in 0.0..1.0 && center.y in 0.0..1.0) SubjectPosition(id!!, center, it.right - it.left) else null
                    }
                    val observation = Observation(captured, pose, lens, detected)
                    val follower = selected
                    val pixels = if (keepModelFrame || follower != null) SubjectPixels.read(image, frame) else null
                    val outline = if (follower != null && pixels != null) follower.observe(observation, pixels) else null
                    val background = if (keepModelFrame || detected != null) Background.read(image, frame) else null
                    SceneFrame(ts, captured, frame, observation, box, background, pixels, follower, outline)
                }
            } catch (e: Exception) {
                if (!reportedFailure) Log.w("LumiCamera", "Composition observation unavailable", e)
                reportedFailure = true
                empty
            }
            if (keepModelFrame) {
                val next = if (result.pixels != null &&
                    SystemClock.elapsedRealtimeNanos() - captured in 0..RotationGuidance.FRESH_NS) {
                    try { RecommendationFrame(result, image.toBitmap()) } catch (_: RuntimeException) { null }
                } else null
                modelFrame.getAndSet(next)?.close()
            }
            deliver(result)
        } finally {
            image.close()
        }
    }

    override fun close() {
        takeModelFrame()?.close()
        detector.close()
    }
}
