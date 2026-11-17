package com.lumi.camera.camera

import android.content.ContentValues
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.View
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.lumi.camera.guidance.Guide
import com.lumi.camera.guidance.RotationGuidance
import com.lumi.camera.guidance.RotationProjection
import com.lumi.camera.shotplan.RememberedComposition
import com.lumi.camera.vision.CompositionAnalyzer
import com.lumi.camera.vision.SceneFrame
import java.util.concurrent.Executors

internal data class Viewfinder(val canRemember: Boolean = false, val hasTarget: Boolean = false,
    val ring: Point? = null, val ready: Boolean = false)

internal class CameraSession(
    private val view: PreviewView,
    private val owner: LifecycleOwner,
    private val onFrame: (Viewfinder) -> Unit,
    private val onBound: (Boolean) -> Unit,
    private val onMessage: (String) -> Unit,
) : DefaultLifecycleObserver, DisplayManager.DisplayListener {
    private data class Binding(val size: Extent, val rotation: Int)
    private val context = view.context
    private val main = ContextCompat.getMainExecutor(context)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val future = ProcessCameraProvider.getInstance(context)
    private val displays = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private class Run(val target: RememberedComposition) {
        val guidance = RotationGuidance(target.observation)
    }
    private var run: Run? = null
    private var latest: SceneFrame? = null
    private var poses: CameraPose? = null
    private var observer: CompositionAnalyzer? = null
    private var provider: ProcessCameraProvider? = null
    private var binding: Binding? = null
    private val frames = FrameGate()
    private var uses = emptyArray<UseCase>()
    private var capture: ImageCapture? = null
    private var disposed = false
    private val expire = Runnable {
        latest = null
        run?.guidance?.lost()
        onFrame(Viewfinder(hasTarget = run != null))
    }
    private val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> bind() }

    fun attach() {
        view.addOnLayoutChangeListener(layout)
        displays.registerDisplayListener(this, handler)
        owner.lifecycle.addObserver(this)
        future.addListener({
            if (!disposed) {
                try {
                    provider = future.get()
                    bind()
                } catch (e: Exception) {
                    Log.e("LumiCamera", "Camera provider unavailable", e)
                    onMessage("相机不可用，请检查权限和后置摄像头。")
                }
            }
        }, main)
    }
    override fun onStart(owner: LifecycleOwner) = bind()
    override fun onStop(owner: LifecycleOwner) = releaseBinding()
    override fun onDisplayChanged(displayId: Int) { if (view.display?.displayId == displayId) bind() }
    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) = Unit

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun bind() {
        val p = provider ?: return
        val rotation = view.display?.rotation ?: return
        if (disposed || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) ||
            view.width <= 0 || view.height <= 0) return
        val next = Binding(Extent(view.width, view.height), rotation)
        if (next == binding) return
        val viewport = view.getViewPort(rotation) ?: return
        releaseBinding()
        val generation = frames.session
        val preview = Preview.Builder().setTargetRotation(rotation).build()
        val readings = FocalReadings()
        val analysisBuilder = ImageAnalysis.Builder().setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        Camera2Interop.Extender(analysisBuilder).setSessionCaptureCallback(readings)
        val analysis = analysisBuilder.build()
        val photo = ImageCapture.Builder().setTargetRotation(rotation).build()
        preview.setSurfaceProvider(view.surfaceProvider)
        val group = UseCaseGroup.Builder().setViewPort(viewport)
            .addUseCase(preview).addUseCase(analysis).addUseCase(photo).build()
        uses = arrayOf(preview, analysis, photo)
        try {
            val camera = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group)
            camera.cameraControl.setZoomRatio(1f)
            val sensors = CameraPose(context, rotation)
            poses = sensors
            if (!sensors.start()) onMessage("此设备的旋转矢量传感器不可用。")
            val analyzer = CompositionAnalyzer(CameraOptics(camera.cameraInfo, readings), sensors) { result ->
                main.execute {
                    if (frames.accept(generation, 0, result.sequence)) receive(result, next.size)
                }
            }
            observer = analyzer
            analysis.setAnalyzer(worker, analyzer)
            binding = next
            capture = photo
            onBound(true)
        } catch (e: RuntimeException) {
            Log.e("LumiCamera", "Unable to bind rear camera", e)
            releaseBinding()
            onMessage("未能打开后置相机，请检查相机是否可用。")
        }
    }

    private fun receive(frame: SceneFrame, size: Extent) {
        handler.removeCallbacks(expire)
        val now = SystemClock.elapsedRealtimeNanos()
        val age = now - frame.capturedNs
        if (run?.target?.geometry?.let { it != frame.geometry } == true) run = null
        if (age !in 0..RotationGuidance.FRESH_NS) {
            expire.run()
            return
        }
        latest = frame
        val current = frame.observation
        val t = run
        val guide = if (t == null || current == null || frame.background == null) {
            t?.guidance?.lost() ?: Guide(null)
        } else {
            val agrees = frame.background.agrees(t.target.marks, RotationProjection(t.target.observation, current))
            t.guidance.update(current, now, agrees)
        }
        val ring = guide.ring?.let {
            val ref = frame.geometry.referenceSize
            FrameMapping(frame.geometry, size).referenceToPreview(
                Point((it.x * ref.width).toFloat(), (it.y * ref.height).toFloat()))
        }
        onFrame(Viewfinder(current?.face != null, t != null, ring, guide.ready))
        handler.postDelayed(expire, (RotationGuidance.FRESH_NS - age) / 1_000_000L + 1)
    }

    fun rememberComposition() {
        val frame = latest ?: return
        val now = SystemClock.elapsedRealtimeNanos()
        if (now - frame.capturedNs !in 0..RotationGuidance.FRESH_NS) {
            expire.run()
            return
        }
        val capturePose = frame.observation?.pose ?: return
        if (poses?.at(now)?.degreesTo(capturePose)?.let { it <= 1.5 } != true) {
            onMessage("取景时的姿态已变化，本次未记录。")
            return
        }
        run = Run(RememberedComposition.from(frame) ?: return)
        binding?.let { receive(frame, it.size) }
    }

    fun save() {
        val photo = capture ?: return
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Lumi_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Lumi")
            }
        }
        val output = ImageCapture.OutputFileOptions.Builder(context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
        photo.takePicture(output, main, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                if (!disposed) onMessage("照片已保存到系统相册。")
            }
            override fun onError(exception: ImageCaptureException) {
                Log.w("LumiCamera", "Save failed", exception)
                if (!disposed) onMessage("保存失败，请检查相册权限和存储空间。")
            }
        })
    }

    private fun releaseBinding() {
        frames.reset()
        latest = null
        run = null
        poses?.close()
        poses = null
        handler.removeCallbacks(expire)
        uses.filterIsInstance<ImageAnalysis>().forEach { it.clearAnalyzer() }
        if (uses.isNotEmpty()) provider?.unbind(*uses)
        observer?.let { worker.execute(it::close) }
        observer = null
        uses = emptyArray()
        binding = null
        capture = null
        onBound(false)
        onFrame(Viewfinder())
    }

    fun dispose() {
        disposed = true
        owner.lifecycle.removeObserver(this)
        view.removeOnLayoutChangeListener(layout)
        displays.unregisterDisplayListener(this)
        releaseBinding()
        worker.shutdown()
    }
}
