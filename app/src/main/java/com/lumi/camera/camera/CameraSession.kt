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
import com.lumi.camera.BuildConfig
import com.lumi.camera.guidance.Guide
import com.lumi.camera.guidance.GuideEvidence
import com.lumi.camera.guidance.Uv
import com.lumi.camera.guidance.ReturnDirection
import com.lumi.camera.guidance.RotationGuidance
import com.lumi.camera.guidance.RotationProjection
import com.lumi.camera.shotplan.ModelFailure
import com.lumi.camera.shotplan.ModelReply
import com.lumi.camera.shotplan.RecommendationRequests
import com.lumi.camera.shotplan.RememberedComposition
import com.lumi.camera.shotplan.RequestToken
import com.lumi.camera.shotplan.VisionChat
import com.lumi.camera.vision.CompositionAnalyzer
import com.lumi.camera.vision.RecommendationFrame
import com.lumi.camera.vision.SubjectOutline
import com.lumi.camera.vision.SceneFrame
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

internal data class Viewfinder(val canRemember: Boolean = false, val hasTarget: Boolean = false,
    val ring: Point? = null, val ready: Boolean = false, val merged: Boolean = false, val reason: String? = null,
    val outline: List<Point?> = emptyList(), val evidence: GuideEvidence = GuideEvidence.NO_OBSERVATION)

internal class CameraSession(
    private val view: PreviewView,
    private val owner: LifecycleOwner,
    private val onFrame: (Viewfinder) -> Unit,
    private val onBound: (Boolean) -> Unit,
    private val onMessage: (String) -> Unit,
    private val onRecommendationStatus: (String?) -> Unit,
) : DefaultLifecycleObserver, DisplayManager.DisplayListener {
    private data class Binding(val size: Extent, val rotation: Int)
    private val context = view.context
    private val main = ContextCompat.getMainExecutor(context)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val network = Executors.newCachedThreadPool()
    private val requests = RecommendationRequests()
    private var call: VisionChat? = null
    private var requestGeometry: FrameGeometry? = null
    private val timeout = Runnable {
        if (requests.active != null) {
            val notice = if (call == null) "取景超时，未取得同步的画面和手机姿态。" else VisionChat.TIMEOUT_NOTICE
            cancelRecommendation()
            onRecommendationStatus(notice)
        }
    }
    private val future = ProcessCameraProvider.getInstance(context)
    private val displays = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private class Run(val target: RememberedComposition, val reason: String?) {
        val guidance = RotationGuidance(target.observation, verifyScene = target.outline != null)
        val direction = ReturnDirection(target.observation, target.backgroundSource, target.outline?.bounds?.let {
            listOf(Uv(it.left,it.top), Uv(it.right,it.top), Uv(it.right,it.bottom), Uv(it.left,it.bottom))
        }.orEmpty())
        var visual = Guide(null)
        private var lastEvidence: GuideEvidence? = null
        private var lastReady = false
        private var lastOutline = false
        private var lastLogNs = 0L
        private var windowStartNs = 0L
        private var frames = 0
        private var late = 0
        private var noObservation = 0
        private var unmatched = 0

        fun traceFrame(frame: SceneFrame, ageNs: Long, nowNs: Long) {
            if (!BuildConfig.DEBUG || target.outline == null ||
                (frame.observation != null && frame.follower !== target.outline)) return
            if (frames == 0) windowStartNs = nowNs
            frames++
            when {
                ageNs !in 0..RotationGuidance.FRESH_NS -> late++
                frame.observation == null -> noObservation++
                frame.outline == null -> unmatched++
            }
            if (nowNs - windowStartNs < 2_000_000_000L) return
            val matched = frames-late-noObservation-unmatched
            Log.d("LumiGuidance", "frames=$frames late=$late noObservation=$noObservation unmatched=$unmatched matched=$matched")
            frames = 0
            late = 0
            noObservation = 0
            unmatched = 0
        }

        fun trace(guide: Guide, outlined: Boolean, nowNs: Long) {
            if (!BuildConfig.DEBUG || (guide.evidence == lastEvidence && guide.ready == lastReady && outlined == lastOutline) ||
                nowNs - lastLogNs < 500_000_000L) return
            lastEvidence = guide.evidence
            lastOutline = outlined
            lastReady = guide.ready
            lastLogNs = nowNs
            Log.d("LumiGuidance", "evidence=${guide.evidence} ring=${guide.ring != null} ready=${guide.ready} outline=$outlined")
        }
    }
    private data class Selection(val name: String, val geometry: FrameGeometry, val outline: SubjectOutline?)
    private var selection: Selection? = null
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
    private val drawDirection = object : Runnable {
        override fun run() {
            if ((this@CameraSession.run == null && selection?.outline == null) || binding == null) return
            render()
            view.postOnAnimation(this)
        }
    }
    private val expire = Runnable {
        latest = null
        run?.let { it.visual = it.guidance.lost(GuideEvidence.STALE) }
        render()
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
            val analyzer = CompositionAnalyzer(context, CameraOptics(camera.cameraInfo, readings), sensors,
                modelFrameReady = { token, sample -> main.execute { sendRecommendation(token, sample) } }) { result ->
                main.execute {
                    if (frames.accept(generation, 0, result.sequence)) receive(result)
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

    private fun receive(frame: SceneFrame) {
        handler.removeCallbacks(expire)
        val now = SystemClock.elapsedRealtimeNanos()
        val age = now - frame.capturedNs
        if (requestGeometry?.let { it != frame.geometry } == true) {
            cancelRecommendation()
            onRecommendationStatus("取景尺寸已变化，请重新推荐。")
        }
        if ((run?.target?.geometry ?: selection?.geometry)?.let { it != frame.geometry } == true) {
            run = null
            selection = null
            onRecommendationStatus(null)
            observer?.selected = null
        }
        run?.traceFrame(frame, age, now)
        if (age !in 0..RotationGuidance.FRESH_NS) {
            expire.run()
            return
        }
        latest = frame
        val t = run
        if (t != null) {
            val current = t.target.current(frame)
            t.visual = if (current == null) t.guidance.lost() else {
                val agrees = frame.background?.agrees(t.target.marks, RotationProjection(t.target.backgroundSource, current)) == true
                t.guidance.update(current, now, agrees)
            }
        }
        render()
        handler.postDelayed(expire, (RotationGuidance.FRESH_NS - age) / 1_000_000L + 1)
    }

    private fun render() {
        val size = binding?.size ?: return
        val now = SystemClock.elapsedRealtimeNanos()
        val fresh = latest?.takeIf { now - it.capturedNs in 0..RotationGuidance.FRESH_NS }
        val current = fresh?.observation
        val active = run
        val identified = selection
        val geometry = active?.target?.geometry ?: identified?.geometry
        if (geometry == null) {
            onFrame(Viewfinder(canRemember = current?.subject != null))
            return
        }
        val visual = if (fresh != null && active != null) active.visual else Guide(null, evidence = GuideEvidence.STALE)
        val pose = poses?.latestAt(now)
        val seen = fresh?.outline?.takeIf { fresh.follower === identified?.outline }
        val guide = active?.let {
            it.direction.display(visual, fresh?.let(it.target::current), pose, seen != null)
        } ?: Guide(null)
        if (active != null && guide.evidence == GuideEvidence.RETURN_DIRECTION && (visual.ready || visual.merged)) {
            active.visual = active.guidance.lost()
        }
        val ref = geometry.referenceSize
        val mapping = FrameMapping(geometry, size)
        fun preview(p: Uv) = mapping.referenceToPreview(Point((p.x * ref.width).toFloat(), (p.y * ref.height).toFloat()))
        val outline = if (seen == null || current == null) emptyList() else {
            val projection = pose?.let { RotationProjection(current, current.copy(pose = it)) }
            seen.points.map { point ->
                val p = if (projection != null && point != null) projection.project(point) else point
                p?.let(::preview)
            }
        }
        active?.trace(guide, outline.any { it != null }, now)
        onFrame(Viewfinder(current?.subject != null, active != null, guide.ring?.let(::preview), guide.ready,
            guide.merged, active?.reason ?: identified?.name, outline, guide.evidence))
    }

    private fun startGuide(target: RememberedComposition, reason: String? = null) {
        observer?.selected = target.outline
        run = Run(target, reason)
        view.removeCallbacks(drawDirection)
        view.postOnAnimation(drawDirection)
        val frame = latest
        if (frame == null) render() else receive(frame)
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
        val target = RememberedComposition.from(frame) ?: return
        cancelRecommendation()
        onRecommendationStatus(null)
        selection = null
        startGuide(target)
    }

    fun recommend() {
        if (binding == null || VisionChat.unavailableReason != null) return
        val now = SystemClock.elapsedRealtimeNanos()
        cancelRecommendation()
        run = null
        selection = null
        observer?.selected = null
        onFrame(Viewfinder(canRemember = latest?.observation?.subject != null))
        val token = requests.begin(now)
        observer?.pendingFrame?.await(token)
        onRecommendationStatus("正在取景…")
        handler.postDelayed(timeout, (token.deadlineNs - now) / 1_000_000L)
    }

    private fun sendRecommendation(token: RequestToken, sample: RecommendationFrame) {
        val source = sample.scene
        val now = SystemClock.elapsedRealtimeNanos()
        if (requests.active != token || now >= token.deadlineNs) {
            sample.close()
            return
        }
        if (now - source.capturedNs !in 0..RotationGuidance.FRESH_NS) {
            sample.close()
            observer?.pendingFrame?.await(token)
            return
        }
        val task = VisionChat(token)
        call = task
        requestGeometry = source.geometry
        onRecommendationStatus("正在推荐构图…")
        network.execute {
            var sentAtMs = 0L
            val result = try {
                val jpeg = sample.use { task.remainingMs(); it.jpeg() }
                sentAtMs = SystemClock.elapsedRealtime()
                Result.success(task.send(jpeg))
            } catch (e: Exception) { Result.failure(e) }
            val elapsed = SystemClock.elapsedRealtime() - sentAtMs
            main.execute select@{
                if (requests.active != token) return@select
                val analyzer = observer
                val subject = result.getOrNull()?.subject
                if (analyzer == null || subject == null) {
                    finishRecommendation(token, source, result, elapsed, null)
                } else worker.execute {
                    val outline = try { analyzer.select(source, subject) } catch (e: RuntimeException) {
                        Log.w("LumiCamera", "Subject segmentation unavailable", e)
                        null
                    }
                    main.execute { finishRecommendation(token, source, result, elapsed, outline) }
                }
            }
        }
    }

    private fun finishRecommendation(token: RequestToken, source: SceneFrame,
        result: Result<ModelReply>, elapsedMs: Long, outline: SubjectOutline?) {
        val completion = requests.finish(token, SystemClock.elapsedRealtimeNanos())
        if (completion == RecommendationRequests.Completion.OBSOLETE) return
        handler.removeCallbacks(timeout)
        call = null
        requestGeometry = null
        if (completion == RecommendationRequests.Completion.EXPIRED) {
            onRecommendationStatus(VisionChat.TIMEOUT_NOTICE)
            return
        }
        val reply = result.getOrElse {
            onRecommendationStatus(when (it) {
                is ModelFailure -> it.notice
                is SocketTimeoutException -> VisionChat.TIMEOUT_NOTICE
                else -> "推荐请求失败，请检查连接后重试。"
            })
            return
        }
        selection = Selection(reply.name, source.geometry, outline)
        val plan = reply.plan
        val target = if (plan != null && outline != null) RememberedComposition.from(source, plan, outline) else null
        if (plan == null || target == null) {
            observer?.selected = outline
            view.removeCallbacks(drawDirection)
            if (outline != null) view.postOnAnimation(drawDirection)
            Log.i("LumiRecommendation", "modelMs=$elapsedMs identified=true modelOutline=${reply.subject != null} " +
                "modelPlan=${plan != null} outline=${outline != null} plan=false")
            onRecommendationStatus(if (outline == null) "主体边缘暂未确认" else "已识别主体，暂无可信构图")
            render()
            return
        }
        Log.i("LumiRecommendation", "modelMs=$elapsedMs aimPoint=(${plan.aimPoint.x},${plan.aimPoint.y}) " +
            "confidence=${plan.confidence} alreadyGood=${plan.alreadyGood} reason=" + VisionChat.logReason(plan.reason))
        onRecommendationStatus(null)
        startGuide(target, plan.caption)
    }

    private fun cancelRecommendation() {
        requests.cancel()
        observer?.pendingFrame?.await(null)
        handler.removeCallbacks(timeout)
        call?.cancel(network)
        call = null
        requestGeometry = null
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
        view.removeCallbacks(drawDirection)
        frames.reset()
        cancelRecommendation()
        onRecommendationStatus(null)
        latest = null
        run = null
        selection = null
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
        network.shutdown()
    }
}
