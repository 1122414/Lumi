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
import com.lumi.camera.guidance.FacePosition
import com.lumi.camera.guidance.Observation
import com.lumi.camera.guidance.RotationGuidance
import com.lumi.camera.guidance.Uv

internal data class FaceBox(val left: Double, val top: Double, val right: Double, val bottom: Double)
internal data class SceneFrame(val sequence: Long, val capturedNs: Long, val geometry: FrameGeometry,
    val observation: Observation?, val box: FaceBox?, val background: Background?)

internal class CompositionAnalyzer(private val optics: CameraOptics, private val poses: CameraPose,
    private val deliver: (SceneFrame) -> Unit) : ImageAnalysis.Analyzer, AutoCloseable {
    private val detector = FaceDetection.getClient(FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).enableTracking().build())
    private var reportedFailure = false

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
                    if (face == null || id == null) empty else {
                        val bounds = face.boundingBox
                        val size = frame.referenceSize
                        val p = frame.uprightToReference(Point(bounds.left.toFloat(), bounds.top.toFloat()))
                        val box = FaceBox(p.x.toDouble()/size.width, p.y.toDouble()/size.height,
                            (p.x + bounds.width()).toDouble()/size.width, (p.y + bounds.height()).toDouble()/size.height)
                        val center = Uv((box.left + box.right)/2, (box.top + box.bottom)/2)
                        if (center.x !in 0.0..1.0 || center.y !in 0.0..1.0 || box.right <= box.left) empty
                        else SceneFrame(ts, captured, frame,
                            Observation(captured, pose, lens, FacePosition(id, center, box.right - box.left)),
                            box, Background.read(image, frame))
                    }
                }
            } catch (e: Exception) {
                if (!reportedFailure) Log.w("LumiCamera", "Composition observation unavailable", e)
                reportedFailure = true
                empty
            }
            deliver(result)
        } finally {
            image.close()
        }
    }

    override fun close() = detector.close()
}
