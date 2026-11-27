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
import androidx.compose.foundation.layout.ColumnScope
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
import com.lumi.camera.filter.FilterUiState
import com.lumi.camera.gallery.GalleryPhotoItem
import com.lumi.camera.gallery.LumiGalleryStore
import com.lumi.camera.guidance.GuideEvidence
import com.lumi.camera.guidance.PoseGuidance
import com.lumi.camera.guidance.StepProgress
import com.lumi.camera.shotplan.CompositionChoice
import com.lumi.camera.shotplan.CompositionDiagnostic
import com.lumi.camera.shotplan.CompositionPreset
import com.lumi.camera.shotplan.ConnectionResult
import com.lumi.camera.shotplan.ModelConfig
import com.lumi.camera.shotplan.ModelConfigStore
import com.lumi.camera.shotplan.PhotoQualityScore
import com.lumi.camera.shotplan.PresetSelection
import com.lumi.camera.shotplan.ProactivityMode
import com.lumi.camera.shotplan.SavedDiagnostic
import com.lumi.camera.shotplan.SubjectCandidates
import com.lumi.camera.vision.NormalizedCrop
import com.lumi.camera.vision.PostShotRecrop
import com.lumi.camera.vision.PostShotReview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

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
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(LumiColors.Obsidian)
                        .safeDrawingPadding(),
                    horizontalAlignment = Alignment.CenterHorizontally,
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
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (requested) "没有相机权限，无法取景。" else "Lumi 需要相机权限来取景和拍照。",
                            color = LumiColors.TextPrimary,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(permissions::requestCamera) { Text("允许使用相机") }
                        if (requested) TextButton({
                            startActivity(Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", packageName, null),
                            ))
                        }) { Text("打开系统设置", color = LumiColors.Ivory) }
                        Spacer(Modifier.weight(1f))
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
private fun ColumnScope.CameraScreen(
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
    var aiMenuOpen by remember { mutableStateOf(false) }
    var diagnosticOpen by remember { mutableStateOf(false) }
    var quickControlsOpen by remember { mutableStateOf(false) }
    var activeTray by remember { mutableStateOf(BottomTray.NONE) }
    var showGrid by remember { mutableStateOf(uiPrefs.getBoolean("show_grid", true)) }

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
                reviewBannerVisible = true
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

    val anyDialogOpen = cameraSettingsOpen || modelSettingsOpen || diagnosticOpen ||
        activeReviewDialogTarget != null || referenceDialogOpen || galleryViewerOpen
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
                primaryBanner?.let { banner ->
                    ViewfinderGuidancePill(banner = banner)
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
                MinimalPresetTabRow(presetSelection, onSelect = session::selectPreset)
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
            )

            Box(Modifier.width(86.dp), contentAlignment = Alignment.CenterEnd) {
                val aiEnabled = bound && !zoom.changing && configured && proactivityMode != ProactivityMode.QUIET
                val aiBusy = recommendationStatus != null && !frame.hasTarget
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
                            text = if (aiBusy) "分析中…" else "AI 构图",
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

    // Dialogs & Sheets
    if (cameraSettingsOpen) {
        CameraSettingsDialog(
            nativeProcessingState = nativeProcessingState,
            showGrid = showGrid,
            modelSummary = config.safeText(config.model).ifBlank { "尚未填写模型" },
            modelStatusLabel = modelStatusLabel,
            modelStatusColor = modelDotColor,
            preferenceSummary = session.preferenceSummary(),
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
            onResetPreferences = session::resetPreferences,
            onRememberComposition = session::rememberComposition,
            onOpenDiagnostics = { diagnosticOpen = true },
            onDismiss = { cameraSettingsOpen = false },
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

private data class GuidanceBanner(
    val text: String,
    val subtitle: String? = null,
    val ready: Boolean = false,
    val subtle: Boolean = false,
)

private fun resolveGuidanceBanner(
    frame: Viewfinder,
    bound: Boolean,
    front: Boolean,
    recommendationStatus: String?,
    autoShoot: Boolean,
    autoShotFired: Boolean,
    proactivityMode: ProactivityMode,
): GuidanceBanner? {
    val rollWarn = frame.rollDeg?.let { abs(it) > PoseGuidance.READY_MAX_ROLL_DEG } == true
    val pendingStep = frame.stepProgress?.takeIf { !it.satisfied }
    val secondaryTip = when {
        frame.directorCue != null -> "${frame.directorCue.category.badge} · ${frame.directorCue.tip}"
        frame.lightGuidance?.isActionable == true -> frame.lightGuidance.tip
        else -> null
    }
    return when {
        !bound -> GuidanceBanner(if (front) "正在打开前置相机" else "正在打开后置相机", subtle = true)
        recommendationStatus != null -> GuidanceBanner(recommendationStatus)
        frame.ready && autoShoot && !autoShotFired -> GuidanceBanner("构图就绪 · 自动拍摄中", ready = true)
        frame.ready && pendingStep != null -> GuidanceBanner("构图已对齐 · ${pendingStep.direction.label}更干净", ready = true)
        frame.ready -> GuidanceBanner(
            text = frame.reason?.let { "构图就绪 · $it" } ?: "构图就绪 · 可以拍",
            subtitle = secondaryTip,
            ready = true,
        )
        frame.viewpointDrifted -> GuidanceBanner(
            text = "视角已偏离，请回到推荐仰俯角",
            subtitle = frame.reason,
        )
        frame.evidence == GuideEvidence.RETURN_DIRECTION -> GuidanceBanner(
            text = frame.reason?.let { "返回推荐方向 · $it" } ?: "返回推荐构图方向",
        )
        frame.hasTarget && frame.ring == null -> GuidanceBanner("正在找回主体")
        frame.hasTarget && rollWarn -> GuidanceBanner(
            text = frame.reason?.let { "$it · 调平画面" } ?: "轻轻调平画面",
            subtitle = secondaryTip,
        )
        frame.reason != null -> GuidanceBanner(
            text = frame.reason,
            subtitle = secondaryTip,
        )
        frame.directorCue != null -> GuidanceBanner("${frame.directorCue.category.badge} · ${frame.directorCue.tip}")
        proactivityMode == ProactivityMode.QUIET -> null
        else -> null
    }
}

@Composable
private fun ViewfinderGuidancePill(banner: GuidanceBanner) {
    val borderColor = when {
        banner.ready -> LumiColors.Emerald.copy(alpha = 0.72f)
        banner.subtle -> LumiColors.Hairline
        else -> LumiColors.HairlineStrong
    }
    val dotColor = if (banner.ready) LumiColors.Emerald else LumiColors.Ivory
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = LumiColors.GlassDark,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (banner.ready) {
                Text("✓", color = LumiColors.Emerald, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            } else {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .border(1.2.dp, dotColor, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(3.5.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = banner.text,
                    color = LumiColors.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                banner.subtitle?.let { sub ->
                    Text(
                        text = sub,
                        color = LumiColors.Ivory.copy(alpha = 0.88f),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun TopIconButton(
    description: String,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (active) Color(0x1AF2E6C9) else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun ViewfinderCornerCircle(
    active: Boolean,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = LumiColors.GlassDark,
        border = BorderStroke(1.dp, if (active) LumiColors.Ivory else LumiColors.Hairline),
        modifier = modifier
            .size(46.dp)
            .semantics { contentDescription = description },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

@Composable
private fun MinimalPresetTabRow(selection: PresetSelection, onSelect: (CompositionPreset) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionPreset.entries.forEach { preset ->
            val selected = selection.preset == preset
            val text = when {
                preset == CompositionPreset.RULE_OF_THIRDS && selected ->
                    if (selection.thirdChoice == CompositionChoice.LEFT_THIRD) "三分·左" else "三分·右"
                else -> preset.label
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.RadioButton) { onSelect(preset) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = text,
                    color = if (selected) LumiColors.TextPrimary else LumiColors.TextMuted,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                )
                Box(
                    Modifier
                        .width(16.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (selected) LumiColors.Ivory else Color.Transparent),
                )
            }
        }
    }
}

@Composable
private fun TelemetryStrip(telemetry: LiveTelemetry) {
    val parts = buildList {
        telemetry.aperture?.let {
            add(String.format(Locale.ROOT, "f/%.2f", it).trimEnd('0').trimEnd('.'))
        }
        telemetry.shutterNs?.let { add(ExposureSolver.formatShutter(it)) }
        telemetry.iso?.let { add("ISO $it") }
    }
    val text = if (parts.isNotEmpty()) parts.joinToString("  |  ") else "AUTO  |  测光就绪"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 42.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(LumiColors.Hairline),
        )
        Text(
            text = text,
            color = LumiColors.TextSecondary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(LumiColors.Hairline),
        )
    }
}

@Composable
private fun TactileShutterButton(
    enabled: Boolean,
    ready: Boolean,
    autoShootProgress: Float,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val innerScale by animateFloatAsState(
        targetValue = if (pressed) 0.89f else 1f,
        animationSpec = tween(90),
        label = "shutter press scale",
    )
    val outerColor = when {
        !enabled -> LumiColors.Hairline
        ready -> LumiColors.Emerald
        else -> LumiColors.TextPrimary
    }
    Box(
        modifier = Modifier
            .size(74.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = "拍照快门" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val ringStroke = 2.4.dp.toPx()
            val radius = size.minDimension / 2f - ringStroke
            drawCircle(
                color = outerColor,
                radius = radius,
                center = center,
                style = Stroke(ringStroke),
            )
            if (autoShootProgress > 0f) {
                drawArc(
                    color = LumiColors.Ivory,
                    startAngle = -90f,
                    sweepAngle = 360f * autoShootProgress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = Offset(ringStroke, ringStroke),
                    size = Size(size.width - ringStroke * 2f, size.height - ringStroke * 2f),
                    style = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            drawCircle(
                color = if (enabled) Color.White else Color(0x66FFFFFF),
                radius = (radius - 5.dp.toPx()) * innerScale,
                center = center,
            )
        }
    }
}

@Composable
private fun LastPhotoThumbSlot(
    photoUri: Uri?,
    cacheKey: Long,
    processingCount: Int,
    onClick: () -> Unit,
) {
    val infinite = rememberInfiniteTransition(label = "capture queue ring")
    val spinDeg by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "capture spin",
    )
    val context = LocalContext.current
    val thumb by produceState<android.graphics.Bitmap?>(null, photoUri, cacheKey) {
        value = if (photoUri == null) null else withContext(Dispatchers.IO) {
            PostShotRecrop.loadPreviewBitmap(context, photoUri, maxEdge = 160)
        }
    }

    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        if (photoUri == null) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x16FFFFFF))
                    .border(1.dp, LumiColors.Hairline, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                ReferenceFrameIcon(tint = LumiColors.TextMuted, size = 18.dp)
            }
        } else {
            Surface(
                onClick = onClick,
                shape = RoundedCornerShape(12.dp),
                color = Color(0x22FFFFFF),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (processingCount > 0) LumiColors.Ivory else LumiColors.HairlineStrong,
                ),
                modifier = Modifier
                    .size(46.dp)
                    .semantics { contentDescription = "打开 Lumi 相册与精裁复盘" },
            ) {
                val bmp = thumb
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "最新照片缩略图",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ReferenceFrameIcon(tint = LumiColors.Ivory, size = 18.dp)
                    }
                }
            }
        }

        if (processingCount > 0) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 2.dp.toPx()
                drawArc(
                    color = LumiColors.Ivory,
                    startAngle = spinDeg,
                    sweepAngle = 110f,
                    useCenter = false,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
private fun DiagnosticDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val saved by produceState<SavedDiagnostic?>(null) {
        value = withContext(Dispatchers.IO) { CompositionDiagnostic.loadLatest(context) }
    }
    val bitmap by produceState<android.graphics.Bitmap?>(null, saved) {
        val file = saved?.imageFile?.takeIf { it.isFile }
        value = file?.let { withContext(Dispatchers.IO) { BitmapFactory.decodeFile(it.absolutePath) } }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(18.dp), color = LumiColors.SurfaceDark) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("上次推荐构图诊断", style = MaterialTheme.typography.titleMedium, color = LumiColors.TextPrimary)
                Text(
                    "黄框/点=模型主体  蓝线=本地边缘  红十字=原中心  绿框/环=建议成片",
                    fontSize = 11.sp,
                    color = LumiColors.TextMuted,
                )
                if (saved == null) {
                    Text("暂无推荐诊断记录，请先点击一次「AI 构图」。", fontSize = 13.sp, color = LumiColors.TextSecondary)
                } else {
                    bitmap?.let { bmp ->
                        Image(
                            bmp.asImageBitmap(),
                            contentDescription = "推荐构图叠加诊断图",
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat()),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    Text(saved?.report.orEmpty(), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = LumiColors.TextSecondary)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss) { Text("关闭", color = LumiColors.Ivory) }
                }
            }
        }
    }
}

private fun DrawScope.drawHorizonLevel(mid: Offset, rollDeg: Double) {
    if (abs(rollDeg) > 35.0) return
    val level = abs(rollDeg) <= PoseGuidance.LEVEL_GREEN_DEG
    val color = when {
        level -> LumiColors.Emerald
        abs(rollDeg) <= 5.0 -> Color.White.copy(alpha = 0.78f)
        else -> LumiColors.Amber.copy(alpha = 0.88f)
    }
    val stroke = 1.5.dp.toPx()
    val refStroke = 1.dp.toPx()
    val innerGap = 11.dp.toPx()
    val armLen = 16.dp.toPx()
    val refGap = innerGap + armLen + 4.dp.toPx()
    val refLen = 6.dp.toPx()
    val refColor = if (level) LumiColors.Emerald.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.30f)
    drawLine(refColor, Offset(mid.x - refGap - refLen, mid.y), Offset(mid.x - refGap, mid.y), refStroke, cap = StrokeCap.Round)
    drawLine(refColor, Offset(mid.x + refGap, mid.y), Offset(mid.x + refGap + refLen, mid.y), refStroke, cap = StrokeCap.Round)
    val rad = if (level) 0.0 else Math.toRadians(rollDeg)
    val cx = cos(rad).toFloat()
    val sy = sin(rad).toFloat()
    drawLine(
        color,
        Offset(mid.x - (innerGap + armLen) * cx, mid.y - (innerGap + armLen) * sy),
        Offset(mid.x - innerGap * cx, mid.y - innerGap * sy),
        stroke,
        cap = StrokeCap.Round,
    )
    drawLine(
        color,
        Offset(mid.x + innerGap * cx, mid.y + innerGap * sy),
        Offset(mid.x + (innerGap + armLen) * cx, mid.y + (innerGap + armLen) * sy),
        stroke,
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawCloseupFrames(closeups: List<CloseupOverlay>) {
    if (closeups.isEmpty()) return
    val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
    val thin = 1.dp.toPx()
    val cornerStroke = 1.8.dp.toPx()
    closeups.forEachIndexed { idx, box ->
        val alpha = if (idx == 0) 0.78f else 0.48f
        val color = LumiColors.Ivory.copy(alpha = alpha)
        val w = (box.right - box.left).coerceAtLeast(1f)
        val h = (box.bottom - box.top).coerceAtLeast(1f)
        drawRect(
            color = color.copy(alpha = alpha * 0.55f),
            topLeft = Offset(box.left, box.top),
            size = Size(w, h),
            style = Stroke(width = thin, pathEffect = dash),
        )
        val corner = minOf(12.dp.toPx(), w * 0.22f, h * 0.22f)
        drawLine(color, Offset(box.left, box.top), Offset(box.left + corner, box.top), cornerStroke, cap = StrokeCap.Round)
        drawLine(color, Offset(box.left, box.top), Offset(box.left, box.top + corner), cornerStroke, cap = StrokeCap.Round)
        drawLine(color, Offset(box.right, box.top), Offset(box.right - corner, box.top), cornerStroke, cap = StrokeCap.Round)
        drawLine(color, Offset(box.right, box.top), Offset(box.right, box.top + corner), cornerStroke, cap = StrokeCap.Round)
        drawLine(color, Offset(box.left, box.bottom), Offset(box.left + corner, box.bottom), cornerStroke, cap = StrokeCap.Round)
        drawLine(color, Offset(box.left, box.bottom), Offset(box.left, box.bottom - corner), cornerStroke, cap = StrokeCap.Round)
        drawLine(color, Offset(box.right, box.bottom), Offset(box.right - corner, box.bottom), cornerStroke, cap = StrokeCap.Round)
        drawLine(color, Offset(box.right, box.bottom), Offset(box.right, box.bottom - corner), cornerStroke, cap = StrokeCap.Round)
        // Tiny center aim dot inside primary close-up frame
        if (idx == 0) {
            drawCircle(color, 2.2.dp.toPx(), Offset((box.left + box.right) * 0.5f, (box.top + box.bottom) * 0.5f))
        }
    }
}

private fun DrawScope.drawCandidateBrackets(candidates: List<CandidateOverlay>) {
    if (candidates.isEmpty()) return
    val stroke = 1.8.dp.toPx()
    for (c in candidates) {
        val color = if (c.userPicked) LumiColors.Ivory else LumiColors.Emerald.copy(alpha = 0.78f)
        val w = c.right - c.left
        val h = c.bottom - c.top
        val corner = minOf(14.dp.toPx(), w * 0.25f, h * 0.25f)
        drawLine(color, Offset(c.left, c.top), Offset(c.left + corner, c.top), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(c.left, c.top), Offset(c.left, c.top + corner), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(c.right, c.top), Offset(c.right - corner, c.top), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(c.right, c.top), Offset(c.right, c.top + corner), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(c.left, c.bottom), Offset(c.left + corner, c.bottom), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(c.left, c.bottom), Offset(c.left, c.bottom - corner), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(c.right, c.bottom), Offset(c.right - corner, c.bottom), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(c.right, c.bottom), Offset(c.right, c.bottom - corner), stroke, cap = StrokeCap.Round)
    }
}

private fun DrawScope.drawTapReticle(tap: Point) {
    val color = LumiColors.Ivory
    val radius = 18.dp.toPx()
    val stroke = 1.6.dp.toPx()
    val center = Offset(tap.x, tap.y)
    drawCircle(color, radius, center, style = Stroke(stroke))
    drawCircle(color, 2.8.dp.toPx(), center)
}

private fun DrawScope.drawCompositionGuides(selection: PresetSelection, showBaseGrid: Boolean) {
    val w = size.width
    val h = size.height
    val baseGridColor = Color.White.copy(alpha = 0.16f)
    val lineColor = Color.White.copy(alpha = 0.24f)
    val accentColor = LumiColors.Ivory.copy(alpha = 0.62f)
    val stroke = 0.85.dp.toPx()
    val dash = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
    when (selection.preset) {
        CompositionPreset.AUTO -> {
            if (showBaseGrid) {
                drawLine(baseGridColor, Offset(w / 3f, 0f), Offset(w / 3f, h), stroke)
                drawLine(baseGridColor, Offset(w * 2f / 3f, 0f), Offset(w * 2f / 3f, h), stroke)
                drawLine(baseGridColor, Offset(0f, h / 3f), Offset(w, h / 3f), stroke)
                drawLine(baseGridColor, Offset(0f, h * 2f / 3f), Offset(w, h * 2f / 3f), stroke)
            }
        }
        CompositionPreset.RULE_OF_THIRDS -> {
            val leftColor = if (selection.activeThirdSide == CompositionChoice.LEFT_THIRD) accentColor else lineColor
            val rightColor = if (selection.activeThirdSide == CompositionChoice.RIGHT_THIRD) accentColor else lineColor
            drawLine(leftColor, Offset(w / 3f, 0f), Offset(w / 3f, h), stroke)
            drawLine(rightColor, Offset(w * 2f / 3f, 0f), Offset(w * 2f / 3f, h), stroke)
            drawLine(lineColor, Offset(0f, h / 3f), Offset(w, h / 3f), stroke)
            drawLine(lineColor, Offset(0f, h * 2f / 3f), Offset(w, h * 2f / 3f), stroke)
        }
        CompositionPreset.SYMMETRY -> {
            drawLine(accentColor, Offset(w / 2f, 0f), Offset(w / 2f, h), stroke)
            drawLine(lineColor, Offset(0f, h / 2f), Offset(w, h / 2f), stroke)
        }
        CompositionPreset.TIGHT -> {
            drawLine(accentColor, Offset(0f, h * 0.14f), Offset(w, h * 0.14f), stroke, pathEffect = dash)
        }
        CompositionPreset.HEADROOM -> {
            drawLine(accentColor, Offset(0f, h * 0.24f), Offset(w, h * 0.24f), stroke, pathEffect = dash)
        }
    }
}

@Composable
private fun StepProgressPill(step: StepProgress) {
    val accent = if (step.satisfied) LumiColors.Emerald else LumiColors.Ivory
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = LumiColors.GlassDark,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.72f)),
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = if (step.satisfied) "✓ 机位已到位" else "${step.direction.arrow} ${step.direction.label}",
                color = accent,
                fontSize = 11.sp,
                maxLines = 1,
            )
            Box(
                Modifier
                    .width(96.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0x28FFFFFF)),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(step.progress.coerceIn(0.08f, 1f))
                        .clip(RoundedCornerShape(2.dp))
                        .background(accent),
                )
            }
        }
    }
}

@Composable
private fun QualityScoreCard(score: PhotoQualityScore) {
    val gradeColor = when (score.grade) {
        "S" -> LumiColors.Emerald
        "A" -> LumiColors.Ivory
        "B" -> LumiColors.Amber
        else -> Color(0xFFFFAB91)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0x14FFFFFF),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("成片四维评估", color = LumiColors.TextPrimary, fontSize = 13.sp)
                Text(
                    text = "${score.overall} 分 · ${score.grade} 级（待提升：${score.weakestDimension}）",
                    color = gradeColor,
                    fontSize = 12.sp,
                )
            }
            val dims = listOf(
                "主体突出" to score.subjectClarity,
                "画面平衡" to score.frameBalance,
                "背景干净" to score.backgroundCleanliness,
                "自然度" to score.naturalness,
            )
            dims.forEach { (label, value) ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(label, color = LumiColors.TextMuted, fontSize = 11.sp, modifier = Modifier.width(52.dp))
                    Box(
                        Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0x24FFFFFF)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth((value / 100f).coerceIn(0.05f, 1f))
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (value >= 80) LumiColors.Emerald else LumiColors.Ivory),
                        )
                    }
                    Text("$value", color = LumiColors.TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(24.dp))
                }
            }
        }
    }
}

@Composable
private fun PostShotReviewDialog(
    review: PostShotReview,
    preferenceSummary: String,
    onDismiss: () -> Unit,
    onSavedCopy: (String, String) -> Unit,
    onResetPreferences: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val previewBitmap by produceState<android.graphics.Bitmap?>(null, review.photoUri) {
        value = withContext(Dispatchers.IO) { PostShotRecrop.loadPreviewBitmap(context, review.photoUri, maxEdge = 1080) }
    }
    var selectedProposalId by remember(review.photoUri) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var prefText by remember(preferenceSummary) { mutableStateOf(preferenceSummary) }
    val activeProposal = review.proposals.firstOrNull { it.id == selectedProposalId }
    val activeCrop: NormalizedCrop? = activeProposal?.crop

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = LumiColors.SurfaceDark,
            border = BorderStroke(1.dp, LumiColors.Hairline),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("拍后轻复盘 · 二次构图", style = MaterialTheme.typography.titleMedium, color = LumiColors.TextPrimary)
                Text(review.summary, fontSize = 13.sp, color = LumiColors.TextSecondary)
                review.qualityScore?.let { QualityScoreCard(it) }
                val bmp = previewBitmap
                if (bmp != null) {
                    val displayBmp = remember(bmp, activeCrop) {
                        if (activeCrop == null) bmp else {
                            val x = (activeCrop.left * bmp.width).toInt().coerceIn(0, bmp.width - 1)
                            val y = (activeCrop.top * bmp.height).toInt().coerceIn(0, bmp.height - 1)
                            val w = (activeCrop.width * bmp.width).toInt().coerceIn(1, bmp.width - x)
                            val h = (activeCrop.height * bmp.height).toInt().coerceIn(1, bmp.height - y)
                            android.graphics.Bitmap.createBitmap(bmp, x, y, w, h)
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(displayBmp.width.toFloat() / displayBmp.height.toFloat())
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black),
                    ) {
                        Image(
                            bitmap = displayBmp.asImageBitmap(),
                            contentDescription = "二次构图预览",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(LumiColors.Obsidian),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("正在加载照片…", color = LumiColors.TextMuted, fontSize = 13.sp)
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedProposalId == null,
                        onClick = { selectedProposalId = null },
                        label = { Text("原图底片", fontSize = 12.sp) },
                    )
                    review.proposals.forEach { proposal ->
                        FilterChip(
                            selected = selectedProposalId == proposal.id,
                            onClick = { selectedProposalId = proposal.id },
                            label = { Text(proposal.title, fontSize = 12.sp) },
                        )
                    }
                }
                activeProposal?.let {
                    Text(it.subtitle, fontSize = 12.sp, color = LumiColors.Ivory)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = prefText,
                        color = LumiColors.TextMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            onResetPreferences()
                            prefText = "正在学习你的拍摄偏好（已记录 0 次）"
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) {
                        Text("重置偏好", fontSize = 11.sp, color = LumiColors.Ivory)
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("关闭", color = LumiColors.TextSecondary) }
                    if (activeProposal != null) {
                        Button(
                            enabled = !saving,
                            onClick = {
                                saving = true
                                scope.launch {
                                    val saved = withContext(Dispatchers.IO) {
                                        PostShotRecrop.saveCroppedCopy(
                                            context = context,
                                            sourceUri = review.photoUri,
                                            crop = activeProposal.crop,
                                            suffix = activeProposal.id,
                                        )
                                    }
                                    saving = false
                                    onSavedCopy(
                                        activeProposal.id,
                                        if (saved != null) "已保存「${activeProposal.title}」副本（原图已保留）"
                                        else "保存裁切副本失败，请检查存储权限。",
                                    )
                                    if (saved != null) onDismiss()
                                }
                            },
                        ) {
                            Text(if (saving) "保存中…" else "保存裁切副本")
                        }
                    }
                }
            }
        }
    }
}
