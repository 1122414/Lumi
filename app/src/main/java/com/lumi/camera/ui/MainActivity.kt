package com.lumi.camera.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.lumi.camera.BuildConfig
import com.lumi.camera.camera.CameraControlsUiState
import com.lumi.camera.camera.CameraPermissions
import com.lumi.camera.camera.CameraSession
import com.lumi.camera.camera.CandidateOverlay
import com.lumi.camera.camera.CaptureAspectRatio
import com.lumi.camera.camera.CaptureQueueState
import com.lumi.camera.camera.CloseupOverlay
import com.lumi.camera.camera.DeviceOrientationHelper
import com.lumi.camera.camera.ExposureControlMode
import com.lumi.camera.camera.ExposureSolver
import com.lumi.camera.camera.FlashOption
import com.lumi.camera.camera.LiveTelemetry
import com.lumi.camera.camera.NativeProcessingMode
import com.lumi.camera.camera.NativeProcessingUiState
import com.lumi.camera.camera.Point
import com.lumi.camera.camera.TimerOption
import com.lumi.camera.camera.Viewfinder
import com.lumi.camera.camera.ViewfinderLens
import com.lumi.camera.camera.ViewfinderZoom
import com.lumi.camera.filter.FilterAdjuster
import com.lumi.camera.filter.FilterPreset
import com.lumi.camera.filter.FilterSelectionMode
import com.lumi.camera.filter.FilterThumbnailRenderer
import com.lumi.camera.filter.FilterUiState
import com.lumi.camera.gallery.GalleryPhotoItem
import com.lumi.camera.gallery.LumiGalleryStore
import com.lumi.camera.guidance.PoseGuidance
import com.lumi.camera.observability.LumiCrashGuard
import com.lumi.camera.observability.LumiTelemetry
import com.lumi.camera.shotplan.CompositionChoice
import com.lumi.camera.shotplan.CompositionPreset
import com.lumi.camera.shotplan.ConnectionResult
import com.lumi.camera.shotplan.ModelConfig
import com.lumi.camera.shotplan.ModelConfigStore
import com.lumi.camera.shotplan.PresetSelection
import com.lumi.camera.shotplan.ProactivityMode
import com.lumi.camera.shotplan.SubjectCandidates
import com.lumi.camera.vision.PostShotRecrop
import com.lumi.camera.vision.PostShotReview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private enum class BottomTray {
    NONE,
    FILTER,
    EXPOSURE,
}

class MainActivity : ComponentActivity() {
    private var allowed by mutableStateOf(false)
    private var requested by mutableStateOf(false)
    private var session: CameraSession? = null
    internal var hardwareShutterHandler: (() -> Boolean)? = null
    private val permissions = CameraPermissions(this, { granted ->
        allowed = granted
        requested = true
    }, { granted ->
        if (granted) session?.save() else notifyUser("未获准写入系统相册。请在系统设置中允许存储权限后重试。")
    })

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val handler = hardwareShutterHandler
            if (handler != null) {
                if (event?.repeatCount == 0) handler()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        allowed = permissions.hasCamera()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requested = savedInstanceState?.getBoolean("requested") ?: false
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        setContent {
            val store = remember { ModelConfigStore(applicationContext) }
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = LumiColors.Ivory,
                    onPrimary = LumiColors.IvoryInk,
                    background = LumiColors.Obsidian,
                    surface = LumiColors.SurfaceDark,
                ),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(LumiColors.Obsidian)
                        .safeDrawingPadding(),
                ) {
                    if (allowed) {
                        val initial by produceState<ModelConfig?>(null, store) {
                            value = withContext(Dispatchers.IO) { store.read() }
                        }
                        initial?.let {
                            CameraScreen(
                                this@MainActivity,
                                store,
                                it,
                                { session = it },
                                permissions::requestAlbum,
                                ::notifyUser,
                            )
                        }
                    } else {
                        CameraPermissionGate(
                            requested = requested,
                            onRequestPermission = permissions::requestCamera,
                            onOpenSettings = {
                                startActivity(Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", packageName, null),
                                ))
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("requested", requested)
        super.onSaveInstanceState(outState)
    }

    private fun notifyUser(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}

@Composable
private fun CameraScreen(
    owner: MainActivity,
    store: ModelConfigStore,
    initial: ModelConfig,
    onSession: (CameraSession?) -> Unit,
    onShoot: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val hostView = LocalView.current
    val preview = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val uiPrefs = remember(context) {
        context.getSharedPreferences("lumi_ui_prefs", android.content.Context.MODE_PRIVATE)
    }

    var frame by remember { mutableStateOf(Viewfinder()) }
    var paintRing by remember { mutableStateOf<Point?>(null) }
    var bound by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(ViewfinderZoom()) }
    var lens by remember { mutableStateOf(ViewfinderLens()) }
    var presetSelection by remember { mutableStateOf(PresetSelection()) }
    var recommendationStatus by remember { mutableStateOf<String?>(null) }
    var config by remember { mutableStateOf(initial) }
    val configured = remember(config) { config.validationError(BuildConfig.DEBUG) == null }
    var lastFailed by remember { mutableStateOf(store.lastFailed) }

    var modelSettingsOpen by remember { mutableStateOf(false) }
    var cameraSettingsOpen by remember { mutableStateOf(false) }
    var privacyHealthOpen by remember { mutableStateOf(false) }
    var firstLaunchConsentOpen by remember { mutableStateOf(!PrivacyConsentStore.hasAccepted(context)) }
    var onboardingFlow by remember { mutableStateOf(OnboardingStore.load(context)) }
    val onboardingAnchors = remember { mutableStateMapOf<OnboardingTarget, Rect>() }
    fun updateOnboarding(next: OnboardingFlow) {
        onboardingFlow = next
        OnboardingStore.save(context, next)
    }
    var aiMenuOpen by remember { mutableStateOf(false) }
    var diagnosticOpen by remember { mutableStateOf(false) }
    var quickControlsOpen by remember { mutableStateOf(false) }
    var activeTray by remember { mutableStateOf(BottomTray.NONE) }
    var showGrid by remember { mutableStateOf(uiPrefs.getBoolean("show_grid", true)) }
    var showPoseSilhouette by remember { mutableStateOf(uiPrefs.getBoolean("show_pose_silhouette", true)) }

    var proactivityMode by remember {
        mutableStateOf(ProactivityMode.fromWire(uiPrefs.getString("proactivity_mode", null)))
    }
    var exposureMode by remember {
        mutableStateOf(ExposureControlMode.fromWire(uiPrefs.getString("exposure_mode", null)))
    }
    var nativeProcessingMode by remember {
        mutableStateOf(NativeProcessingMode.fromWire(uiPrefs.getString("native_processing_mode", null)))
    }
    val initialFlash = remember {
        FlashOption.fromWire(uiPrefs.getString("flash_option", null)).let {
            if (it == FlashOption.TORCH) FlashOption.OFF else it
        }
    }
    val initialAspectRatio = remember {
        CaptureAspectRatio.fromWire(uiPrefs.getString("aspect_ratio", null))
    }
    var timerOption by remember {
        mutableStateOf(TimerOption.fromWire(uiPrefs.getString("timer_option", null)))
    }
    var controlsState by remember {
        mutableStateOf(CameraControlsUiState(flash = initialFlash, aspectRatio = initialAspectRatio))
    }
    var deviceBucketDeg by remember { mutableIntStateOf(0) }
    val uiIconRotation by animateFloatAsState(
        targetValue = DeviceOrientationHelper.toUiIconRotation(deviceBucketDeg),
        animationSpec = tween(220),
        label = "gravity icon rotation",
    )
    var focusTap by remember { mutableStateOf<FocusTapState?>(null) }
    var evDragActive by remember { mutableStateOf(false) }
    var viewfinderWidthPx by remember { mutableFloatStateOf(0f) }
    var viewfinderHeightPx by remember { mutableFloatStateOf(0f) }
    var countdownRemaining by remember { mutableIntStateOf(0) }
    var shutterFlashAlpha by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val initialFilterMode = remember {
        FilterSelectionMode.fromWire(uiPrefs.getString("filter_mode", null))
    }
    val initialFilterPreset = remember {
        FilterPreset.fromId(uiPrefs.getString("filter_preset", null)) ?: FilterPreset.ORIGINAL
    }
    val initialFilterIntensity = remember {
        uiPrefs.getFloat("filter_intensity", FilterAdjuster.DEFAULT_INTENSITY).coerceIn(0f, 1f)
    }
    val initialFilterAiAdaptive = remember {
        uiPrefs.getBoolean("filter_ai_adaptive", true)
    }

    var nativeProcessingState by remember {
        mutableStateOf(NativeProcessingUiState(mode = nativeProcessingMode))
    }
    var filterState by remember {
        mutableStateOf(
            FilterUiState(
                mode = initialFilterMode,
                selectedPreset = initialFilterPreset,
                intensity = initialFilterIntensity,
                aiAdaptiveEnabled = initialFilterAiAdaptive,
                resolvedPreset = initialFilterPreset,
            ),
        )
    }
    var exposureState by remember { mutableStateOf(ExposureUiState(mode = exposureMode)) }
    var referenceState by remember { mutableStateOf(ReferenceUiState()) }
    var referenceDialogOpen by remember { mutableStateOf(false) }
    var lastReview by remember { mutableStateOf<PostShotReview?>(null) }
    var activeReviewDialogTarget by remember { mutableStateOf<PostShotReview?>(null) }
    var reviewBannerVisible by remember { mutableStateOf(false) }
    var galleryPhotos by remember { mutableStateOf<List<GalleryPhotoItem>>(emptyList()) }
    var galleryViewerOpen by remember { mutableStateOf(false) }
    var captureQueue by remember { mutableStateOf(CaptureQueueState()) }
    var filterLiveThumbnails by remember { mutableStateOf<Map<FilterPreset, android.graphics.Bitmap>>(emptyMap()) }
    var autoShoot by remember { mutableStateOf(false) }
    var autoShootProgress by remember { mutableFloatStateOf(0f) }
    var autoShotFired by remember { mutableStateOf(false) }
    var wasReady by remember { mutableStateOf(false) }
    var wasHorizonLevel by remember { mutableStateOf(false) }

    fun refreshGallery() {
        scope.launch {
            galleryPhotos = withContext(Dispatchers.IO) { LumiGalleryStore.loadPhotos(context) }
        }
    }

    LaunchedEffect(Unit) {
        galleryPhotos = withContext(Dispatchers.IO) { LumiGalleryStore.loadPhotos(context) }
    }

    fun setFailure(failed: Boolean) {
        if (lastFailed != failed) {
            store.lastFailed = failed
            lastFailed = failed
        }
    }

    val session = remember(preview, owner) {
        CameraSession(
            preview,
            owner,
            { next ->
                frame = next
                if (next.ring != null || !next.hasTarget) paintRing = next.ring
            },
            { bound = it },
            onMessage,
            { recommendationStatus = it },
            config,
            { setFailure(it != ConnectionResult.SUCCESS) },
            { zoom = it },
            { lens = it },
            { presetSelection = it },
            onCaptured = { review ->
                lastReview = review
                val nextOnboarding = onboardingFlow.onPhotoCaptured()
                if (nextOnboarding != onboardingFlow) {
                    updateOnboarding(nextOnboarding)
                    reviewBannerVisible = false
                } else {
                    reviewBannerVisible = true
                }
                refreshGallery()
            },
            onExposure = { nextExp ->
                exposureState = nextExp
                exposureMode = nextExp.mode
            },
            onReference = { nextRef ->
                referenceState = nextRef
            },
            onNativeProcessing = { nextProc ->
                nativeProcessingState = nextProc
                nativeProcessingMode = nextProc.mode
            },
            onFilter = { nextFilter ->
                filterState = nextFilter
            },
            onControls = { nextControls ->
                controlsState = nextControls
            },
            onCaptureQueue = { nextQueue ->
                captureQueue = nextQueue
            },
            initialProactivity = proactivityMode,
            initialExposureMode = exposureMode,
            initialNativeProcessing = nativeProcessingMode,
            initialFilterMode = initialFilterMode,
            initialFilterPreset = initialFilterPreset,
            initialFilterIntensity = initialFilterIntensity,
            initialFilterAiAdaptive = initialFilterAiAdaptive,
            initialFlash = initialFlash,
            initialAspectRatio = initialAspectRatio,
        )
    }

    val pickReferenceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) session.loadReferenceImage(uri)
    }
    fun launchReferencePicker() {
        pickReferenceLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    LaunchedEffect(lastReview?.capturedAtMs) {
        if (lastReview != null) {
            reviewBannerVisible = true
            delay(3200L)
            reviewBannerVisible = false
        }
    }
    fun fireImmediateShutter() {
        hostView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        scope.launch {
            shutterFlashAlpha = 0.72f
            delay(75L)
            shutterFlashAlpha = 0f
        }
        onShoot()
    }

    fun triggerShutter() {
        if (!bound || zoom.changing) return
        if (countdownRemaining > 0) {
            countdownRemaining = 0
            hostView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            return
        }
        if (timerOption == TimerOption.OFF) {
            fireImmediateShutter()
        } else {
            countdownRemaining = timerOption.seconds
        }
    }

    LaunchedEffect(countdownRemaining) {
        if (countdownRemaining <= 0) return@LaunchedEffect
        hostView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        delay(1000L)
        if (countdownRemaining == 1) {
            countdownRemaining = 0
            fireImmediateShutter()
        } else if (countdownRemaining > 1) {
            countdownRemaining -= 1
        }
    }

    LaunchedEffect(activeTray, bound) {
        val enabled = activeTray == BottomTray.FILTER && bound
        session.setFilterPreviewEnabled(enabled)
        if (!enabled) return@LaunchedEffect
        while (true) {
            val thumbs = withContext(Dispatchers.Default) {
                val square = session.captureFilterPreviewBitmap() ?: return@withContext null
                FilterThumbnailRenderer.renderAllPresets(square)
            }
            if (thumbs != null) {
                filterLiveThumbnails = thumbs
            }
            delay(380L)
        }
    }

    LaunchedEffect(focusTap?.token) {
        if (focusTap == null) return@LaunchedEffect
        delay(2800L)
        focusTap = null
        evDragActive = false
    }

    DisposableEffect(context, session) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                val next = DeviceOrientationHelper.resolveBucketDegrees(orientation, deviceBucketDeg)
                if (next != deviceBucketDeg) {
                    deviceBucketDeg = next
                    session.setDeviceOrientationDegrees(next)
                }
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }

    val onboardingVisible = !firstLaunchConsentOpen && onboardingFlow.isOverlayVisible
    LaunchedEffect(onboardingFlow.step) {
        if (onboardingFlow.step == OnboardingStep.COMPOSITION_MODES) {
            activeTray = BottomTray.NONE
        }
    }
    val anyDialogOpen = cameraSettingsOpen || modelSettingsOpen || diagnosticOpen ||
        activeReviewDialogTarget != null || referenceDialogOpen || galleryViewerOpen ||
        firstLaunchConsentOpen || privacyHealthOpen || onboardingVisible
    DisposableEffect(owner, bound, zoom.changing, anyDialogOpen, timerOption, countdownRemaining) {
        owner.hardwareShutterHandler = if (!anyDialogOpen) {
            {
                if (bound && !zoom.changing) {
                    triggerShutter()
                    true
                } else {
                    false
                }
            }
        } else {
            null
        }
        onDispose { owner.hardwareShutterHandler = null }
    }

    DisposableEffect(session) {
        onSession(session)
        session.attach()
        onDispose { onSession(null); session.dispose() }
    }

    LaunchedEffect(frame.ready) {
        if (frame.ready && !wasReady) {
            val code = if (android.os.Build.VERSION.SDK_INT >= 30) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.KEYBOARD_TAP
            }
            hostView.performHapticFeedback(code)
        }
        wasReady = frame.ready
        if (!frame.ready) autoShotFired = false
    }

    LaunchedEffect(frame.rollDeg) {
        val roll = frame.rollDeg ?: return@LaunchedEffect
        val absRoll = abs(roll)
        if (!wasHorizonLevel && absRoll <= 0.8) {
            wasHorizonLevel = true
            hostView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        } else if (wasHorizonLevel && absRoll >= 2.2) {
            wasHorizonLevel = false
        }
    }

    LaunchedEffect(frame.ready, autoShoot, autoShotFired, frame.suggestedZoom, frame.stepProgress?.satisfied) {
        if (!autoShoot || !frame.ready || autoShotFired || frame.suggestedZoom != null || frame.stepProgress?.satisfied == false) {
            autoShootProgress = 0f
            return@LaunchedEffect
        }
        val totalMs = 450L
        val stepMs = 30L
        var elapsed = 0L
        while (elapsed < totalMs) {
            autoShootProgress = (elapsed.toFloat() / totalMs).coerceIn(0f, 1f)
            delay(stepMs)
            elapsed += stepMs
        }
        autoShootProgress = 1f
        autoShotFired = true
        fireImmediateShutter()
    }

    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(frame.merged) {
        settled = false
        if (frame.merged) {
            delay(500)
            settled = true
        }
    }
    val showRing = frame.ring != null && !(frame.merged && settled)
    val ringAlpha by animateFloatAsState(
        if (showRing) 1f else 0f,
        if (showRing) snap() else tween(120),
        label = "ring visibility",
    )

    val modelDotColor = when {
        !configured -> LumiColors.Amber
        lastFailed -> LumiColors.Danger
        else -> LumiColors.Emerald
    }
    val modelStatusLabel = when {
        !configured -> "未配置"
        lastFailed -> "连接异常"
        else -> "可用"
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
    // 1. Minimalist Top Bar (Wordmark + Quick Ratio/Timer pill | Flash, Flip, Reference, AI mode, Settings)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button) { modelSettingsOpen = true }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Lumi",
                    color = LumiColors.TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.4.sp,
                )
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(modelDotColor),
                )
            }

            val quickBadgeActive = quickControlsOpen ||
                controlsState.aspectRatio != CaptureAspectRatio.RATIO_3_4 ||
                timerOption != TimerOption.OFF
            val quickLabel = buildString {
                append(controlsState.aspectRatio.label)
                if (timerOption != TimerOption.OFF) append("·${timerOption.label}")
            }
            Surface(
                onClick = { quickControlsOpen = !quickControlsOpen },
                shape = RoundedCornerShape(12.dp),
                color = if (quickBadgeActive) LumiColors.IvoryMuted else Color(0x16FFFFFF),
                border = BorderStroke(
                    1.dp,
                    if (quickBadgeActive) LumiColors.Ivory.copy(alpha = 0.65f) else LumiColors.Hairline,
                ),
            ) {
                Text(
                    text = quickLabel,
                    color = if (quickBadgeActive) LumiColors.Ivory else LumiColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (controlsState.hasFlash) {
                val flashActive = controlsState.flash != FlashOption.OFF
                TopIconButton(
                    description = controlsState.flash.label,
                    active = flashActive,
                    enabled = bound && !zoom.changing,
                    onClick = {
                        val next = session.cycleFlash()
                        uiPrefs.edit().putString("flash_option", next.wire).apply()
                        onMessage(next.label)
                    },
                ) {
                    Box(Modifier.graphicsLayer { rotationZ = uiIconRotation }) {
                        FlashIcon(
                            option = controlsState.flash,
                            tint = if (flashActive) LumiColors.Ivory else LumiColors.TextPrimary,
                        )
                    }
                }
            }

            if (lens.canSwitch) {
                TopIconButton(
                    description = if (lens.front) "切换到后置摄像头" else "切换到前置摄像头",
                    enabled = bound && !zoom.changing,
                    onClick = session::switchCamera,
                ) {
                    Box(Modifier.graphicsLayer { rotationZ = uiIconRotation }) {
                        LensFlipIcon(
                            tint = if (bound && !zoom.changing) LumiColors.TextPrimary else LumiColors.TextMuted,
                        )
                    }
                }
            }

            TopIconButton(
                description = "导入或管理参考图",
                active = referenceState.hasReference,
                modifier = Modifier.onGloballyPositioned { coords ->
                    onboardingAnchors[OnboardingTarget.REFERENCE_PHOTO] = coords.boundsInRoot()
                },
                onClick = {
                    if (referenceState.hasReference) referenceDialogOpen = true
                    else launchReferencePicker()
                },
            ) {
                Box(Modifier.graphicsLayer { rotationZ = uiIconRotation }) {
                    ReferenceFrameIcon(
                        tint = if (referenceState.hasReference) LumiColors.Ivory else LumiColors.TextPrimary,
                    )
                }
            }

            Box {
                val aiHighlighted = proactivityMode == ProactivityMode.PROACTIVE || autoShoot
                TopIconButton(
                    description = "AI 主动程度与自动拍",
                    active = aiHighlighted,
                    onClick = { aiMenuOpen = true },
                ) {
                    Box(Modifier.graphicsLayer { rotationZ = uiIconRotation }) {
                        AiModeReticleIcon(
                            tint = when {
                                autoShoot -> LumiColors.Emerald
                                proactivityMode == ProactivityMode.PROACTIVE -> LumiColors.Ivory
                                proactivityMode == ProactivityMode.QUIET -> LumiColors.TextMuted
                                else -> LumiColors.TextPrimary
                            },
                        )
                    }
                }
                DropdownMenu(
                    expanded = aiMenuOpen,
                    onDismissRequest = { aiMenuOpen = false },
                    containerColor = LumiColors.SurfaceDark,
                ) {
                    ProactivityMode.entries.forEach { mode ->
                        val selected = proactivityMode == mode
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = buildString {
                                        append(if (selected) "✓ " else "   ")
                                        append("AI ${mode.label}模式")
                                        append(
                                            when (mode) {
                                                ProactivityMode.PROACTIVE -> "（停稳自动分析）"
                                                ProactivityMode.MANUAL -> "（点按触发）"
                                                ProactivityMode.QUIET -> "（仅水平仪）"
                                            },
                                        )
                                    },
                                    color = if (selected) LumiColors.Ivory else LumiColors.TextPrimary,
                                    fontSize = 13.sp,
                                )
                            },
                            onClick = {
                                aiMenuOpen = false
                                proactivityMode = mode
                                uiPrefs.edit().putString("proactivity_mode", mode.wire).apply()
                                session.setProactivityMode(mode)
                            },
                        )
                    }
                    HorizontalDivider(color = LumiColors.Hairline)
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (autoShoot) "✓ 到位自动快门 · 已开启" else "   到位自动快门 · 关闭",
                                color = if (autoShoot) LumiColors.Emerald else LumiColors.TextPrimary,
                                fontSize = 13.sp,
                            )
                        },
                        onClick = {
                            aiMenuOpen = false
                            autoShoot = !autoShoot
                            onMessage(if (autoShoot) "已开启到位自动快门" else "已关闭自动快门")
                        },
                    )
                }
            }

            TopIconButton(
                description = "相机设置",
                onClick = { cameraSettingsOpen = true },
            ) {
                Box(Modifier.graphicsLayer { rotationZ = uiIconRotation }) {
                    SettingsHexIcon(tint = LumiColors.TextPrimary)
                }
            }
        }
    }

    // 2. 3:4 Live Viewfinder Hero
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .background(Color.Black)
                .onSizeChanged { sz ->
                    viewfinderWidthPx = sz.width.toFloat()
                    viewfinderHeightPx = sz.height.toFloat()
                },
        ) {
            AndroidView({ preview }, Modifier.matchParentSize())

            ReferenceOverlayLayer(
                state = referenceState,
                modifier = Modifier.matchParentSize(),
            )

            Canvas(
                Modifier
                    .matchParentSize()
                    .clipToBounds()
                    .pointerInput(session) {
                        detectTapGestures(
                            onTap = { offset ->
                                if (activeTray != BottomTray.NONE) activeTray = BottomTray.NONE
                                if (quickControlsOpen) quickControlsOpen = false
                                if (session.focusAt(offset.x, offset.y)) {
                                    focusTap = FocusTapState(offset.x, offset.y, System.nanoTime())
                                    evDragActive = false
                                }
                            },
                            onLongPress = { offset ->
                                if (activeTray != BottomTray.NONE) activeTray = BottomTray.NONE
                                if (quickControlsOpen) quickControlsOpen = false
                                focusTap = null
                                evDragActive = false
                                hostView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                session.tapSubject(offset.x, offset.y)
                            },
                        )
                    }
                    .pointerInput(session) {
                        detectTransformGestures { _, pan, scale, _ ->
                            if (abs(scale - 1f) > 0.002f) {
                                session.scaleZoom(scale)
                            } else {
                                val activeFocus = focusTap
                                if (activeFocus != null && controlsState.canQuickEv && abs(pan.y) > 0.4f) {
                                    val deltaEv = -pan.y / 210f
                                    session.setQuickEv(controlsState.quickEvSteps + deltaEv)
                                    evDragActive = true
                                    focusTap = activeFocus.copy(token = System.nanoTime())
                                }
                            }
                        }
                    },
            ) {
                drawAspectRatioMask(controlsState.aspectRatio)
                drawCompositionGuides(presetSelection, showGrid)
                if (showPoseSilhouette) {
                    frame.poseSilhouette?.let { drawPoseSilhouette(it) }
                }
                drawCloseupFrames(frame.closeups)
                drawCandidateBrackets(frame.candidates)
                frame.tapPoint?.let { tap -> drawTapReticle(tap) }
                val points = frame.outline
                if (points.isNotEmpty()) {
                    val path = Path()
                    val gap = points.indexOf(null)
                    var drawing = false
                    for (i in points.indices) {
                        val p = points[(i + gap + 1) % points.size]
                        if (p == null) drawing = false
                        else if (drawing) path.lineTo(p.x, p.y)
                        else {
                            path.moveTo(p.x, p.y)
                            drawing = true
                        }
                    }
                    if (gap == -1) path.close()
                    drawPath(
                        path,
                        Color(0xFF46A9FF),
                        style = Stroke(
                            width = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                        ),
                    )
                }
                val mid = center
                frame.rollDeg?.let { roll -> drawHorizonLevel(mid, roll) }
                paintRing?.takeIf { ringAlpha > 0f }?.let {
                    val dx = it.x - mid.x
                    val dy = it.y - mid.y
                    val radius = 11.dp.toPx()
                    val inset = radius + 3.dp.toPx()
                    val scaleX = if (dx == 0f) 1f else (mid.x - inset) / abs(dx)
                    val scaleY = if (dy == 0f) 1f else (mid.y - inset) / abs(dy)
                    val scale = minOf(1f, scaleX, scaleY).coerceAtLeast(0f)
                    val ring = Offset(mid.x + dx * scale, mid.y + dy * scale)
                    val green = LumiColors.Emerald
                    drawLine(green, mid, ring, 1.4.dp.toPx(), alpha = ringAlpha)
                    drawCircle(green, radius, ring, alpha = ringAlpha, style = Stroke(2.2.dp.toPx()))
                }
                drawCircle(Color.White.copy(alpha = 0.9f), 2.5.dp.toPx(), mid)
            }

            focusTap?.let { activeFocus ->
                FocusEvOverlay(
                    focus = activeFocus,
                    viewWidthPx = viewfinderWidthPx,
                    viewHeightPx = viewfinderHeightPx,
                    evSteps = controlsState.quickEvSteps,
                    minEv = controlsState.minEvSteps,
                    maxEv = controlsState.maxEvSteps,
                    canAdjustEv = controlsState.canQuickEv,
                    evActive = evDragActive,
                )
            }

            if (countdownRemaining > 0) {
                CountdownOverlay(
                    secondsRemaining = countdownRemaining,
                    rotationDegrees = uiIconRotation,
                )
            }

            if (shutterFlashAlpha > 0f) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = shutterFlashAlpha)),
                )
            }

            // Single clean top-center frosted guidance pill + at most one secondary action/progress chip
            val primaryBanner = remember(
                frame,
                bound,
                lens.front,
                recommendationStatus,
                autoShoot,
                autoShotFired,
                proactivityMode,
            ) {
                resolveGuidanceBanner(
                    frame = frame,
                    bound = bound,
                    front = lens.front,
                    recommendationStatus = recommendationStatus,
                    autoShoot = autoShoot,
                    autoShotFired = autoShotFired,
                    proactivityMode = proactivityMode,
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(
                        top = 12.dp,
                        start = 16.dp,
                        end = if (referenceState.hasReference) 74.dp else 16.dp,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (quickControlsOpen) {
                    QuickControlsStrip(
                        aspectRatio = controlsState.aspectRatio,
                        timer = timerOption,
                        onSelectAspectRatio = { nextRatio ->
                            uiPrefs.edit().putString("aspect_ratio", nextRatio.wire).apply()
                            session.setAspectRatio(nextRatio)
                        },
                        onSelectTimer = { nextTimer ->
                            timerOption = nextTimer
                            uiPrefs.edit().putString("timer_option", nextTimer.wire).apply()
                        },
                    )
                }
                val displayedBanner = primaryBanner ?: if (onboardingVisible && onboardingFlow.step == OnboardingStep.AI_GUIDANCE) {
                    GuidanceBanner(text = "实时观察画面 · 准备给出构图建议", subtle = true)
                } else {
                    null
                }
                Box(
                    modifier = Modifier.onGloballyPositioned { coords ->
                        onboardingAnchors[OnboardingTarget.AI_GUIDANCE] = coords.boundsInRoot()
                    },
                ) {
                    displayedBanner?.let { banner ->
                        ViewfinderGuidancePill(banner = banner)
                    }
                }
                val targetZoom = frame.suggestedZoom
                val step = frame.stepProgress
                val refGuide = frame.referenceGuidance
                when {
                    targetZoom != null -> {
                        Surface(
                            onClick = session::applySuggestedZoom,
                            shape = RoundedCornerShape(18.dp),
                            color = LumiColors.GlassDark,
                            border = BorderStroke(1.dp, LumiColors.Ivory),
                        ) {
                            Text(
                                text = "建议切至 ${SubjectCandidates.formatZoomLabel(targetZoom)} ›",
                                color = LumiColors.Ivory,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                            )
                        }
                    }
                    step != null -> {
                        StepProgressPill(step)
                    }
                    refGuide != null -> {
                        ReferenceCoachBanner(refGuide)
                    }
                    else -> {
                        AiExposureActionPill(
                            state = exposureState,
                            onApplyPending = session::applyPendingAiExposure,
                            onRevertApplied = session::revertAiExposure,
                        )
                    }
                }
            }

            if (referenceState.hasReference) {
                ReferenceThumbnailDock(
                    state = referenceState,
                    onToggleOverlay = session::toggleReferenceOverlay,
                    onOpenSheet = { referenceDialogOpen = true },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 10.dp, top = 10.dp),
                )
            }

            val activePose = frame.poseSilhouette
            if (activePose != null && !reviewBannerVisible) {
                PoseSilhouetteToggleChip(
                    silhouette = activePose,
                    visible = showPoseSilhouette,
                    onToggle = {
                        showPoseSilhouette = !showPoseSilhouette
                        uiPrefs.edit().putBoolean("show_pose_silhouette", showPoseSilhouette).apply()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 12.dp, bottom = 58.dp),
                )
            }

            val bannerReview = lastReview
            if (reviewBannerVisible && bannerReview != null) {
                Surface(
                    onClick = {
                        reviewBannerVisible = false
                        activeReviewDialogTarget = bannerReview
                    },
                    shape = RoundedCornerShape(18.dp),
                    color = LumiColors.GlassDark,
                    border = BorderStroke(1.dp, LumiColors.Ivory.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 66.dp, start = 16.dp, end = 16.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = bannerReview.summary,
                            color = LumiColors.TextPrimary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            text = "精裁 ›",
                            color = LumiColors.Ivory,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
            }

            if (frame.closeups.isNotEmpty() && !reviewBannerVisible) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 66.dp, start = 16.dp, end = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    frame.closeups.forEach { proposal ->
                        Surface(
                            onClick = { session.selectCloseupProposal(proposal.id) },
                            shape = RoundedCornerShape(16.dp),
                            color = LumiColors.GlassDark,
                            border = BorderStroke(1.dp, LumiColors.Ivory.copy(alpha = 0.72f)),
                        ) {
                            Text(
                                text = proposal.label,
                                color = LumiColors.Ivory,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
            }

            // Bottom 3-point overlay inside viewfinder: Filter (left) | Compact Zoom (center) | Exposure ± (right)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                val filterActive = activeTray == BottomTray.FILTER || filterState.isActive
                ViewfinderCornerCircle(
                    active = filterActive,
                    description = "滤镜与环境调色",
                    onClick = {
                        activeTray = if (activeTray == BottomTray.FILTER) BottomTray.NONE else BottomTray.FILTER
                    },
                    modifier = Modifier.align(Alignment.BottomStart),
                ) {
                    Column(
                        modifier = Modifier.graphicsLayer { rotationZ = uiIconRotation },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        FilterRingsIcon(
                            tint = if (filterActive) LumiColors.Ivory else LumiColors.TextPrimary,
                            size = 17.dp,
                        )
                        Text(
                            text = filterState.badgeLabel,
                            color = if (filterActive) LumiColors.Ivory else LumiColors.TextSecondary,
                            fontSize = 8.sp,
                            maxLines = 1,
                        )
                    }
                }

                if (bound && zoom.max > zoom.min) {
                    ZoomWheel(
                        zoom = zoom,
                        select = session::setZoom,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }

                val exposureActive = activeTray == BottomTray.EXPOSURE || exposureMode != ExposureControlMode.AUTO
                ViewfinderCornerCircle(
                    active = exposureActive,
                    description = "曝光与 M 手动挡",
                    onClick = {
                        activeTray = if (activeTray == BottomTray.EXPOSURE) BottomTray.NONE else BottomTray.EXPOSURE
                    },
                    modifier = Modifier.align(Alignment.BottomEnd),
                ) {
                    Column(
                        modifier = Modifier.graphicsLayer { rotationZ = uiIconRotation },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        ExposurePlusMinusIcon(
                            tint = if (exposureActive) LumiColors.Ivory else LumiColors.TextPrimary,
                            size = 17.dp,
                        )
                        val expBadge = when (exposureMode) {
                            ExposureControlMode.AUTO -> null
                            ExposureControlMode.AI_AUTO -> "AI"
                            ExposureControlMode.MANUAL -> "M"
                        }
                        if (expBadge != null) {
                            Text(
                                text = expBadge,
                                color = LumiColors.Ivory,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }

    // 3. Bottom Control Deck (Obsidian black, mutually exclusive tray + symmetrical shutter row per 摆法 1)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(LumiColors.Obsidian)
            .padding(top = 6.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (activeTray) {
            BottomTray.FILTER -> {
                FilterBar(
                    state = filterState,
                    liveThumbnails = filterLiveThumbnails,
                    onSelectAiSmart = {
                        uiPrefs.edit().putString("filter_mode", FilterSelectionMode.AI_SMART.wire).apply()
                        session.selectAiSmartFilter()
                    },
                    onSelectPreset = { preset ->
                        uiPrefs.edit()
                            .putString("filter_mode", FilterSelectionMode.MANUAL.wire)
                            .putString("filter_preset", preset.id)
                            .apply()
                        session.selectFilterPreset(preset)
                    },
                    onIntensityChange = { value ->
                        uiPrefs.edit().putFloat("filter_intensity", value).apply()
                        session.setFilterIntensity(value)
                    },
                    onToggleAiAdaptive = { enabled ->
                        uiPrefs.edit().putBoolean("filter_ai_adaptive", enabled).apply()
                        session.setFilterAiAdaptive(enabled)
                    },
                )
            }
            BottomTray.EXPOSURE -> {
                ManualExposurePanel(
                    state = exposureState,
                    onSelectMode = { next ->
                        exposureMode = next
                        uiPrefs.edit().putString("exposure_mode", next.wire).apply()
                        session.setExposureMode(next)
                    },
                    onUpdateManual = session::updateManualExposure,
                    onApplyAiToManual = session::applyLatestAiToManual,
                    onResetManual = session::resetManualExposure,
                )
            }
            BottomTray.NONE -> {
                MinimalPresetTabRow(
                    selection = presetSelection,
                    onSelect = session::selectPreset,
                    modifier = Modifier.onGloballyPositioned { coords ->
                        onboardingAnchors[OnboardingTarget.COMPOSITION_MODES] = coords.boundsInRoot()
                    },
                )
                TelemetryStrip(telemetry = exposureState.telemetry)
            }
        }

        // Main Shutter Row (摆法 1: Left Recent Photo Thumb | Center Tactile Shutter | Right "AI 构图")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(86.dp), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.graphicsLayer { rotationZ = uiIconRotation }) {
                    val latestThumbUri = galleryPhotos.firstOrNull()?.uri ?: lastReview?.photoUri
                    val thumbKey = galleryPhotos.firstOrNull()?.id ?: lastReview?.capturedAtMs ?: 0L
                    LastPhotoThumbSlot(
                        photoUri = latestThumbUri,
                        cacheKey = thumbKey,
                        processingCount = captureQueue.pendingCount,
                        onClick = {
                            reviewBannerVisible = false
                            if (galleryPhotos.isNotEmpty()) {
                                galleryViewerOpen = true
                            } else if (lastReview != null) {
                                activeReviewDialogTarget = lastReview
                            }
                        },
                    )
                }
            }

            TactileShutterButton(
                enabled = bound && !zoom.changing,
                ready = frame.ready || countdownRemaining > 0,
                autoShootProgress = if (autoShoot) autoShootProgress else 0f,
                onClick = ::triggerShutter,
                modifier = Modifier.onGloballyPositioned { coords ->
                    onboardingAnchors[OnboardingTarget.SHUTTER] = coords.boundsInRoot()
                },
            )

            Box(Modifier.width(86.dp), contentAlignment = Alignment.CenterEnd) {
                val aiEnabled = bound && !zoom.changing && proactivityMode != ProactivityMode.QUIET
                val statusText = recommendationStatus
                val aiBusy = statusText != null && !frame.hasTarget &&
                    (statusText.contains("正在") || statusText.contains("精修中"))
                Surface(
                    onClick = session::recommend,
                    enabled = aiEnabled,
                    shape = RoundedCornerShape(22.dp),
                    color = when {
                        !aiEnabled -> Color(0x16FFFFFF)
                        aiBusy -> LumiColors.IvoryMuted
                        else -> LumiColors.Ivory
                    },
                    border = BorderStroke(
                        width = 1.dp,
                        color = when {
                            !aiEnabled -> LumiColors.Hairline
                            aiBusy -> LumiColors.Ivory
                            else -> LumiColors.Ivory
                        },
                    ),
                    modifier = Modifier.height(42.dp),
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 15.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = when {
                                aiBusy -> "分析中…"
                                !configured -> "离线构图"
                                else -> "AI 构图"
                            },
                            color = when {
                                !aiEnabled -> LumiColors.TextMuted
                                aiBusy -> LumiColors.Ivory
                                else -> LumiColors.IvoryInk
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
    } // end camera Column

    if (onboardingVisible) {
        OnboardingOverlay(
            flow = onboardingFlow,
            anchorsInRoot = onboardingAnchors,
            firstShotReview = lastReview,
            onStartGuide = { updateOnboarding(onboardingFlow.startGuide()) },
            onNext = { updateOnboarding(onboardingFlow.next()) },
            onPrevious = { updateOnboarding(onboardingFlow.previous()) },
            onSkip = { updateOnboarding(onboardingFlow.skip()) },
            onOpenFirstShotReview = {
                updateOnboarding(onboardingFlow.dismissFirstShot())
                lastReview?.let { activeReviewDialogTarget = it }
            },
            onDismissFirstShot = { updateOnboarding(onboardingFlow.dismissFirstShot()) },
        )
    }
    } // end root Box

    // Dialogs & Sheets
    if (cameraSettingsOpen) {
        CameraSettingsDialog(
            nativeProcessingState = nativeProcessingState,
            showGrid = showGrid,
            showModelConfigEntry = BuildConfig.DEBUG || !config.managedGateway,
            modelSummary = if (config.managedGateway) "Lumi 云端托管网关" else config.safeText(config.model).ifBlank { "尚未填写模型" },
            modelStatusLabel = modelStatusLabel,
            modelStatusColor = modelDotColor,
            preferenceSummary = session.preferenceSummary(),
            telemetrySummary = LumiTelemetry.snapshot().compactSummary(),
            canRememberComposition = bound && frame.canRemember,
            onSetNativeProcessing = { mode ->
                nativeProcessingMode = mode
                uiPrefs.edit().putString("native_processing_mode", mode.wire).apply()
                session.setNativeProcessingMode(mode)
            },
            onToggleGrid = { enabled ->
                showGrid = enabled
                uiPrefs.edit().putBoolean("show_grid", enabled).apply()
            },
            onOpenModelSettings = { modelSettingsOpen = true },
            onOpenPrivacyHealth = { privacyHealthOpen = true },
            onReplayOnboarding = {
                activeTray = BottomTray.NONE
                updateOnboarding(onboardingFlow.replay())
            },
            onResetPreferences = session::resetPreferences,
            onRememberComposition = session::rememberComposition,
            onOpenDiagnostics = { diagnosticOpen = true },
            onDismiss = { cameraSettingsOpen = false },
        )
    }
    if (firstLaunchConsentOpen || privacyHealthOpen) {
        val isFirst = firstLaunchConsentOpen
        PrivacyAndHealthDialog(
            firstLaunch = isFirst,
            crashCount = LumiCrashGuard.crashCount(context),
            latestCrashSummary = LumiCrashGuard.latestCrashSummary(context),
            onClearLocalData = {
                session.resetPreferences()
                LumiCrashGuard.clearLogs(context)
                LumiTelemetry.reset()
                onMessage("已清除本地偏好与运行日志")
                privacyHealthOpen = false
            },
            onAcceptOrClose = {
                if (isFirst) {
                    PrivacyConsentStore.markAccepted(context)
                    firstLaunchConsentOpen = false
                }
                privacyHealthOpen = false
            },
        )
    }
    if (modelSettingsOpen) {
        ModelSettingsDialog(
            config,
            store,
            onDismiss = { modelSettingsOpen = false },
            onTested = { draft, result -> if (draft == config) setFailure(result != ConnectionResult.SUCCESS) },
            onSaved = { saved, result ->
                setFailure(result?.let { it != ConnectionResult.SUCCESS } ?: (saved == config && lastFailed))
                session.useModelConfig(saved)
                config = saved
                modelSettingsOpen = false
            },
        )
    }
    if (diagnosticOpen && BuildConfig.DEBUG) {
        DiagnosticDialog(onDismiss = { diagnosticOpen = false })
    }
    if (galleryViewerOpen && galleryPhotos.isNotEmpty()) {
        LumiGalleryViewerDialog(
            photos = galleryPhotos,
            onOpenRecrop = { item ->
                galleryViewerOpen = false
                activeReviewDialogTarget = if (lastReview?.photoUri == item.uri) {
                    lastReview
                } else {
                    PostShotRecrop.buildReview(
                        photoUri = item.uri,
                        subjectBounds = null,
                        rollDeg = null,
                        wasReady = false,
                        reason = null,
                        directorCue = null,
                    )
                }
            },
            onDeletePhoto = { item ->
                scope.launch {
                    val deleted = withContext(Dispatchers.IO) {
                        LumiGalleryStore.deletePhoto(context, item.uri)
                    }
                    if (deleted) {
                        if (lastReview?.photoUri == item.uri) lastReview = null
                        galleryPhotos = galleryPhotos.filterNot { it.id == item.id }
                        if (galleryPhotos.isEmpty()) galleryViewerOpen = false
                        onMessage("已删除照片")
                    } else {
                        onMessage("未能删除该照片，请在系统相册中操作")
                    }
                }
            },
            onDismiss = { galleryViewerOpen = false },
        )
    }
    val activeReview = activeReviewDialogTarget
    if (activeReview != null) {
        PostShotReviewDialog(
            review = activeReview,
            preferenceSummary = session.preferenceSummary(),
            onDismiss = { activeReviewDialogTarget = null },
            onSavedCopy = { proposalId, msg ->
                session.recordRecropPreference(proposalId)
                refreshGallery()
                onMessage(msg)
            },
            onResetPreferences = session::resetPreferences,
        )
    }
    if (referenceDialogOpen && referenceState.hasReference) {
        ReferenceRecipeDialog(
            state = referenceState,
            modelConfigured = configured,
            onDismiss = { referenceDialogOpen = false },
            onToggleOverlay = session::toggleReferenceOverlay,
            onAlphaChange = session::setReferenceOverlayAlpha,
            onRetryAnalyze = session::retryReferenceAnalysis,
            onReplaceImage = ::launchReferencePicker,
            onClearReference = session::clearReferenceImage,
        )
    }
}
