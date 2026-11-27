package com.lumi.camera.camera

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.view.View
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ZoomState
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.lumi.camera.BuildConfig
import com.lumi.camera.filter.AiFilterSuggestion
import com.lumi.camera.filter.FilterAdjuster
import com.lumi.camera.filter.FilterPreset
import com.lumi.camera.filter.FilterSelectionMode
import com.lumi.camera.filter.FilterThumbnailRenderer
import com.lumi.camera.filter.FilterUiState
import com.lumi.camera.filter.PhotoFilterRenderer
import com.lumi.camera.filter.PreviewFilterEffect
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
import com.lumi.camera.observability.LumiTelemetry
import com.lumi.camera.shotplan.AestheticPreferenceStore
import com.lumi.camera.shotplan.AiExposureSuggestion
import com.lumi.camera.shotplan.CompositionChoice
import com.lumi.camera.shotplan.CompositionDiagnostic
import com.lumi.camera.shotplan.CompositionPreset
import com.lumi.camera.shotplan.ConnectionResult
import com.lumi.camera.shotplan.DiagnosticInput
import com.lumi.camera.shotplan.DirectorCue
import com.lumi.camera.shotplan.ModelConfig
import com.lumi.camera.shotplan.ModelFailure
import com.lumi.camera.shotplan.ModelReply
import com.lumi.camera.shotplan.OnDeviceCompositionAdvisor
import com.lumi.camera.shotplan.PlanProblem
import com.lumi.camera.shotplan.PlanResult
import com.lumi.camera.shotplan.PoseSilhouette
import com.lumi.camera.shotplan.PresetSelection
import com.lumi.camera.shotplan.ProactiveTrigger
import com.lumi.camera.shotplan.SessionShotMemory
import com.lumi.camera.shotplan.SessionShotRecord
import com.lumi.camera.shotplan.ProactivityMode
import com.lumi.camera.shotplan.RecommendationRequests
import com.lumi.camera.shotplan.ReferenceGuidance
import com.lumi.camera.shotplan.ReferenceImageLoader
import com.lumi.camera.shotplan.RememberedComposition
import com.lumi.camera.shotplan.RequestToken
import com.lumi.camera.shotplan.SubjectCandidate
import com.lumi.camera.shotplan.VisionChat
import com.lumi.camera.ui.ExposureUiState
import com.lumi.camera.ui.ReferenceUiState
import com.lumi.camera.vision.CloseupFraming
import com.lumi.camera.vision.CloseupProposal
import com.lumi.camera.vision.CompositionAnalyzer
import com.lumi.camera.vision.LightGuidance
import com.lumi.camera.vision.LightGuidanceStabilizer
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
import kotlin.math.roundToInt

internal data class CandidateOverlay(
    val id: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val userPicked: Boolean = false,
)

internal data class CloseupOverlay(
    val id: String,
    val label: String,
    val targetZoom: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
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
    val closeups: List<CloseupOverlay> = emptyList(),
    val tapPoint: Point? = null,
    val viewpointDrifted: Boolean = false,
    val directorCue: DirectorCue? = null,
    val stepProgress: StepProgress? = null,
    val referenceGuidance: ReferenceGuidance? = null,
    val lightGuidance: LightGuidance? = null,
    val poseSilhouette: PoseSilhouette? = null,
    val isOfflineBaseline: Boolean = false,
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
    private val onExposure: (ExposureUiState) -> Unit = {},
    private val onReference: (ReferenceUiState) -> Unit = {},
    private val onNativeProcessing: (NativeProcessingUiState) -> Unit = {},
    private val onFilter: (FilterUiState) -> Unit = {},
    private val onControls: (CameraControlsUiState) -> Unit = {},
    private val onCaptureQueue: (CaptureQueueState) -> Unit = {},
    initialProactivity: ProactivityMode = ProactivityMode.MANUAL,
    initialExposureMode: ExposureControlMode = ExposureControlMode.AUTO,
    initialNativeProcessing: NativeProcessingMode = NativeProcessingMode.NATIVE_AI,
    initialFilterMode: FilterSelectionMode = FilterSelectionMode.MANUAL,
    initialFilterPreset: FilterPreset = FilterPreset.ORIGINAL,
    initialFilterIntensity: Float = FilterAdjuster.DEFAULT_INTENSITY,
    initialFilterAiAdaptive: Boolean = true,
    initialFlash: FlashOption = FlashOption.OFF,
    initialAspectRatio: CaptureAspectRatio = CaptureAspectRatio.RATIO_3_4,
) : DefaultLifecycleObserver, DisplayManager.DisplayListener {
    private data class Binding(
        val size: Extent,
        val rotation: Int,
        val lensFacing: Int,
        val extensionMode: Int?,
        val minimizeLatency: Boolean,
    )
    private val context = view.context
    private val main = ContextCompat.getMainExecutor(context)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val network = Executors.newCachedThreadPool()
    private val capturePipeline = CapturePipeline(
        maxPending = 4,
        callbackExecutor = main,
        onStateChanged = { state -> if (!disposed) onCaptureQueue(state) },
    )
    private val requests = RecommendationRequests(initialConfig)
    private var call: VisionChat? = null
    private var requestGeometry: FrameGeometry? = null
    private val timeout = Runnable {
        if (requests.active != null) {
            val hadOfflineBaseline = run?.target?.isOfflineBaseline == true
            val notice = if (requestGeometry == null) "取景超时，未取得同步的画面和手机姿态。" else VisionChat.TIMEOUT_NOTICE
            if (call != null) onConnection(ConnectionResult.UNREACHABLE)
            cancelRecommendation()
            if (hadOfflineBaseline) {
                onRecommendationStatus(null)
                onMessage("云端响应超时，已保持端侧离线构图引导")
            } else {
                onRecommendationStatus(notice)
            }
            render()
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
    private val sessionMemory = SessionShotMemory()
    private var lastReadyState = false
    private var selection: Selection? = null
    private var run: Run? = null
    private var pendingCandidates: List<SubjectCandidate> = emptyList()
    private var pendingCandidateGeometry: FrameGeometry? = null
    private var pendingTapPreview: Point? = null
    private var latestCloseups: List<CloseupProposal> = emptyList()
    private var latestCloseupFrame: SceneFrame? = null
    private var latest: SceneFrame? = null
    private var latestLens: Lens? = null
    private var latestLensZoom = 1f
    private var latestShift: TrackedShift? = null
    private var latestStepProgress: StepProgress? = null
    private var stepSatisfiedSinceNs: Long? = null
    private val lightStabilizer = LightGuidanceStabilizer()
    private var trackerLostSinceNs: Long? = null
    private var subjectLostNotified = false
    private var poses: CameraPose? = null
    private var lightSensor: AmbientLightSensor? = null
    private var capabilities = CameraCapabilities()
    private var nativeProcessingMode = initialNativeProcessing
    private var nativeCapability = NativeProcessingCapability()
    private var extensionsManager: ExtensionsManager? = null
    private var filterMode = initialFilterMode
    private var filterPreset = initialFilterPreset
    private var filterIntensity = initialFilterIntensity.coerceIn(0f, 1f)
    private var filterAiAdaptive = initialFilterAiAdaptive
    private var filterPreviewRequested = false
    private var aiFilterSuggestion: AiFilterSuggestion? = null
    private var filterState = FilterUiState(
        mode = filterMode,
        selectedPreset = filterPreset,
        intensity = filterIntensity,
        aiAdaptiveEnabled = filterAiAdaptive,
    )
    private var previewFilterEffect: PreviewFilterEffect? = null
    private var exposureMode = initialExposureMode
    private var manualExposure = ManualExposureSettings()
    private var quickEvSteps = 0f
    private var flashOption = initialFlash
    private var hasFlashUnit = false
    private var torchEnabled = false
    private var aspectRatio = initialAspectRatio
    private var deviceSurfaceRotation = Surface.ROTATION_0
    private var pendingAiExposure: AiExposureSuggestion? = null
    private var appliedAiExposure: AiExposureSuggestion? = null
    private var latestAiExposure: AiExposureSuggestion? = null
    private var lastTelemetryPushNs = 0L
    private var referenceState = ReferenceUiState()
    private var referenceAnalysisJpeg: ByteArray? = null
    private var referenceCompareJpeg: ByteArray? = null
    private var referenceGeneration = 0
    private var referenceCall: VisionChat? = null
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
                    val extFuture = ExtensionsManager.getInstanceAsync(context, cameraProvider)
                    extFuture.addListener({
                        if (!disposed) {
                            extensionsManager = runCatching { extFuture.get() }.getOrNull()
                            bind()
                        }
                    }, main)
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

    private fun probeSupportedExtensions(baseSelector: CameraSelector): Set<Int> {
        val mgr = extensionsManager ?: return emptySet()
        return buildSet {
            for (mode in listOf(ExtensionMode.AUTO, ExtensionMode.HDR, ExtensionMode.NIGHT)) {
                val ok = runCatching { mgr.isExtensionAvailable(baseSelector, mode) }.getOrDefault(false)
                if (ok) add(mode)
            }
        }
    }

    private fun publishNativeProcessingState() {
        onNativeProcessing(
            NativeProcessingPolicy.uiState(nativeProcessingMode, exposureMode, nativeCapability)
        )
    }

    fun setNativeProcessingMode(mode: NativeProcessingMode) {
        if (nativeProcessingMode == mode) return
        val previousPlan = NativeProcessingPolicy.resolve(nativeProcessingMode, exposureMode, nativeCapability)
        nativeProcessingMode = mode
        val nextPlan = NativeProcessingPolicy.resolve(nativeProcessingMode, exposureMode, nativeCapability)
        publishNativeProcessingState()
        if (previousPlan.extensionMode != nextPlan.extensionMode ||
            previousPlan.minimizeCaptureLatency != nextPlan.minimizeCaptureLatency) {
            bind()
        } else {
            applyExposureToHardware()
        }
        onMessage(
            if (mode == NativeProcessingMode.PURE) "已切换至纯净原片（关闭本机 AI 优化）"
            else "已开启本机 AI 成像优化"
        )
    }

    private fun refreshFilterState() {
        val next = FilterAdjuster.resolve(
            mode = filterMode,
            selectedPreset = filterPreset,
            intensity = filterIntensity,
            aiAdaptiveEnabled = filterAiAdaptive,
            aiSuggestion = aiFilterSuggestion,
            telemetry = currentTelemetry(),
        )
        filterState = next
        previewFilterEffect?.updateGrade(next.resolvedGrade)
        onFilter(next)
    }

    fun selectAiSmartFilter() {
        filterMode = FilterSelectionMode.AI_SMART
        if (filterIntensity <= 0.05f) filterIntensity = FilterAdjuster.DEFAULT_INTENSITY
        refreshFilterState()
    }

    fun selectFilterPreset(preset: FilterPreset) {
        filterMode = FilterSelectionMode.MANUAL
        filterPreset = preset
        if (preset != FilterPreset.ORIGINAL && filterIntensity <= 0.05f) {
            filterIntensity = FilterAdjuster.DEFAULT_INTENSITY
        }
        refreshFilterState()
    }

    fun setFilterIntensity(intensity: Float) {
        filterIntensity = intensity.coerceIn(0f, 1f)
        refreshFilterState()
    }

    fun setFilterAiAdaptive(enabled: Boolean) {
        if (filterAiAdaptive == enabled) return
        filterAiAdaptive = enabled
        refreshFilterState()
    }

    fun setFilterPreviewEnabled(enabled: Boolean) {
        filterPreviewRequested = enabled
        observer?.filterPreviewEnabled = enabled
        if (!enabled) observer?.latestFilterPreview = null
    }

    fun captureFilterPreviewBitmap(): android.graphics.Bitmap? =
        observer?.latestFilterPreview?.let(FilterThumbnailRenderer::sampleSquareFromPixels)

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun bind() {
        val p = provider ?: return
        val rotation = view.display?.rotation ?: return
        if (disposed || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) ||
            view.width <= 0 || view.height <= 0) return
        val front = lensFacing == CameraSelector.LENS_FACING_FRONT
        val baseSelector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val supportedExts = probeSupportedExtensions(baseSelector)
        val tentativeCap = nativeCapability.copy(supportedExtensions = supportedExts)
        val initialPlan = NativeProcessingPolicy.resolve(nativeProcessingMode, exposureMode, tentativeCap)
        val next = Binding(
            size = Extent(view.width, view.height),
            rotation = rotation,
            lensFacing = lensFacing,
            extensionMode = initialPlan.extensionMode,
            minimizeLatency = initialPlan.minimizeCaptureLatency,
        )
        if (next == binding) return
        val viewport = view.getViewPort(rotation) ?: return
        releaseBinding()
        val generation = frames.session
        val effectiveSelector = initialPlan.extensionMode?.let { extMode ->
            runCatching { extensionsManager?.getExtensionEnabledCameraSelector(baseSelector, extMode) }
                .getOrNull()
        } ?: baseSelector
        val preview = Preview.Builder().setTargetRotation(rotation).build()
        val analysisBuilder = ImageAnalysis.Builder().setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        Camera2Interop.Extender(analysisBuilder).setSessionCaptureCallback(readings)
        val analysis = analysisBuilder.build()
        val captureMode = if (initialPlan.minimizeCaptureLatency) {
            ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        } else {
            ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
        }
        val photo = ImageCapture.Builder()
            .setTargetRotation(rotation)
            .setCaptureMode(captureMode)
            .setFlashMode(flashOption.toImageCaptureFlashMode())
            .build()
        preview.setSurfaceProvider(view.surfaceProvider)
        val filterEffect = PreviewFilterEffect().also {
            it.updateGrade(filterState.resolvedGrade)
        }
        previewFilterEffect = filterEffect
        val group = UseCaseGroup.Builder().setViewPort(viewport)
            .addUseCase(preview).addUseCase(analysis).addUseCase(photo)
            .addEffect(filterEffect.cameraEffect)
            .build()
        uses = arrayOf(preview, analysis, photo)
        try {
            var actualExtensionMode = initialPlan.extensionMode
            var extensionBindFailed = false
            val camera = try {
                p.bindToLifecycle(owner, effectiveSelector, group)
            } catch (e: RuntimeException) {
                if (effectiveSelector !== baseSelector) {
                    Log.w("LumiCamera", "Extension bind failed (mode=$actualExtensionMode), falling back to standard pipeline", e)
                    actualExtensionMode = null
                    extensionBindFailed = true
                    p.bindToLifecycle(owner, baseSelector, group)
                } else {
                    throw e
                }
            }
            this.camera = camera
            appliedZoom = 1f
            val sensors = CameraPose(context, rotation)
            poses = sensors
            if (!sensors.start()) onMessage("此设备的旋转矢量传感器不可用。")
            val luxSensor = AmbientLightSensor(context)
            lightSensor = luxSensor
            luxSensor.start()
            val optics = CameraOptics(camera.cameraInfo, readings)
            capabilities = optics.capabilities()
            nativeCapability = NativeProcessingCapability(
                supportedExtensions = if (extensionBindFailed) emptySet() else supportedExts,
                isp = optics.processingModes(),
            )
            val analyzer = CompositionAnalyzer(context, optics, sensors,
                mirrored = front,
                modelFrameReady = { token, sample -> main.execute { sendRecommendation(token, sample) } }) { result ->
                main.execute {
                    if (frames.accept(generation, 0, result.sequence)) receive(result)
                }
            }
            observer = analyzer
            analyzer.proactiveMonitor = proactivityMode == ProactivityMode.PROACTIVE
            analyzer.filterPreviewEnabled = filterPreviewRequested
            analysis.setAnalyzer(worker, analyzer)
            binding = next.copy(extensionMode = actualExtensionMode)
            capture = photo
            photo.targetRotation = deviceSurfaceRotation
            hasFlashUnit = camera.cameraInfo.hasFlashUnit()
            if (!hasFlashUnit && flashOption == FlashOption.TORCH) {
                flashOption = FlashOption.OFF
            }
            applyFlashToHardware()
            applyExposureToHardware()
            publishExposureState()
            publishNativeProcessingState()
            publishControlsState()
            refreshFilterState()
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
        sessionMemory.clear()
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
        LumiTelemetry.markFirstViewfinderFrame()
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
        if (now - lastTelemetryPushNs >= 250_000_000L) {
            lastTelemetryPushNs = now
            publishExposureState()
            refreshFilterState()
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
        val stabilizedLight = lightStabilizer.update(fresh?.lumaStats?.lightGuidance, now)
        val geometry = active?.target?.geometry ?: identified?.geometry
        if (geometry == null) {
            latestCloseups = emptyList()
            latestCloseupFrame = null
            onFrame(Viewfinder(
                canRemember = current != null,
                rollDeg = rollDeg,
                candidates = candidateOverlays,
                closeups = emptyList(),
                tapPoint = pendingTapPreview,
                lightGuidance = null,
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
        val activePersonOutline = active?.target?.outline?.takeIf { it.person }
        val closeupOverlays = if (fresh != null &&
            active != null &&
            activePersonOutline != null &&
            !subjectLost &&
            requests.active == null &&
            proactivityMode != ProactivityMode.QUIET &&
            lensFacing == CameraSelector.LENS_FACING_BACK &&
            !zoom.changing &&
            zoom.max > zoom.ratio + 0.45f) {
            val livePersonBounds = if (pose != null && lens != null) {
                active.target.liveSubjectBounds(pose, lens, latestShift)
            } else activePersonOutline.bounds
            val proposals = CloseupFraming.generateForSelectedPerson(
                selectedPersonBounds = livePersonBounds,
                faces = fresh.faces,
                currentZoom = zoom.ratio,
                zoomStops = zoom.keyRatios,
                maxZoom = zoom.max,
            )
            latestCloseups = proposals
            latestCloseupFrame = if (proposals.isNotEmpty()) fresh else null
            if (proposals.isEmpty()) emptyList() else {
                val refSize = fresh.geometry.referenceSize
                val map = FrameMapping(fresh.geometry, size)
                proposals.map { p ->
                    val tl = map.referenceToPreview(Point((p.box.left * refSize.width).toFloat(), (p.box.top * refSize.height).toFloat()))
                    val br = map.referenceToPreview(Point((p.box.right * refSize.width).toFloat(), (p.box.bottom * refSize.height).toFloat()))
                    CloseupOverlay(
                        id = p.id,
                        label = p.chipLabel,
                        targetZoom = p.targetZoom,
                        left = minOf(tl.x, br.x),
                        top = minOf(tl.y, br.y),
                        right = maxOf(tl.x, br.x),
                        bottom = maxOf(tl.y, br.y),
                    )
                }
            }
        } else {
            latestCloseups = emptyList()
            latestCloseupFrame = null
            emptyList()
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
        val rawStep = if (active?.target?.stepDirection != null) latestStepProgress else null
        val visibleStep = when {
            rawStep == null || viewpointDrifted -> {
                stepSatisfiedSinceNs = null
                null
            }
            rawStep.satisfied -> {
                val since = stepSatisfiedSinceNs ?: now.also { stepSatisfiedSinceNs = it }
                if (now - since < STEP_SATISFIED_DISPLAY_NS) rawStep else null
            }
            else -> {
                stepSatisfiedSinceNs = null
                rawStep
            }
        }
        if (guide.ready && !lastReadyState) {
            LumiTelemetry.recordGuidanceReady()
        }
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
            closeups = closeupOverlays,
            tapPoint = pendingTapPreview,
            viewpointDrifted = viewpointDrifted,
            directorCue = active?.target?.directorCue,
            stepProgress = visibleStep,
            referenceGuidance = active?.target?.referenceGuidance,
            lightGuidance = if (active != null) stabilizedLight else null,
            poseSilhouette = active?.target?.poseSilhouette,
            isOfflineBaseline = active?.target?.isOfflineBaseline == true,
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
            pendingAiExposure = null
            observer?.apply { watchPerson = false; tracker = null; stepTracker = null }
            onRecommendationStatus(null)
            publishExposureState()
            render()
        }
    }

    private fun currentTelemetry(frame: SceneFrame? = latest): LiveTelemetry {
        val base = readings.latestExposure()
        val evStep = capabilities.evStep.takeIf { it > 0f } ?: (1f / 6f)
        val stats = frame?.lumaStats
        return LiveTelemetry(
            iso = base.iso,
            shutterNs = base.shutterNs,
            aperture = base.aperture ?: capabilities.primaryAperture,
            evSteps = base.evIndex * evStep,
            ambientLux = lightSensor?.latestLux,
            meanLuma = stats?.meanLuma ?: frame?.meanLuma,
            highlightClipPct = stats?.highlightClipPct,
            shadowClipPct = stats?.shadowClipPct,
            subjectVsBgDiff = stats?.subjectVsBgDiff,
            lightGuidance = stats?.lightGuidance,
        )
    }

    private fun publishExposureState() {
        onExposure(
            ExposureUiState(
                mode = exposureMode,
                capabilities = capabilities,
                telemetry = currentTelemetry(),
                manual = manualExposure,
                pendingAiSuggestion = pendingAiExposure,
                appliedAiSuggestion = appliedAiExposure,
                latestAiSuggestion = latestAiExposure,
            )
        )
    }

    fun setExposureMode(mode: ExposureControlMode) {
        if (exposureMode == mode) return
        val prevProc = NativeProcessingPolicy.resolve(nativeProcessingMode, exposureMode, nativeCapability)
        exposureMode = mode
        when (mode) {
            ExposureControlMode.AUTO -> {
                pendingAiExposure = null
                appliedAiExposure = null
            }
            ExposureControlMode.AI_AUTO -> {
                // Keep any latest AI suggestion ready if user wants to apply it.
            }
            ExposureControlMode.MANUAL -> {
                pendingAiExposure = null
                appliedAiExposure = null
            }
        }
        val nextProc = NativeProcessingPolicy.resolve(nativeProcessingMode, exposureMode, nativeCapability)
        publishNativeProcessingState()
        if (prevProc.extensionMode != nextProc.extensionMode) {
            if (nextProc.bypassedForManual) {
                onMessage("M 手动挡已临时避让本机厂商扩展，确保手动参数生效")
            }
            bind()
        } else {
            applyExposureToHardware()
            publishExposureState()
            publishControlsState()
        }
    }

    fun updateManualExposure(settings: ManualExposureSettings) {
        manualExposure = settings
        val prevProc = NativeProcessingPolicy.resolve(nativeProcessingMode, exposureMode, nativeCapability)
        if (exposureMode != ExposureControlMode.MANUAL) {
            exposureMode = ExposureControlMode.MANUAL
        }
        appliedAiExposure = null
        pendingAiExposure = null
        val nextProc = NativeProcessingPolicy.resolve(nativeProcessingMode, exposureMode, nativeCapability)
        publishNativeProcessingState()
        if (prevProc.extensionMode != nextProc.extensionMode) {
            bind()
        } else {
            applyExposureToHardware()
            publishExposureState()
        }
    }

    fun resetManualExposure() {
        manualExposure = ManualExposureSettings()
        appliedAiExposure = null
        pendingAiExposure = null
        applyExposureToHardware()
        publishExposureState()
    }

    fun applyPendingAiExposure() {
        val suggestion = pendingAiExposure ?: return
        pendingAiExposure = null
        appliedAiExposure = suggestion
        applyExposureToHardware()
        publishExposureState()
        onMessage("已应用 AI 光影参数：${suggestion.summaryLabel()}")
    }

    fun revertAiExposure() {
        appliedAiExposure = null
        pendingAiExposure = null
        applyExposureToHardware()
        publishExposureState()
        onMessage("已还原为自动测光")
    }

    fun applyLatestAiToManual() {
        val suggestion = latestAiExposure ?: return
        val prevProc = NativeProcessingPolicy.resolve(nativeProcessingMode, exposureMode, nativeCapability)
        manualExposure = ExposureSolver.fromAiSuggestion(suggestion, capabilities)
        exposureMode = ExposureControlMode.MANUAL
        pendingAiExposure = null
        appliedAiExposure = null
        val nextProc = NativeProcessingPolicy.resolve(nativeProcessingMode, exposureMode, nativeCapability)
        publishNativeProcessingState()
        if (prevProc.extensionMode != nextProc.extensionMode) {
            bind()
        } else {
            applyExposureToHardware()
            publishExposureState()
        }
        onMessage("已将 AI 建议填入 M 手动挡，可继续微调")
    }

    fun loadReferenceImage(uri: Uri) {
        val gen = ++referenceGeneration
        referenceCall?.cancel(network)
        referenceCall = null
        referenceAnalysisJpeg = null
        referenceCompareJpeg = null
        referenceState = ReferenceUiState(analyzing = true)
        onReference(referenceState)
        worker.execute {
            val loaded = runCatching { ReferenceImageLoader.load(context, uri) }.getOrNull()
            main.execute {
                if (disposed || gen != referenceGeneration) return@execute
                if (loaded == null) {
                    referenceState = ReferenceUiState(errorMessage = "无法读取参考图，请换一张图片重试。")
                    onReference(referenceState)
                    onMessage("无法读取参考图，请换一张图片。")
                    return@execute
                }
                referenceAnalysisJpeg = loaded.analysisJpeg
                referenceCompareJpeg = loaded.compactCompareJpeg
                val configured = requests.config.validationError(BuildConfig.DEBUG) == null
                referenceState = ReferenceUiState(
                    previewBitmap = loaded.displayBitmap,
                    overlayEnabled = false,
                    overlayAlpha = 0.25f,
                    analyzing = configured,
                    recipe = null,
                    errorMessage = null,
                )
                onReference(referenceState)
                if (configured) {
                    onMessage("参考图已载入，正在拆解拍摄处方（轻点缩略图可开叠图）")
                    analyzeReferenceRecipe(gen, loaded.analysisJpeg)
                } else {
                    onMessage("参考图已载入，轻点右上角缩略图可开启 25% 叠图")
                }
            }
        }
    }

    fun retryReferenceAnalysis() {
        val jpeg = referenceAnalysisJpeg ?: return
        if (requests.config.validationError(BuildConfig.DEBUG) != null) {
            onMessage("请先配置视觉模型后再拆解处方。")
            return
        }
        val gen = ++referenceGeneration
        referenceCall?.cancel(network)
        referenceCall = null
        referenceState = referenceState.copy(analyzing = true, errorMessage = null)
        onReference(referenceState)
        analyzeReferenceRecipe(gen, jpeg)
    }

    private fun analyzeReferenceRecipe(gen: Int, analysisJpeg: ByteArray) {
        val cfg = requests.config
        val deadline = SystemClock.elapsedRealtimeNanos() + 20_000_000_000L
        val task = VisionChat(cfg, deadline)
        referenceCall = task
        network.execute {
            val result = runCatching { task.analyzeReference(analysisJpeg) }
            main.execute {
                if (disposed || gen != referenceGeneration) return@execute
                if (referenceCall === task) referenceCall = null
                result.onSuccess { recipe ->
                    referenceState = referenceState.copy(analyzing = false, recipe = recipe, errorMessage = null)
                    onReference(referenceState)
                    recipe.targetExposure?.let { exp ->
                        latestAiExposure = exp
                        if (exposureMode == ExposureControlMode.AI_AUTO && proactivityMode == ProactivityMode.MANUAL) {
                            pendingAiExposure = exp
                        }
                        publishExposureState()
                    }
                    onMessage("参考处方已就绪：${recipe.shotType} · 点推荐构图获取复刻与超越建议")
                }.onFailure { err ->
                    val notice = (err as? ModelFailure)?.notice ?: "参考图拆解失败，仍可使用半透明叠图和实时推荐。"
                    referenceState = referenceState.copy(analyzing = false, errorMessage = notice)
                    onReference(referenceState)
                }
            }
        }
    }

    fun toggleReferenceOverlay() {
        if (!referenceState.hasReference) return
        val next = !referenceState.overlayEnabled
        referenceState = referenceState.copy(overlayEnabled = next)
        onReference(referenceState)
        onMessage(if (next) "已开启参考图半透明叠图" else "已关闭参考图叠图")
    }

    fun setReferenceOverlayAlpha(alpha: Float) {
        if (!referenceState.hasReference) return
        referenceState = referenceState.copy(overlayAlpha = alpha.coerceIn(0.15f, 0.45f))
        onReference(referenceState)
    }

    fun clearReferenceImage() {
        referenceGeneration++
        referenceCall?.cancel(network)
        referenceCall = null
        referenceAnalysisJpeg = null
        referenceCompareJpeg = null
        referenceState = ReferenceUiState()
        onReference(referenceState)
        onMessage("已清除参考图")
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun applyExposureToHardware() {
        val cam = camera ?: return
        val effectiveSettings = when (exposureMode) {
            ExposureControlMode.AUTO -> ManualExposureSettings(evSteps = quickEvSteps)
            ExposureControlMode.MANUAL -> manualExposure
            ExposureControlMode.AI_AUTO -> appliedAiExposure?.let { ExposureSolver.fromAiSuggestion(it, capabilities) }
                ?: ManualExposureSettings(evSteps = quickEvSteps)
        }
        val baseline = readings.lastAutoExposure().takeIf { it.iso != null && it.shutterNs != null }
            ?: readings.latestExposure()
        val resolved = ExposureSolver.resolve(effectiveSettings, capabilities, baseline)

        val options = CaptureRequestOptions.Builder()
        if (resolved.aeAuto) {
            // Clear AE_MODE override so CameraX ImageCapture owns ON / ON_AUTO_FLASH / ON_ALWAYS_FLASH.
            options.clearCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE)
            options.clearCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY)
            options.clearCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME)
            runCatching { cam.cameraControl.setExposureCompensationIndex(resolved.evIndex) }
        } else {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            resolved.manualIso?.let { options.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, it) }
            resolved.manualShutterNs?.let { options.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
        }

        if (resolved.wbKelvin != null) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            options.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
            )
            options.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                ExposureSolver.kelvinToRggbVector(resolved.wbKelvin),
            )
        } else {
            options.clearCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE)
            options.clearCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE)
            options.clearCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS)
        }

        if (resolved.aperture != null) {
            options.setCaptureRequestOption(CaptureRequest.LENS_APERTURE, resolved.aperture)
        } else {
            options.clearCaptureRequestOption(CaptureRequest.LENS_APERTURE)
        }

        val processing = NativeProcessingPolicy.resolve(nativeProcessingMode, exposureMode, nativeCapability)
        if (processing.sceneMode != null) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, processing.sceneMode)
        } else {
            options.clearCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE)
        }
        if (processing.edgeMode != null) {
            options.setCaptureRequestOption(CaptureRequest.EDGE_MODE, processing.edgeMode)
        } else {
            options.clearCaptureRequestOption(CaptureRequest.EDGE_MODE)
        }
        if (processing.noiseReductionMode != null) {
            options.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, processing.noiseReductionMode)
        } else {
            options.clearCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE)
        }

        runCatching {
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(options.build())
        }.onFailure { e ->
            Log.w("LumiCamera", "Unable to apply Camera2 exposure options", e)
        }
    }

    private fun applyFlashToHardware() {
        val cam = camera ?: return
        val photo = capture
        if (!hasFlashUnit) {
            photo?.flashMode = ImageCapture.FLASH_MODE_OFF
            torchEnabled = false
            return
        }
        val wantTorch = flashOption.usesTorch
        if (wantTorch != torchEnabled) {
            torchEnabled = wantTorch
            runCatching { cam.cameraControl.enableTorch(wantTorch) }
        }
        photo?.flashMode = flashOption.toImageCaptureFlashMode()
    }

    private fun publishControlsState() {
        onControls(
            CameraControlsUiState(
                flash = if (hasFlashUnit) flashOption else FlashOption.OFF,
                hasFlash = hasFlashUnit,
                aspectRatio = aspectRatio,
                quickEvSteps = quickEvSteps,
                minEvSteps = capabilities.minEv.coerceAtLeast(-2.5f),
                maxEvSteps = capabilities.maxEv.coerceAtMost(2.5f),
                canQuickEv = exposureMode != ExposureControlMode.MANUAL,
            ),
        )
    }

    fun cycleFlash(): FlashOption {
        if (!hasFlashUnit) {
            onMessage("当前镜头不支持闪光灯")
            return FlashOption.OFF
        }
        flashOption = flashOption.next()
        applyFlashToHardware()
        publishControlsState()
        if (exposureMode == ExposureControlMode.MANUAL &&
            (flashOption == FlashOption.AUTO || flashOption == FlashOption.ON)) {
            onMessage("M 手动挡下请用「常亮补光」或切回自动曝光以触发闪光")
        }
        return flashOption
    }

    fun setAspectRatio(next: CaptureAspectRatio) {
        if (aspectRatio == next) return
        aspectRatio = next
        publishControlsState()
    }

    fun setDeviceOrientationDegrees(bucketDegrees: Int) {
        val nextRotation = DeviceOrientationHelper.toSurfaceRotation(bucketDegrees)
        if (deviceSurfaceRotation == nextRotation) return
        deviceSurfaceRotation = nextRotation
        capture?.targetRotation = nextRotation
    }

    fun focusAt(x: Float, y: Float): Boolean {
        val cam = camera ?: return false
        if (zoom.changing || view.width <= 0 || view.height <= 0) return false
        focusOnPreviewPoint(x, y)
        if (exposureMode != ExposureControlMode.MANUAL && quickEvSteps != 0f) {
            quickEvSteps = 0f
            runCatching { cam.cameraControl.setExposureCompensationIndex(0) }
            publishControlsState()
        }
        return true
    }

    fun setQuickEv(evSteps: Float) {
        val cam = camera ?: return
        if (exposureMode == ExposureControlMode.MANUAL) return
        val minEv = capabilities.minEv.coerceAtLeast(-2.5f)
        val maxEv = capabilities.maxEv.coerceAtMost(2.5f)
        val clamped = evSteps.coerceIn(minEv, maxEv)
        quickEvSteps = clamped
        val step = capabilities.evStep.takeIf { it > 0f } ?: (1f / 6f)
        val idx = (clamped / step).roundToInt().coerceIn(capabilities.evIndexRange.first, capabilities.evIndexRange.last)
        runCatching { cam.cameraControl.setExposureCompensationIndex(idx) }
        publishControlsState()
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

    fun selectCloseupProposal(proposalId: String) {
        val proposal = latestCloseups.firstOrNull { it.id == proposalId } ?: return
        val sourceFrame = latestCloseupFrame ?: latest ?: return
        val target = RememberedComposition.closeup(sourceFrame, proposal.aimUv, proposal.targetZoom) ?: return
        cancelRecommendation()
        selection = null
        latestShift = null
        latestStepProgress = null
        trackerLostSinceNs = null
        subjectLostNotified = false
        observer?.apply { watchPerson = false; tracker = null; stepTracker = null }
        val targetZoom = proposal.targetZoom.coerceIn(zoom.min, zoom.max)
        onRecommendationStatus(null)
        startGuide(target, "${proposal.kind.label} · 对准框中心")
        if (abs(zoom.ratio - targetZoom) > 0.1f) {
            setZoom(targetZoom)
        }
        onMessage("已切至 ${proposal.chipLabel}，将白点对准绿环拍摄")
    }

    fun recommend() = recommend(null, null, null)

    private fun recommend(tapUv: Uv?, tapPreview: Point?, statusOverride: String? = null) {
        if (proactivityMode == ProactivityMode.QUIET) return
        if (binding == null || zoom.changing) return
        val now = SystemClock.elapsedRealtimeNanos()
        if (statusOverride == null) {
            proactiveTrigger.onManualOrCaptured(now, poses?.latestAt(now) ?: latest?.observation?.pose)
        }
        cancelRecommendation()
        run = null
        selection = null
        latestShift = null
        latestStepProgress = null
        stepSatisfiedSinceNs = null
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

    /**
     * Launches an instant (< 15ms) on-device composition baseline so the user receives immediate
     * alignment-ring guidance even before the cloud VLM responds or while completely offline.
     */
    private fun applyOfflineBaseline(
        source: SceneFrame,
        sample: RecommendationFrame,
        capturedZoom: Float,
    ): Boolean {
        val obs = source.observation ?: return false
        val pixels = source.pixels ?: return false
        val zoomStops = if (lensFacing == CameraSelector.LENS_FACING_FRONT) emptyList() else zoom.keyRatios
        val advice = OnDeviceCompositionAdvisor.advise(
            frame = source,
            candidates = sample.candidates,
            preset = presetSelection.preset,
            currentZoom = capturedZoom,
            zoomStops = zoomStops,
            tapUv = sample.tapUv,
        ) ?: return false
        val instantOutline = SubjectOutline.instantBox(obs, advice.subject, pixels)
        val baseTarget = when (val decision = RememberedComposition.recommended(source, advice, instantOutline, capturedZoom)) {
            is PlanResult.Accepted -> decision.value
            is PlanResult.Rejected -> return false
        }
        val activePreset = presetSelection.preset
        val effectiveTarget = if (activePreset == CompositionPreset.AUTO) {
            baseTarget
        } else {
            baseTarget.withPreset(presetSelection, obs.pose, obs.lens, null)
        }
        observer?.tracker = instantOutline?.let { SubjectTracker.from(it, pixels) }
        latestShift = null
        trackerLostSinceNs = null
        subjectLostNotified = false
        val subjectName = advice.subject.name
        selection = Selection(subjectName, source.geometry, obs, instantOutline)
        val caption = if (effectiveTarget === baseTarget) {
            advice.caption
        } else {
            effectiveTarget.choice?.let { "$subjectName·${it.label}" } ?: advice.caption
        }
        startGuide(effectiveTarget, caption, subjectName)
        return true
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
        val capturedZoom = appliedZoom
        val hasBaseline = applyOfflineBaseline(source, sample, capturedZoom)
        LumiTelemetry.recordAiTriggered(hasBaseline)
        val cloudConfigured = token.config.validationError(BuildConfig.DEBUG) == null
        if (!cloudConfigured) {
            sample.close()
            requests.finish(token, SystemClock.elapsedRealtimeNanos())
            handler.removeCallbacks(timeout)
            call = null
            requestGeometry = null
            pendingCandidates = emptyList()
            pendingCandidateGeometry = null
            pendingTapPreview = null
            if (hasBaseline) {
                onRecommendationStatus(null)
                onMessage("端侧离线构图已就绪（配置云端模型可开启高级导演）")
            } else {
                onRecommendationStatus("当前画面未识别到明确主体，请调整角度后重试。")
            }
            render()
            return
        }
        val task = VisionChat(token.config, token.deadlineNs)
        call = task
        requestGeometry = source.geometry
        pendingCandidates = sample.candidates
        pendingCandidateGeometry = source.geometry
        render()
        val capturedZoomStops = if (lensFacing == CameraSelector.LENS_FACING_FRONT) emptyList() else zoom.keyRatios
        val capturedFront = lensFacing == CameraSelector.LENS_FACING_FRONT
        val capturedPreset = presetSelection.preset
        val candidates = sample.candidates
        val userTapped = sample.tapUv != null
        val preferencePrior = aestheticPrefs.read().promptPrior()
        val sessionMemoryHint = sessionMemory.promptSummary(System.currentTimeMillis())
        val telemetrySummary = currentTelemetry(source).promptSummary(capabilities)
        val requestExposureAdvice = exposureMode != ExposureControlMode.AUTO
        val requestFilterAdvice = filterMode == FilterSelectionMode.AI_SMART ||
            (filterPreset != FilterPreset.ORIGINAL && filterAiAdaptive)
        val capturedFilterPreset = if (filterMode == FilterSelectionMode.MANUAL) filterPreset else null
        val capturedRefRecipe = referenceState.recipe
        val capturedRefJpeg = referenceCompareJpeg
        onRecommendationStatus(
            when {
                hasBaseline && capturedRefJpeg != null -> "端侧已就绪 · 云端对照参考图精修中…"
                hasBaseline -> "端侧已就绪 · 云端精修中…"
                capturedRefJpeg != null && userTapped -> "正在对照参考图分析所选人物与超越方案…"
                capturedRefJpeg != null -> "正在对照参考图生成复刻与超越建议…"
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
                Result.success(
                    task.send(
                        jpeg, capturedPreset, candidates, capturedZoomStops, capturedZoom,
                        preferencePrior, telemetrySummary, requestExposureAdvice,
                        capturedRefRecipe, capturedRefJpeg,
                        requestFilterAdvice, capturedFilterPreset,
                        sessionMemoryHint,
                    )
                )
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
        val hadOfflineBaseline = run?.target?.isOfflineBaseline == true
        if (completion == RecommendationRequests.Completion.EXPIRED) {
            LumiTelemetry.recordCloudOutcome(succeeded = false)
            saveDiagnostic("EXPIRED", times, SystemClock.elapsedRealtimeNanos(), sentJpeg, capturedFront, capturedZoom, source.observation, result.getOrNull(), result.exceptionOrNull(), attempt, candidateCount, userTapped)
            if (hadOfflineBaseline) {
                onRecommendationStatus(null)
                onMessage("云端响应超时，已保持端侧离线构图引导")
            } else {
                onRecommendationStatus(VisionChat.TIMEOUT_NOTICE)
            }
            render()
            return
        }
        val reply = result.getOrElse {
            val decided = SystemClock.elapsedRealtimeNanos()
            LumiTelemetry.recordCloudOutcome(succeeded = false)
            recordDecision("REQUEST_FAILED", times, decided)
            saveDiagnostic("REQUEST_FAILED", times, decided, sentJpeg, capturedFront, capturedZoom, source.observation, null, it, attempt, candidateCount, userTapped)
            if (hadOfflineBaseline) {
                onRecommendationStatus(null)
                onMessage("网络不可用，已保持端侧离线构图引导")
            } else {
                onRecommendationStatus(when (it) {
                    is ModelFailure -> it.notice
                    is SocketTimeoutException -> VisionChat.TIMEOUT_NOTICE
                    else -> "推荐请求失败，请检查连接后重试。"
                })
            }
            render()
            return
        }
        fun reject(problem: PlanProblem) {
            val decided = SystemClock.elapsedRealtimeNanos()
            recordDecision(problem.name, times, decided)
            saveDiagnostic(problem.name, times, decided, sentJpeg, capturedFront, capturedZoom, source.observation, reply, null, attempt, candidateCount, userTapped)
            if (hadOfflineBaseline) {
                onRecommendationStatus(null)
            } else {
                observer?.watchPerson = false
                view.removeCallbacks(drawDirection)
                if (attempt?.outline != null) view.postOnAnimation(drawDirection)
                onRecommendationStatus(problem.notice)
            }
            render()
        }
        val outline = attempt?.outline
        val plan = when (val decision = reply.decision) {
            is PlanResult.Accepted -> decision.value
            is PlanResult.Rejected -> return reject(decision.problem)
        }
        val baseTarget = when (val decision = RememberedComposition.recommended(source, plan, outline, capturedZoom)) {
            is PlanResult.Accepted -> decision.value
            is PlanResult.Rejected -> return reject(decision.problem)
        }
        val subjectTracker = if (outline != null && source.pixels != null) SubjectTracker.from(outline, source.pixels) else null
        latestShift = null
        trackerLostSinceNs = null
        subjectLostNotified = false
        observer?.tracker = subjectTracker
        selection = Selection(token.config.safeText(reply.name), source.geometry, source.observation, outline)
        val stepDir = plan.stepDirection
        if (stepDir != null && outline != null && source.pixels != null) {
            observer?.stepTracker = ParallaxStepTracker.from(stepDir, outline, source.pixels)
            latestStepProgress = StepProgress(stepDir, 0f, satisfied = false)
        } else {
            observer?.stepTracker = null
            latestStepProgress = null
        }
        val aiExp = plan.exposureSuggestion
        if (aiExp != null) {
            latestAiExposure = aiExp
            if (exposureMode == ExposureControlMode.AI_AUTO) {
                if (proactivityMode == ProactivityMode.PROACTIVE) {
                    appliedAiExposure = aiExp
                    pendingAiExposure = null
                    applyExposureToHardware()
                } else if (proactivityMode == ProactivityMode.MANUAL) {
                    pendingAiExposure = aiExp
                }
            }
            publishExposureState()
        } else if (pendingAiExposure != null) {
            pendingAiExposure = null
            publishExposureState()
        }
        val aiFilter = plan.filterSuggestion
        if (aiFilter != null) {
            aiFilterSuggestion = aiFilter
            refreshFilterState()
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
        LumiTelemetry.recordCloudOutcome(
            succeeded = true,
            latencyMs = (times.replied - times.sent).coerceAtLeast(0L) / 1_000_000L,
        )
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
        val captureSubjectName = run?.subjectName ?: selection?.name
        val captureZoom = zoom.ratio
        val captureLuma = latest?.meanLuma
        val captureClutter = latest?.backgroundClutter
        val captureFaceBox = latest?.faces?.maxByOrNull { (it.right - it.left) * (it.bottom - it.top) }
        val captureUnresolvedStep = run?.target?.stepDirection != null && latestStepProgress?.satisfied != true
        val activeGrade = filterState.resolvedGrade
        val activeAspect = aspectRatio
        val frontMirror = lensFacing == CameraSelector.LENS_FACING_FRONT
        photo.targetRotation = deviceSurfaceRotation
        photo.flashMode = if (hasFlashUnit) flashOption.toImageCaptureFlashMode() else ImageCapture.FLASH_MODE_OFF

        val shutterStartedMs = SystemClock.elapsedRealtime()
        fun finishSaved(uri: Uri?, filterSaveMs: Long? = null) {
            if (disposed) return
            val shutterElapsedMs = (SystemClock.elapsedRealtime() - shutterStartedMs).coerceAtLeast(0L)
            LumiTelemetry.recordCapture(
                wasReady = captureReady,
                shutterLatencyMs = shutterElapsedMs,
                filterSaveMs = filterSaveMs,
            )
            proactiveTrigger.onManualOrCaptured(SystemClock.elapsedRealtimeNanos(), capturePose)
            aestheticPrefs.recordComposition(captureChoice, captureZoom)
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
                    faceBox = captureFaceBox,
                )
                val captureLightTag = latest?.lumaStats?.lightGuidance?.position?.name
                sessionMemory.record(
                    SessionShotRecord(
                        capturedAtMs = System.currentTimeMillis(),
                        subjectName = captureSubjectName,
                        choice = captureChoice,
                        zoomRatio = captureZoom,
                        wasReady = captureReady,
                        rollDeg = captureRoll,
                        lightTag = captureLightTag,
                        tipGiven = captureCue?.tip,
                    ),
                )
                onCaptured(review)
            } else {
                onMessage("照片已保存到系统相册。")
            }
        }

        if (!activeGrade.isIdentity || activeAspect != CaptureAspectRatio.RATIO_3_4) {
            if (capturePipeline.pendingCount >= 4) {
                onMessage("正在处理前几张照片，请稍候…")
                return
            }
            photo.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rotationDeg = image.imageInfo.rotationDegrees
                    val bytes = try {
                        val buffer = image.planes[0].buffer
                        ByteArray(buffer.remaining()).also { buffer.get(it) }
                    } finally {
                        image.close()
                    }
                    val accepted = capturePipeline.submit(
                        work = {
                            val filterStartMs = SystemClock.elapsedRealtime()
                            val saved = PhotoFilterRenderer.saveFilteredJpeg(
                                context = context,
                                jpegBytes = bytes,
                                grade = activeGrade,
                                mirrorHorizontal = frontMirror,
                                fallbackRotationDegrees = rotationDeg,
                                aspectRatio = activeAspect,
                            )
                            saved to (SystemClock.elapsedRealtime() - filterStartMs).coerceAtLeast(0L)
                        },
                        onComplete = { outcome ->
                            val savedUri = outcome?.first
                            val filterMs = outcome?.second
                            if (savedUri != null) {
                                finishSaved(savedUri, filterSaveMs = filterMs)
                            } else if (!disposed) {
                                onMessage("保存失败，请检查相册权限和存储空间。")
                            }
                        },
                    )
                    if (!accepted) {
                        main.execute {
                            if (!disposed) onMessage("正在处理前几张照片，请稍候…")
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.w("LumiCamera", "Filtered capture failed", exception)
                    main.execute {
                        if (!disposed) onMessage("保存失败，请检查相册权限和存储空间。")
                    }
                }
            })
            return
        }

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Lumi_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Lumi")
            }
        }
        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = frontMirror
        }
        val output = ImageCapture.OutputFileOptions.Builder(context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            .setMetadata(metadata)
            .build()
        photo.takePicture(output, main, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                finishSaved(result.savedUri)
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
        if (torchEnabled) {
            runCatching { camera?.cameraControl?.enableTorch(false) }
            torchEnabled = false
        }
        zoomGeneration++
        zoomSinceNs = 0L
        logZoomFrame = true
        camera = null
        hasFlashUnit = false
        quickEvSteps = 0f
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
        stepSatisfiedSinceNs = null
        lightStabilizer.reset()
        trackerLostSinceNs = null
        subjectLostNotified = false
        lastReadyState = false
        proactiveTrigger.reset()
        run = null
        selection = null
        poses?.close()
        poses = null
        lightSensor?.close()
        lightSensor = null
        pendingAiExposure = null
        appliedAiExposure = null
        handler.removeCallbacks(expire)
        uses.filterIsInstance<ImageAnalysis>().forEach { it.clearAnalyzer() }
        if (uses.isNotEmpty()) provider?.unbind(*uses)
        previewFilterEffect?.release()
        previewFilterEffect = null
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
        referenceCall?.cancel(network)
        referenceCall = null
        owner.lifecycle.removeObserver(this)
        view.removeOnLayoutChangeListener(layout)
        displays.unregisterDisplayListener(this)
        releaseBinding()
        capturePipeline.close()
        worker.shutdown()
        network.shutdown()
    }

    companion object {
        private const val SUBJECT_LOST_NS = 450_000_000L
        private const val ZOOM_SETTLE_NS = 260_000_000L
        private const val STEP_SATISFIED_DISPLAY_NS = 1_200_000_000L
    }
}
