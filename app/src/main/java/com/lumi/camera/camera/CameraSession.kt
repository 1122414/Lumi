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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

internal class CameraSession(
    private val view: PreviewView,
    private val owner: LifecycleOwner,
    private val onFrame: (AnalysisResult?) -> Unit,
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
    private val selection = AtomicReference(Tap(0, null))
    private var provider: ProcessCameraProvider? = null
    private var binding: Binding? = null
    private val frames = FrameGate()
    private var uses = emptyArray<UseCase>()
    private var capture: ImageCapture? = null
    private var disposed = false
    private val expire = Runnable { onFrame(null) }
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

    private fun bind() {
        val p = provider ?: return
        val rotation = view.display?.rotation ?: return
        if (disposed || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) ||
            view.width <= 0 || view.height <= 0) return
        val next = Binding(Extent(view.width, view.height), rotation)
        if (next == binding) return
        val viewport = view.getViewPort(rotation) ?: return
        releaseBinding()
        val run = frames.session
        val preview = Preview.Builder().setTargetRotation(rotation).build()
        val analysis = ImageAnalysis.Builder().setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        val photo = ImageCapture.Builder().setTargetRotation(rotation).build()
        preview.setSurfaceProvider(view.surfaceProvider)
        analysis.setAnalyzer(worker, FrameAnalyzer(next.size, selection) { result ->
            main.execute {
                if (frames.accept(run, result.tapId, result.frame.timestampNs)) {
                    handler.removeCallbacks(expire)
                    val remaining = 300L - (SystemClock.elapsedRealtime() - result.startedAtMs)
                    onFrame(if (remaining > 0) result else null)
                    if (remaining > 0 && result.marker != null) handler.postDelayed(expire, remaining)
                }
            }
        })
        val group = UseCaseGroup.Builder().setViewPort(viewport)
            .addUseCase(preview).addUseCase(analysis).addUseCase(photo).build()
        uses = arrayOf(preview, analysis, photo)
        try {
            val camera = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group)
            camera.cameraControl.setZoomRatio(1f)
            binding = next
            capture = photo
            onBound(true)
        } catch (e: RuntimeException) {
            Log.e("LumiCamera", "Unable to bind rear camera", e)
            releaseBinding()
            onMessage("未能打开后置相机，请检查相机是否可用。")
        }
    }

    fun select(point: Point) {
        if (binding == null) return
        selection.set(Tap(frames.select(), point))
        handler.removeCallbacks(expire)
        onFrame(null)
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
        selection.set(Tap(0, null))
        handler.removeCallbacks(expire)
        uses.filterIsInstance<ImageAnalysis>().forEach { it.clearAnalyzer() }
        if (uses.isNotEmpty()) provider?.unbind(*uses)
        uses = emptyArray()
        binding = null
        capture = null
        onBound(false)
        onFrame(null)
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
