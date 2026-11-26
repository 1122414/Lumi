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
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.lumi.camera.BuildConfig
import com.lumi.camera.guidance.GuideEvidence
import com.lumi.camera.guidance.Lens
import com.lumi.camera.guidance.Observation
import com.lumi.camera.guidance.ParallaxStepTracker
import com.lumi.camera.guidance.PoseGuide
import com.lumi.camera.guidance.PoseGuidance
import com.lumi.camera.guidance.RotationGuidance
import com.lumi.camera.guidance.RotationProjection
import com.lumi.camera.guidance.StepProgress
import com.lumi.camera.guidance.Uv
import com.lumi.camera.shotplan.AestheticPreferenceStore
import com.lumi.camera.shotplan.CompositionChoice
import com.lumi.camera.shotplan.CompositionDiagnostic
import com.lumi.camera.shotplan.CompositionPreset
import com.lumi.camera.shotplan.ConnectionResult
import com.lumi.camera.shotplan.DiagnosticInput
import com.lumi.camera.shotplan.DirectorCue
import com.lumi.camera.shotplan.ModelConfig
import com.lumi.camera.shotplan.ModelFailure
import com.lumi.camera.shotplan.ModelReply
import com.lumi.camera.shotplan.PlanProblem
import com.lumi.camera.shotplan.PlanResult
import com.lumi.camera.shotplan.PresetSelection
import com.lumi.camera.shotplan.ProactiveTrigger
import com.lumi.camera.shotplan.ProactivityMode
import com.lumi.camera.shotplan.RecommendationRequests
import com.lumi.camera.shotplan.RememberedComposition
import com.lumi.camera.shotplan.RequestToken
import com.lumi.camera.shotplan.SubjectCandidate
import com.lumi.camera.shotplan.VisionChat
import com.lumi.camera.vision.CompositionAnalyzer
import com.lumi.camera.vision.OutlineAttempt
import com.lumi.camera.vision.PostShotRecrop
import com.lumi.camera.vision.PostShotReview
import com.lumi.camera.vision.RecommendationFrame
import com.lumi.camera.vision.SubjectOutline
import com.lumi.camera.vision.SubjectTracker
import com.lumi.camera.vision.SceneFrame
import com.lumi.camera.vision.TrackedShift
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

internal data class CandidateOverlay(
    val id: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val userPicked: Boolean = false,
)

internal data class Viewfinder(
    val canRemember: Boolean = false,
    val hasTarget: Boolean = false,
    val ring: Point? = null,
    val ready: Boolean = false,
    val merged: Boolean = false,
    val reason: String? = null,
    val outline: List<Point?> = emptyList(),
    val evidence: GuideEvidence = GuideEvidence.NO_OBSERVATION,
    val rollDeg: Double? = null,
    val suggestedZoom: Float? = null,
    val candidates: List<CandidateOverlay> = emptyList(),
    val tapPoint: Point? = null,
    val viewpointDrifted: Boolean = false,
    val directorCue: DirectorCue? = null,
    val stepProgress: StepProgress? = null,
)

internal data class ViewfinderZoom(val ratio: Float = 1f, val min: Float = 1f, val max: Float = 1f,
    val changing: Boolean = false, val keyRatios: List<Float> = listOf(1f))

internal data class ViewfinderLens(val front: Boolean = false, val canSwitch: Boolean = false)

internal class CameraSession(
    private val view: PreviewView,
    private val owner: LifecycleOwner,
    private val onFrame: (Viewfinder) -> Unit,
    private val onBound: (Boolean) -> Unit,
    private val onMessage: (String) -> Unit,
    private val onRecommendationStatus: (String?) -> Unit,
    initialConfig: ModelConfig,
    private val onConnection: (ConnectionResult) -> Unit,
    private val onZoom: (ViewfinderZoom) -> Unit,
    private val onLens: (ViewfinderLens) -> Unit = {},
    private val onPreset: (PresetSelection) -> Unit = {},
    private val onCaptured: (PostShotReview) -> Unit = {},
    initialProactivity: ProactivityMode = ProactivityMode.MANUAL,
) : DefaultLifecycleObserver, DisplayManager.DisplayListener {
    private data class Binding(val size: Extent, val rotation: Int, val lensFacing: Int)
    private val context = view.context
    private val main = ContextCompat.getMainExecutor(context)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val network = Executors.newCachedThreadPool()
    private val requests = RecommendationRequests(initialConfig)
    private var call: VisionChat? = null
    private var requestGeometry: FrameGeometry? = null
    private val timeout = Runnable {
        if (requests.active != null) {
            val notice = if (requestGeometry == null) "取景超时，未取得同步的画面和手机姿态。" else VisionChat.TIMEOUT_NOTICE
            if (call != null) onConnection(ConnectionResult.UNREACHABLE)
            cancelRecommendation()
            onRecommendationStatus(notice)
        }
    }
    private val future = ProcessCameraProvider.getInstance(context)
    private val displays = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private class Run(var target: RememberedComposition, val originalReason: String?, val subjectName: String? = null) {
        var reason: String? = originalReason
        var onFirstRing: (() -> Unit)? = null
        val guidance = PoseGuidance(target.observation)
        private var lastReady = false
        private var lastOffscreen = false
        private var lastOutline = false
        private var lastLogNs = 0L

        fun trace(guide: PoseGuide, outlined: Boolean, nowNs: Long) {
            if (!BuildConfig.DEBUG || (guide.offscreen == lastOffscreen && guide.ready == lastReady && outlined == lastOutline) ||
                nowNs - lastLogNs < 500_000_000L) return
            lastOffscreen = guide.offscreen
            lastOutline = outlined
            lastReady = guide.ready
            lastLogNs = nowNs
            Log.d("LumiGuidance", "ring=${guide.ring != null} offscreen=${guide.offscreen} ready=${guide.ready} angle=${"%.1f".format(guide.angleDeg)} outline=$outlined")
        }
    }
    private data class Selection(val name: String, val geometry: FrameGeometry, val source: Observation?, val outline: SubjectOutline?)
    private var presetSelection = PresetSelection()
    private var proactivityMode = initialProactivity
    private val proactiveTrigger = ProactiveTrigger()
    private val aestheticPrefs = AestheticPreferenceStore(context)
    private var lastReadyState = false
    private var selection: Selection? = null
    private var run: Run? = null
    private var pendingCandidates: List<SubjectCandidate> = emptyList()
    private var pendingCandidateGeometry: FrameGeometry? = null
    private var pendingTapPreview: Point? = null
    private var latest: SceneFrame? = null
    private var latestLens: Lens? = null
    private var latestLensZoom = 1f
    private var latestShift: TrackedShift? = null
    private var latestStepProgress: StepProgress? = null
    private var trackerLostSinceNs: Long? = null
    private var subjectLostNotified = false
    private var poses: CameraPose? = null
    private var observer: CompositionAnalyzer? = null
    private var provider: ProcessCameraProvider? = null
    private var binding: Binding? = null
    private val frames = FrameGate()
    private var uses = emptyArray<UseCase>()
    private var capture: ImageCapture? = null
    private var camera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var canSwitchLens = false
    private var zoomObserver: Observer<ZoomState>? = null
    private val readings = FocalReadings()
    private var zoom = ViewfinderZoom()
    private var appliedZoom = 1f
    private var desiredZoom = 1f
    private var zoomGeneration = 0L
    private var zoomSinceNs = 0L
    private var zoomSettleUntilNs = 0L
    private var logZoomFrame = true
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
                    val cameraProvider = future.get()
                    provider = cameraProvider
                    val hasBack = runCatching { cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)
                    val hasFront = runCatching { cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)
                    if (!hasBack && hasFront) lensFacing = CameraSelector.LENS_FACING_FRONT
                    canSwitchLens = hasBack && hasFront
                    onLens(ViewfinderLens(lensFacing == CameraSelector.LENS_FACING_FRONT, canSwitchLens))
                    bind()
                } catch (e: Exception) {
                    Log.e("LumiCamera", "Camera provider unavailable", e)
                    onMessage("相机不可用，请检查权限和摄像头。")
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
        val next = Binding(Extent(view.width, view.height), rotation, lensFacing)
        if (next == binding) return
        val viewport = view.getViewPort(rotation) ?: return
        releaseBinding()
        val generation = frames.session
        val front = lensFacing == CameraSelector.LENS_FACING_FRONT
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val preview = Preview.Builder().setTargetRotation(rotation).build()
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
            val camera = p.bindToLifecycle(owner, selector, group)
            this.camera = camera
            appliedZoom = 1f
            val sensors = CameraPose(context, rotation)
            poses = sensors
            if (!sensors.start()) onMessage("此设备的旋转矢量传感器不可用。")
            val analyzer = CompositionAnalyzer(context, CameraOptics(camera.cameraInfo, readings), sensors,
                mirrored = front,
                modelFrameReady = { token, sample -> main.execute { sendRecommendation(token, sample) } }) { result ->
                main.execute {
                    if (frames.accept(generation, 0, result.sequence)) receive(result)
                }
            }
            observer = analyzer
            analyzer.proactiveMonitor = proactivityMode == ProactivityMode.PROACTIVE
            analysis.setAnalyzer(worker, analyzer)
            binding = next
            capture = photo
            val lensStops = rearZoomStops(context, camera.cameraInfo)
            val ranges = Observer<ZoomState> { range ->
                if (this.camera === camera && (zoom.min != range.minZoomRatio || zoom.max != range.maxZoomRatio)) {
                    zoom = zoom.copy(min = range.minZoomRatio, max = range.maxZoomRatio,
                        keyRatios = lensStops.filter { it in range.minZoomRatio..range.maxZoomRatio })
                    onZoom(zoom)
                    setZoom(desiredZoom)
                }
            }
            zoomObserver = ranges
            camera.cameraInfo.zoomState.observe(owner, ranges)
            onLens(ViewfinderLens(front, canSwitchLens))
            onBound(true)
        } catch (e: RuntimeException) {
            Log.e("LumiCamera", "Unable to bind ${if (front) "front" else "rear"} camera", e)
            releaseBinding()
            onMessage(if (front) "未能打开前置相机，请检查相机是否可用。" else "未能打开后置相机，请检查相机是否可用。")
        }
    }

    fun switchCamera() {
        val p = provider ?: return
        if (!canSwitchLens || zoom.changing) return
        val nextFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        val selector = if (nextFacing == CameraSelector.LENS_FACING_FRONT)
            CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        if (!runCatching { p.hasCamera(selector) }.getOrDefault(false)) return
        lensFacing = nextFacing
        desiredZoom = 1f
        onLens(ViewfinderLens(lensFacing == CameraSelector.LENS_FACING_FRONT, canSwitchLens))
        bind()
    }

    private fun liveLens(fallback: Lens?): Lens? {
        val base = latestLens ?: fallback ?: return null
        val scale = (zoom.ratio / latestLensZoom).toDouble()
        if (!scale.isFinite() || scale <= 0.0 || abs(scale - 1.0) < 0.005) return base
        return base.copy(fx = base.fx * scale, fy = base.fy * scale)
    }

    private fun receive(frame: SceneFrame) {
        if (zoom.changing || frame.capturedNs < zoomSinceNs) return
        if (frame.observation != null) {
            latestLens = frame.observation.lens
            latestLensZoom = appliedZoom
            if (logZoomFrame && BuildConfig.DEBUG) {
                Log.d("LumiZoom", "ratio=${zoom.ratio} min=${zoom.min} max=${zoom.max} fx=${frame.observation.lens.fx}")
                logZoomFrame = false
            }
        }
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
            latestShift = null
            latestStepProgress = null
            trackerLostSinceNs = null
            subjectLostNotified = false
            onRecommendationStatus(null)
            observer?.apply { watchPerson = false; tracker = null; stepTracker = null }
        }
        if (age !in 0..RotationGuidance.FRESH_NS) {
            expire.run()
            return
        }
        latest = frame
        val active = run
        val obs = frame.observation
        var faceTracked = false
        if (active?.target?.outline?.person == true && obs != null) {
            val anchorFace = active.target.backgroundSource.subject
            if (anchorFace != null) {
                val predicted = RotationProjection(active.target.backgroundSource, obs).project(anchorFace.center)
                if (predicted != null) {
                    val lensScale = obs.lens.fx / active.target.backgroundSource.lens.fx
                    val liveCenter = RememberedComposition.matchLiveFace(anchorFace, predicted, frame.trackedFaces, lensScale)
                    if (liveCenter != null) {
                        val rawDx = liveCenter.x - predicted.x
                        val rawDy = liveCenter.y - predicted.y
                        val prev = latestShift
                        val dx = if (prev != null) {
                            val initDx = prev.dx * (obs.lens.fx / prev.lens.fx)
                            initDx + 0.55 * (rawDx - initDx)
                        } else rawDx
                        val dy = if (prev != null) {
                            val initDy = prev.dy * (obs.lens.fy / prev.lens.fy)
                            initDy + 0.55 * (rawDy - initDy)
                        } else rawDy
                        latestShift = TrackedShift(dx, dy, obs.lens, obs.timeNs, matched = true)
                        trackerLostSinceNs = null
                        if (subjectLostNotified) {
                            subjectLostNotified = false
                            onRecommendationStatus(null)
                        }
                        faceTracked = true
                    }
                }
            }
        }
        val shift = frame.shift
        if (!faceTracked && shift != null && now >= zoomSettleUntilNs) {
            if (shift.matched) {
                latestShift = shift
                trackerLostSinceNs = null
                if (subjectLostNotified) {
                    subjectLostNotified = false
                    onRecommendationStatus(null)
                }
            } else if (trackerLostSinceNs == null) {
                trackerLostSinceNs = now
            }
        }
        if (active?.target?.stepDirection != null && frame.stepProgress != null) {
            latestStepProgress = frame.stepProgress
        }
        render()
        if (proactivityMode == ProactivityMode.PROACTIVE &&
            requests.config.validationError(BuildConfig.DEBUG) == null) {
            val trigger = proactiveTrigger.evaluate(
                nowNs = now,
                pose = poses?.latestAt(now) ?: frame.observation?.pose,
                rollDeg = (poses?.latestAt(now) ?: frame.observation?.pose)?.horizonRollDeg(),
                faces = frame.faces,
                meanLuma = frame.meanLuma,
                hasActiveGuidance = run != null,
                isRequestBusy = requests.active != null,
            )
            if (trigger != null) {
                recommend(null, null, trigger.statusHint)
            }
        }
        handler.postDelayed(expire, (RotationGuidance.FRESH_NS - age) / 1_000_000L + 1)
    }

    private fun render() {
        val size = binding?.size ?: return
        val now = SystemClock.elapsedRealtimeNanos()
        val fresh = latest?.takeIf { now - it.capturedNs in 0..RotationGuidance.FRESH_NS }
        val current = fresh?.observation
        val active = run
        val identified = selection
        val pose = poses?.latestAt(now) ?: current?.pose
        val rollDeg = pose?.horizonRollDeg()
        val candidateOverlays = pendingCandidateGeometry?.let { candGeom ->
            if (pendingCandidates.isEmpty()) emptyList() else {
                val candRef = candGeom.referenceSize
                val candMap = FrameMapping(candGeom, size)
                pendingCandidates.map { c ->
                    val tl = candMap.referenceToPreview(Point((c.box.left * candRef.width).toFloat(), (c.box.top * candRef.height).toFloat()))
                    val br = candMap.referenceToPreview(Point((c.box.right * candRef.width).toFloat(), (c.box.bottom * candRef.height).toFloat()))
                    CandidateOverlay(c.id, minOf(tl.x, br.x), minOf(tl.y, br.y), maxOf(tl.x, br.x), maxOf(tl.y, br.y), c.userPicked)
                }
            }
        }.orEmpty()
        val geometry = active?.target?.geometry ?: identified?.geometry
        if (geometry == null) {
            onFrame(Viewfinder(
                canRemember = current != null,
                rollDeg = rollDeg,
                candidates = candidateOverlays,
                tapPoint = pendingTapPreview,
            ))
            return
        }
        val lens = liveLens(active?.target?.observation?.lens ?: identified?.source?.lens)
        val expectedCenter = if (active != null && pose != null && lens != null) {
            active.target.predictedSubjectCenter(pose, lens, latestShift)
        } else null
        val expectedInView = expectedCenter != null &&
            expectedCenter.x in 0.05..0.95 && expectedCenter.y in 0.05..0.95
        val subjectLost = active?.target?.outline != null && expectedInView &&
            trackerLostSinceNs?.let { now - it >= SUBJECT_LOST_NS } == true
        if (subjectLost) {
            active?.guidance?.resetHold()
            if (!subjectLostNotified) {
                subjectLostNotified = true
                onRecommendationStatus("未检测到主体，移回画面或重新推荐")
            }
        } else if (subjectLostNotified && !expectedInView) {
            subjectLostNotified = false
            onRecommendationStatus(null)
        }
        val guide = if (active != null && pose != null && lens != null && !subjectLost) {
            val liveTarget = active.target.targetFor(pose, lens, latestShift)
            active.guidance.update(pose, lens, now, liveTarget)
        } else PoseGuide(null, rollDeg = rollDeg)
        if (guide.ring != null && active != null) {
            active.onFirstRing?.invoke()
            active.onFirstRing = null
        }
        val ref = geometry.referenceSize
        val mapping = FrameMapping(geometry, size)
        fun preview(p: Uv) = mapping.referenceToPreview(Point((p.x * ref.width).toFloat(), (p.y * ref.height).toFloat()))
        val outlineSource = active?.target?.outline ?: identified?.outline
        val outline = if (outlineSource == null || pose == null || lens == null || subjectLost) emptyList() else {
            val liveObs = outlineSource.sourceObservation.copy(timeNs = now, pose = pose, lens = lens)
            val projection = RotationProjection(outlineSource.sourceObservation, liveObs)
            val shift = latestShift
            val shiftDx = if (shift != null) shift.dx * (lens.fx / shift.lens.fx) else 0.0
            val shiftDy = if (shift != null) shift.dy * (lens.fy / shift.lens.fy) else 0.0
            val apparentScale = (shift?.scale ?: 1.0).coerceIn(0.55, 1.55)
            val ob = outlineSource.bounds
            val anchorCenter = projection.project(Uv((ob.left + ob.right) / 2.0, (ob.top + ob.bottom) / 2.0))
            outlineSource.capturePoints.map { point ->
                point?.let(projection::project)?.let { p ->
                    val scaled = if (anchorCenter != null && abs(apparentScale - 1.0) > 0.01) {
                        Uv(
                            anchorCenter.x + (p.x - anchorCenter.x) * apparentScale,
                            anchorCenter.y + (p.y - anchorCenter.y) * apparentScale,
                        )
                    } else p
                    preview(Uv(scaled.x + shiftDx, scaled.y + shiftDy))
                }
            }
        }
        active?.trace(guide, outline.any { it != null }, now)
        val evidence = when {
            active == null -> GuideEvidence.NO_OBSERVATION
            guide.ring == null -> GuideEvidence.STALE
            guide.ready -> GuideEvidence.CONFIRMED
            guide.offscreen -> GuideEvidence.RETURN_DIRECTION
            else -> GuideEvidence.VERIFYING_COMPOSITION
        }
        val activeSuggestedZoom = active?.target?.suggestedZoom?.takeIf { targetZoom ->
            abs(zoom.ratio - targetZoom) > 0.15f && targetZoom in zoom.min..zoom.max
        }
        val viewpointDrifted = active != null && pose != null && lens != null &&
            active.target.isViewpointDrifted(pose, lens)
        lastReadyState = guide.ready
        onFrame(Viewfinder(
            canRemember = current != null,
            hasTarget = active != null,
            ring = guide.ring?.let(::preview),
            ready = guide.ready,
            merged = guide.ready,
            reason = active?.reason ?: identified?.name,
            outline = outline,
            evidence = evidence,
            rollDeg = guide.rollDeg ?: rollDeg,
            suggestedZoom = activeSuggestedZoom,
            candidates = candidateOverlays,
            tapPoint = pendingTapPreview,
            viewpointDrifted = viewpointDrifted,
            directorCue = active?.target?.directorCue,
            stepProgress = if (active?.target?.stepDirection != null) latestStepProgress else null,
        ))
    }

    private fun startGuide(target: RememberedComposition, reason: String? = null, subjectName: String? = null,
        firstRing: (() -> Unit)? = null) {
        observer?.watchPerson = target.outline?.person == true
        run = Run(target, reason, subjectName).also { it.onFirstRing = firstRing }
        view.removeCallbacks(drawDirection)
        view.postOnAnimation(drawDirection)
        render()
    }

    fun selectPreset(preset: CompositionPreset) {
        val active = run
        val nextThird = when {
            preset != CompositionPreset.RULE_OF_THIRDS -> presetSelection.thirdChoice
            presetSelection.preset == CompositionPreset.RULE_OF_THIRDS ->
                if (presetSelection.thirdChoice == CompositionChoice.LEFT_THIRD) CompositionChoice.RIGHT_THIRD else CompositionChoice.LEFT_THIRD
            active?.target?.originalChoice == CompositionChoice.LEFT_THIRD -> CompositionChoice.LEFT_THIRD
            active?.target?.originalChoice == CompositionChoice.RIGHT_THIRD -> CompositionChoice.RIGHT_THIRD
            else -> presetSelection.thirdChoice
        }
        presetSelection = PresetSelection(preset, nextThird)
        onPreset(presetSelection)
        if (active != null && active.target.outline != null) {
            val now = SystemClock.elapsedRealtimeNanos()
            val pose = poses?.latestAt(now) ?: latest?.observation?.pose ?: active.target.backgroundSource.pose
            val lens = liveLens(active.target.observation.lens) ?: active.target.observation.lens
            val updated = active.target.withPreset(presetSelection, pose, lens, latestShift)
            active.target = updated
            active.reason = if (preset == CompositionPreset.AUTO) {
                active.originalReason
            } else {
                val label = updated.choice?.label ?: preset.label
                active.subjectName?.let { "$it·$label" } ?: label
            }
            active.guidance.resetHold()
            if (preset != CompositionPreset.AUTO) {
                aestheticPrefs.recordComposition(updated.choice, zoom.ratio)
            }
            render()
        }
    }

    fun recordRecropPreference(proposalId: String) {
        aestheticPrefs.recordRecrop(proposalId)
    }

    fun preferenceSummary(): String = aestheticPrefs.read().summaryLabel()

    fun resetPreferences() {
        aestheticPrefs.clear()
        onMessage("已重置本地构图偏好记忆")
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
        val target = RememberedComposition.remembered(frame) ?: return
        cancelRecommendation()
        onRecommendationStatus(null)
        selection = null
        latestShift = null
        latestStepProgress = null
        trackerLostSinceNs = null
        subjectLostNotified = false
        observer?.apply { tracker = null; stepTracker = null }
        startGuide(target)
    }

    fun useModelConfig(config: ModelConfig) {
        cancelRecommendation()
        requests.useConfig(config)
        view.removeCallbacks(drawDirection)
        run = null
        selection = null
        latestShift = null
        latestStepProgress = null
        trackerLostSinceNs = null
        subjectLostNotified = false
        observer?.apply { watchPerson = false; tracker = null; stepTracker = null }
        onRecommendationStatus(null)
        onFrame(Viewfinder(canRemember = latest?.observation != null))
    }

    private fun focusOnPreviewPoint(x: Float, y: Float) {
        val cam = camera ?: return
        if (view.width <= 0 || view.height <= 0) return
        val point = view.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(5, TimeUnit.SECONDS)
            .build()
        runCatching { cam.cameraControl.startFocusAndMetering(action) }
    }

    fun setProactivityMode(mode: ProactivityMode) {
        if (proactivityMode == mode) return
        proactivityMode = mode
        observer?.proactiveMonitor = mode == ProactivityMode.PROACTIVE
        proactiveTrigger.reset()
        if (mode == ProactivityMode.QUIET) {
            cancelRecommendation()
            view.removeCallbacks(drawDirection)
            run = null
            selection = null
            latestShift = null
            latestStepProgress = null
            trackerLostSinceNs = null
            subjectLostNotified = false
            lastReadyState = false
            observer?.apply { watchPerson = false; tracker = null; stepTracker = null }
            onRecommendationStatus(null)
            render()
        }
    }

    fun tapSubject(x: Float, y: Float) {
        val size = binding?.size ?: return
        if (zoom.changing) return
        focusOnPreviewPoint(x, y)
        val geometry = latest?.geometry
        val tapUv = if (geometry != null) {
            val ref = FrameMapping(geometry, size).previewToReference(Point(x, y))
            val refSize = geometry.referenceSize
            ref?.let { Uv((it.x / refSize.width).toDouble().coerceIn(0.01, 0.99), (it.y / refSize.height).toDouble().coerceIn(0.01, 0.99)) }
        } else {
            Uv((x / size.width).toDouble().coerceIn(0.01, 0.99), (y / size.height).toDouble().coerceIn(0.01, 0.99))
        }
        if (proactivityMode == ProactivityMode.QUIET || requests.config.validationError(BuildConfig.DEBUG) != null) {
            pendingTapPreview = Point(x, y)
            render()
            handler.postDelayed({
                if (requests.active == null) {
                    pendingTapPreview = null
                    render()
                }
            }, 900L)
            return
        }
        recommend(tapUv, Point(x, y))
    }

    fun applySuggestedZoom() {
        val targetZoom = run?.target?.suggestedZoom ?: return
        if (targetZoom in zoom.min..zoom.max) setZoom(targetZoom)
    }

    fun recommend() = recommend(null, null, null)

    private fun recommend(tapUv: Uv?, tapPreview: Point?, statusOverride: String? = null) {
        if (proactivityMode == ProactivityMode.QUIET) return
        if (binding == null || zoom.changing || requests.config.validationError(BuildConfig.DEBUG) != null) return
        val now = SystemClock.elapsedRealtimeNanos()
        if (statusOverride == null) {
            proactiveTrigger.onManualOrCaptured(now, poses?.latestAt(now) ?: latest?.observation?.pose)
        }
        cancelRecommendation()
        run = null
        selection = null
        latestShift = null
        latestStepProgress = null
        trackerLostSinceNs = null
        subjectLostNotified = false
        lastReadyState = false
        pendingTapPreview = tapPreview
        observer?.apply { watchPerson = false; tracker = null; stepTracker = null }
        render()
        val token = requests.begin(now, tapUv)
        observer?.pendingFrame?.await(token)
        onRecommendationStatus(statusOverride ?: if (tapUv != null) "已锁定点选区域，正在构图…" else "正在取景…")
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
        val task = VisionChat(token.config, token.deadlineNs)
        call = task
        requestGeometry = source.geometry
        pendingCandidates = sample.candidates
        pendingCandidateGeometry = source.geometry
        render()
        val capturedZoom = appliedZoom
        val capturedZoomStops = if (lensFacing == CameraSelector.LENS_FACING_FRONT) emptyList() else zoom.keyRatios
        val capturedFront = lensFacing == CameraSelector.LENS_FACING_FRONT
        val capturedPreset = presetSelection.preset
        val candidates = sample.candidates
        val userTapped = sample.tapUv != null
        val preferencePrior = aestheticPrefs.read().promptPrior()
        onRecommendationStatus(
            when {
                userTapped -> "正在围绕所选主体构图…"
                candidates.isNotEmpty() -> "已识别 ${candidates.size} 个候选主体，正在构图…"
                else -> "正在推荐构图…"
            }
        )
        observer?.let { analyzer -> worker.execute {
            try { analyzer.prepareSelection() } catch (e: RuntimeException) {
                Log.w("LumiCamera", "Subject model warmup unavailable", e)
            }
        } }
        network.execute {
            var sentNs: Long? = null
            var sentJpeg: ByteArray? = null
            val result = try {
                val jpeg = sample.use { task.remainingMs(); it.jpeg() }
                if (BuildConfig.DEBUG) sentJpeg = jpeg
                sentNs = SystemClock.elapsedRealtimeNanos()
                Result.success(task.send(jpeg, capturedPreset, candidates, capturedZoomStops, capturedZoom, preferencePrior))
            } catch (e: Exception) { Result.failure(e) }
            val times = RequestTimes(token.config, token.startedNs, sentNs ?: now, SystemClock.elapsedRealtimeNanos())
            main.execute select@{
                if (requests.active != token) return@select
                call = null
                if (sentNs != null) {
                    onConnection(result.fold(ConnectionResult::fromReply) {
                        (it as? ModelFailure)?.kind ?: ConnectionResult.UNREACHABLE
                    })
                }
                val analyzer = observer
                val reply = result.getOrNull()
                val subject = reply?.subject
                if (analyzer == null || subject == null) {
                    finishRecommendation(token, source, result, times, null, sentJpeg, capturedFront, capturedZoom, candidates.size, userTapped)
                } else worker.execute {
                    val attempt = try { analyzer.selectDiagnosed(source, subject, allowBoxFallback = reply.candidateId != null) } catch (e: RuntimeException) {
                        Log.w("LumiCamera", "Subject segmentation unavailable", e)
                        OutlineAttempt(null, "SEGMENTER_ERROR")
                    }
                    main.execute { finishRecommendation(token, source, result, times, attempt, sentJpeg, capturedFront, capturedZoom, candidates.size, userTapped) }
                }
            }
        }
    }

    private fun finishRecommendation(token: RequestToken, source: SceneFrame,
        result: Result<ModelReply>, times: RequestTimes, attempt: OutlineAttempt?,
        sentJpeg: ByteArray?, capturedFront: Boolean, capturedZoom: Float,
        candidateCount: Int, userTapped: Boolean) {
        val completion = requests.finish(token, SystemClock.elapsedRealtimeNanos())
        if (completion == RecommendationRequests.Completion.OBSOLETE) return
        handler.removeCallbacks(timeout)
        call = null
        requestGeometry = null
        pendingCandidates = emptyList()
        pendingCandidateGeometry = null
        pendingTapPreview = null
        if (completion == RecommendationRequests.Completion.EXPIRED) {
            saveDiagnostic("EXPIRED", times, SystemClock.elapsedRealtimeNanos(), sentJpeg, capturedFront, capturedZoom, source.observation, result.getOrNull(), result.exceptionOrNull(), attempt, candidateCount, userTapped)
            onRecommendationStatus(VisionChat.TIMEOUT_NOTICE)
            render()
            return
        }
        val reply = result.getOrElse {
            val decided = SystemClock.elapsedRealtimeNanos()
            recordDecision("REQUEST_FAILED", times, decided)
            saveDiagnostic("REQUEST_FAILED", times, decided, sentJpeg, capturedFront, capturedZoom, source.observation, null, it, attempt, candidateCount, userTapped)
            onRecommendationStatus(when (it) {
                is ModelFailure -> it.notice
                is SocketTimeoutException -> VisionChat.TIMEOUT_NOTICE
                else -> "推荐请求失败，请检查连接后重试。"
            })
            render()
            return
        }
        val outline = attempt?.outline
        val subjectTracker = if (outline != null && source.pixels != null) SubjectTracker.from(outline, source.pixels) else null
        latestShift = null
        trackerLostSinceNs = null
        subjectLostNotified = false
        observer?.tracker = subjectTracker
        selection = Selection(token.config.safeText(reply.name), source.geometry, source.observation, outline)
        fun reject(problem: PlanProblem) {
            observer?.watchPerson = false
            view.removeCallbacks(drawDirection)
            if (outline != null) view.postOnAnimation(drawDirection)
            val decided = SystemClock.elapsedRealtimeNanos()
            recordDecision(problem.name, times, decided)
            saveDiagnostic(problem.name, times, decided, sentJpeg, capturedFront, capturedZoom, source.observation, reply, null, attempt, candidateCount, userTapped)
            onRecommendationStatus(problem.notice)
            render()
        }
        val plan = when (val decision = reply.decision) {
            is PlanResult.Accepted -> decision.value
            is PlanResult.Rejected -> return reject(decision.problem)
        }
        val baseTarget = when (val decision = RememberedComposition.recommended(source, plan, outline, capturedZoom)) {
            is PlanResult.Accepted -> decision.value
            is PlanResult.Rejected -> return reject(decision.problem)
        }
        val stepDir = plan.stepDirection
        if (stepDir != null && outline != null && source.pixels != null) {
            observer?.stepTracker = ParallaxStepTracker.from(stepDir, outline, source.pixels)
            latestStepProgress = StepProgress(stepDir, 0f, satisfied = false)
        } else {
            observer?.stepTracker = null
            latestStepProgress = null
        }
        // Automatically lock AF/AE focus and metering onto the chosen subject.
        val size = binding?.size
        if (outline != null && size != null) {
            val b = outline.bounds
            val ref = source.geometry.referenceSize
            val centerPreview = FrameMapping(source.geometry, size).referenceToPreview(
                Point(((b.left + b.right) * 0.5 * ref.width).toFloat(), ((b.top + b.bottom) * 0.5 * ref.height).toFloat())
            )
            focusOnPreviewPoint(centerPreview.x, centerPreview.y)
        }
        val activePreset = presetSelection.preset
        if (activePreset == CompositionPreset.RULE_OF_THIRDS &&
            (plan.choice == CompositionChoice.LEFT_THIRD || plan.choice == CompositionChoice.RIGHT_THIRD)) {
            presetSelection = presetSelection.copy(thirdChoice = plan.choice)
            onPreset(presetSelection)
        }
        val effectiveTarget = if (activePreset == CompositionPreset.AUTO) {
            baseTarget
        } else {
            val matchesPreset = when (activePreset) {
                CompositionPreset.AUTO -> true
                CompositionPreset.RULE_OF_THIRDS -> plan.choice == CompositionChoice.LEFT_THIRD || plan.choice == CompositionChoice.RIGHT_THIRD
                CompositionPreset.SYMMETRY -> plan.choice == CompositionChoice.CENTER || plan.choice == CompositionChoice.KEEP
                CompositionPreset.TIGHT -> plan.choice == CompositionChoice.TIGHT_TOP || plan.choice == CompositionChoice.KEEP
                CompositionPreset.HEADROOM -> plan.choice == CompositionChoice.MORE_HEADROOM || plan.choice == CompositionChoice.KEEP
            }
            if (matchesPreset) baseTarget else {
                val poseNow = poses?.latestAt(SystemClock.elapsedRealtimeNanos()) ?: baseTarget.backgroundSource.pose
                val lensNow = liveLens(baseTarget.observation.lens) ?: baseTarget.observation.lens
                baseTarget.withPreset(presetSelection, poseNow, lensNow, null)
            }
        }
        val decided = SystemClock.elapsedRealtimeNanos()
        recordDecision("ACCEPTED", times, decided)
        saveDiagnostic("ACCEPTED", times, decided, sentJpeg, capturedFront, capturedZoom, source.observation, reply, null, attempt, candidateCount, userTapped)
        if (BuildConfig.DEBUG) Log.i("LumiRecommendation", token.config.safeText("aimPoint=(${plan.aimPoint.x},${plan.aimPoint.y}) " +
            "confidence=${plan.confidence} alreadyGood=${plan.alreadyGood} zoom=${plan.suggestedZoom} candidate=${plan.candidateId} reason=" + plan.reason))
        onRecommendationStatus(null)
        val safeName = token.config.safeText(reply.name)
        val caption = if (effectiveTarget === baseTarget) token.config.safeText(plan.caption)
            else effectiveTarget.choice?.let { "$safeName·${it.label}" } ?: token.config.safeText(plan.caption)
        startGuide(effectiveTarget, caption, safeName) { recordDecision("ACCEPTED", times, decided, SystemClock.elapsedRealtimeNanos()) }
    }

    private fun saveDiagnostic(outcome: String, times: RequestTimes, decided: Long, jpeg: ByteArray?,
        front: Boolean, zoomRatio: Float, sourceObservation: Observation?, reply: ModelReply?,
        failure: Throwable?, attempt: OutlineAttempt?, candidateCount: Int = 0, userTapped: Boolean = false) {
        if (!BuildConfig.DEBUG) return
        val input = DiagnosticInput(
            config = times.config,
            jpeg = jpeg,
            outcome = outcome,
            frontCamera = front,
            zoomRatio = zoomRatio,
            prepareMs = (times.sent - times.tap) / 1_000_000,
            modelMs = (times.replied - times.sent) / 1_000_000,
            verifyMs = (decided - times.replied) / 1_000_000,
            sourceObservation = sourceObservation,
            reply = reply,
            failure = failure,
            outlineAttempt = attempt,
            candidateCount = candidateCount,
            userTapped = userTapped,
        )
        network.execute { CompositionDiagnostic.save(context, input) }
    }

    private data class RequestTimes(val config: ModelConfig, val tap: Long, val sent: Long, val replied: Long)

    private fun recordDecision(outcome: String, times: RequestTimes, decided: Long, firstRing: Long? = null) {
        if (!BuildConfig.DEBUG) return
        fun ms(ns: Long) = ns / 1_000_000
        val details = "${System.currentTimeMillis()} outcome=$outcome prepareMs=${ms(times.sent-times.tap)} " +
            "modelMs=${ms(times.replied-times.sent)} verifyMs=${ms(decided-times.replied)}" +
            (firstRing?.let { " firstRingMs=${ms(it-times.tap)}" } ?: "")
        val summary = times.config.recordText(details, BuildConfig.DEBUG)
        Log.i("LumiRecommendation", summary)
        val history = context.getSharedPreferences("recommendation_outcomes", Context.MODE_PRIVATE)
        history.edit {
            if (firstRing == null) putString("previous", history.getString("latest", null))
            putString("latest", summary)
        }
    }

    private fun cancelRecommendation() {
        requests.cancel()
        observer?.pendingFrame?.await(null)
        handler.removeCallbacks(timeout)
        call?.cancel(network)
        call = null
        requestGeometry = null
        pendingCandidates = emptyList()
        pendingCandidateGeometry = null
        pendingTapPreview = null
    }

    fun scaleZoom(factor: Float) = setZoom(zoom.ratio * factor)

    fun setZoom(requested: Float) {
        val camera = camera ?: return
        if (!requested.isFinite()) return
        val ratio = requested.coerceIn(zoom.min, zoom.max)
        if (ratio == zoom.ratio) return
        val generation = ++zoomGeneration
        desiredZoom = ratio
        zoom = zoom.copy(ratio = ratio, changing = true)
        onZoom(zoom)
        if (requests.active != null) {
            cancelRecommendation()
            onRecommendationStatus(null)
        }
        observer?.expectedZoom = ratio
        render()
        val operation = camera.cameraControl.setZoomRatio(ratio)
        operation.addListener({
            if (generation != zoomGeneration) return@addListener
            try {
                operation.get()
                appliedZoom = ratio
            } catch (_: java.util.concurrent.ExecutionException) {
                desiredZoom = appliedZoom
                observer?.expectedZoom = appliedZoom
                onMessage("相机未能切换倍率")
            }
            zoomSinceNs = SystemClock.elapsedRealtimeNanos()
            zoomSettleUntilNs = zoomSinceNs + ZOOM_SETTLE_NS
            trackerLostSinceNs = null
            logZoomFrame = true
            zoom = zoom.copy(ratio = appliedZoom, changing = false)
            onZoom(zoom)
            render()
        }, main)
    }

    fun save() {
        if (zoom.changing) return
        val photo = capture ?: return
        val now = SystemClock.elapsedRealtimeNanos()
        val capturePose = poses?.latestAt(now) ?: latest?.observation?.pose
        val captureLens = liveLens(run?.target?.observation?.lens ?: latest?.observation?.lens)
        val captureBounds = (if (run != null && capturePose != null && captureLens != null) {
            run?.target?.liveSubjectBounds(capturePose, captureLens, latestShift)
        } else null)
            ?: selection?.outline?.bounds
            ?: latest?.box
        val captureRoll = capturePose?.horizonRollDeg()
        val captureReady = lastReadyState
        val captureReason = run?.reason ?: selection?.name
        val captureCue = run?.target?.directorCue
        val captureChoice = run?.target?.choice
        val captureZoom = zoom.ratio
        val captureLuma = latest?.meanLuma
        val captureClutter = latest?.backgroundClutter
        val captureUnresolvedStep = run?.target?.stepDirection != null && latestStepProgress?.satisfied != true
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Lumi_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Lumi")
            }
        }
        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT
        }
        val output = ImageCapture.OutputFileOptions.Builder(context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            .setMetadata(metadata)
            .build()
        photo.takePicture(output, main, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                if (disposed) return
                proactiveTrigger.onManualOrCaptured(SystemClock.elapsedRealtimeNanos(), capturePose)
                aestheticPrefs.recordComposition(captureChoice, captureZoom)
                val uri = result.savedUri
                if (uri != null) {
                    val review = PostShotRecrop.buildReview(
                        photoUri = uri,
                        subjectBounds = captureBounds,
                        rollDeg = captureRoll,
                        wasReady = captureReady,
                        reason = captureReason,
                        directorCue = captureCue,
                        meanLuma = captureLuma,
                        zoomRatio = captureZoom,
                        backgroundClutter = captureClutter,
                        unresolvedStep = captureUnresolvedStep,
                    )
                    onCaptured(review)
                } else {
                    onMessage("照片已保存到系统相册。")
                }
            }
            override fun onError(exception: ImageCaptureException) {
                Log.w("LumiCamera", "Save failed", exception)
                if (!disposed) onMessage("保存失败，请检查相册权限和存储空间。")
            }
        })
    }

    private fun releaseBinding() {
        zoomObserver?.let { camera?.cameraInfo?.zoomState?.removeObserver(it) }
        zoomObserver = null
        zoomGeneration++
        zoomSinceNs = 0L
        logZoomFrame = true
        camera = null
        readings.reset()
        zoom = ViewfinderZoom()
        onZoom(zoom)
        view.removeCallbacks(drawDirection)
        frames.reset()
        cancelRecommendation()
        onRecommendationStatus(null)
        latest = null
        latestLens = null
        latestLensZoom = 1f
        latestShift = null
        latestStepProgress = null
        trackerLostSinceNs = null
        subjectLostNotified = false
        lastReadyState = false
        proactiveTrigger.reset()
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

    companion object {
        private const val SUBJECT_LOST_NS = 450_000_000L
        private const val ZOOM_SETTLE_NS = 260_000_000L
    }
}
