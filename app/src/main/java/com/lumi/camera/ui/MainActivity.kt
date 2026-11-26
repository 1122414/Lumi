package com.lumi.camera.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.camera.view.PreviewView
import com.lumi.camera.BuildConfig
import com.lumi.camera.camera.CameraPermissions
import com.lumi.camera.camera.CameraSession
import com.lumi.camera.camera.CandidateOverlay
import com.lumi.camera.camera.Viewfinder
import com.lumi.camera.camera.ViewfinderLens
import com.lumi.camera.camera.ViewfinderZoom
import com.lumi.camera.camera.Point
import com.lumi.camera.guidance.GuideEvidence
import com.lumi.camera.guidance.PoseGuidance
import com.lumi.camera.guidance.StepProgress
import com.lumi.camera.shotplan.CompositionChoice
import com.lumi.camera.shotplan.CompositionDiagnostic
import com.lumi.camera.shotplan.CompositionPreset
import com.lumi.camera.shotplan.ConnectionResult
import com.lumi.camera.shotplan.DirectorCategory
import com.lumi.camera.shotplan.DirectorCue
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private var allowed by mutableStateOf(false)
    private var requested by mutableStateOf(false)
    private var session: CameraSession? = null
    private val permissions = CameraPermissions(this, { granted ->
        allowed = granted
        requested = true
    }, { granted ->
        if (granted) session?.save() else notifyUser("未获准写入系统相册。请在系统设置中允许存储权限后重试。")
    })

    override fun onResume() {
        super.onResume()
        allowed = permissions.hasCamera()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requested = savedInstanceState?.getBoolean("requested") ?: false
        enableEdgeToEdge()
        setContent {
            val store = remember { ModelConfigStore(applicationContext) }
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFB3DBBE))) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                    .safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (allowed) {
                        val initial by produceState<ModelConfig?>(null, store) {
                            value = withContext(Dispatchers.IO) { store.read() }
                        }
                        initial?.let {
                            CameraScreen(this@MainActivity, store, it, { session = it }, permissions::requestAlbum, ::notifyUser)
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                        Text(if (requested) "没有相机权限，无法取景。" else "Lumi 需要相机权限来取景和拍照。",
                            color = Color.White)
                        Button(permissions::requestCamera) { Text("允许使用相机") }
                        if (requested) TextButton({
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", packageName, null)))
                        }) { Text("打开系统设置") }
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
private fun ColumnScope.CameraScreen(owner: MainActivity, store: ModelConfigStore, initial: ModelConfig,
    onSession: (CameraSession?) -> Unit,
    onShoot: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val hostView = LocalView.current
    val preview = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
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
    var settingsOpen by remember { mutableStateOf(false) }
    var diagnosticOpen by remember { mutableStateOf(false) }
    val uiPrefs = remember(context) { context.getSharedPreferences("lumi_ui_prefs", android.content.Context.MODE_PRIVATE) }
    var proactivityMode by remember {
        mutableStateOf(ProactivityMode.fromWire(uiPrefs.getString("proactivity_mode", null)))
    }
    var lastReview by remember { mutableStateOf<PostShotReview?>(null) }
    var reviewBannerVisible by remember { mutableStateOf(false) }
    var reviewDialogOpen by remember { mutableStateOf(false) }
    var autoShoot by remember { mutableStateOf(false) }
    var autoShootProgress by remember { mutableFloatStateOf(0f) }
    var autoShotFired by remember { mutableStateOf(false) }
    var wasReady by remember { mutableStateOf(false) }
    fun setFailure(failed: Boolean) {
        if (lastFailed != failed) {
            store.lastFailed = failed
            lastFailed = failed
        }
    }
    val session = remember(preview, owner) {
        CameraSession(preview, owner, { next ->
            frame = next
            if (next.ring != null || !next.hasTarget) paintRing = next.ring
        }, { bound = it }, onMessage, { recommendationStatus = it }, config,
            { setFailure(it != ConnectionResult.SUCCESS) }, { zoom = it }, { lens = it }, { presetSelection = it },
            onCaptured = { review ->
                lastReview = review
                reviewBannerVisible = true
            },
            initialProactivity = proactivityMode)
    }
    LaunchedEffect(lastReview?.capturedAtMs) {
        if (lastReview != null) {
            reviewBannerVisible = true
            delay(3200L)
            reviewBannerVisible = false
        }
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
        onShoot()
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
    val alpha by animateFloatAsState(if (showRing) 1f else 0f,
        if (showRing) snap() else tween(120), label = "ring visibility")
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(top = 48.dp, bottom = 160.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(3f / 4f).background(Color.Black)) {
                AndroidView({ preview }, Modifier.matchParentSize())
                Canvas(
                    Modifier
                        .matchParentSize()
                        .clipToBounds()
                        .pointerInput(session) {
                            detectTapGestures { offset -> session.tapSubject(offset.x, offset.y) }
                        }
                        .pointerInput(session) {
                            detectTransformGestures { _, _, scale, _ -> if (scale != 1f) session.scaleZoom(scale) }
                        }
                ) {
                    drawCompositionGuides(presetSelection)
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
                            else { path.moveTo(p.x, p.y); drawing = true }
                        }
                        if (gap == -1) path.close()
                        drawPath(path, Color(0xFF46A9FF), style = Stroke(width = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
                    }
                    val mid = center
                    frame.rollDeg?.let { roll -> drawHorizonLevel(mid, roll) }
                    paintRing?.takeIf { alpha > 0f }?.let {
                        val dx = it.x - mid.x
                        val dy = it.y - mid.y
                        val radius = 11.dp.toPx()
                        val inset = radius + 3.dp.toPx()
                        // An off-screen projection meets the border along the same line.
                        val scaleX = if (dx == 0f) 1f else (mid.x - inset) / abs(dx)
                        val scaleY = if (dy == 0f) 1f else (mid.y - inset) / abs(dy)
                        val scale = minOf(1f, scaleX, scaleY).coerceAtLeast(0f)
                        val ring = Offset(mid.x + dx * scale, mid.y + dy * scale)
                        val green = Color(0xFF38DF85)
                        drawLine(green, mid, ring, 1.5.dp.toPx(), alpha = alpha)
                        drawCircle(green, radius, ring, alpha = alpha, style = Stroke(2.5.dp.toPx()))
                    }
                    if (autoShoot && autoShootProgress > 0f && autoShootProgress < 1f) {
                        val arcRadius = 17.dp.toPx()
                        drawArc(
                            color = Color(0xFF38DF85),
                            startAngle = -90f,
                            sweepAngle = 360f * autoShootProgress,
                            useCenter = false,
                            topLeft = Offset(mid.x - arcRadius, mid.y - arcRadius),
                            size = Size(arcRadius * 2f, arcRadius * 2f),
                            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                    drawCircle(Color.White, 3.dp.toPx(), mid)
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp, start = 12.dp, end = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    frame.suggestedZoom?.let { targetZoom ->
                        Surface(
                            onClick = session::applySuggestedZoom,
                            shape = RoundedCornerShape(18.dp),
                            color = Color(0xCC102016),
                            border = BorderStroke(1.dp, Color(0xFF38DF85)),
                        ) {
                            Text(
                                text = "建议切至 ${SubjectCandidates.formatZoomLabel(targetZoom)}",
                                color = Color(0xFF38DF85),
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            )
                        }
                    }
                    frame.directorCue?.let { cue ->
                        DirectorCuePill(cue)
                    }
                    frame.stepProgress?.let { step ->
                        StepProgressPill(step)
                    }
                }
                val bannerReview = lastReview
                if (reviewBannerVisible && bannerReview != null) {
                    Surface(
                        onClick = {
                            reviewBannerVisible = false
                            reviewDialogOpen = true
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xD9161B18),
                        border = BorderStroke(1.dp, Color(0x99B3DBBE)),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = if (bound && zoom.max > zoom.min) 52.dp else 12.dp, start = 14.dp, end = 14.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = bannerReview.summary,
                                color = Color.White,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Text(
                                text = "二次构图 ›",
                                color = Color(0xFF38DF85),
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (bound && zoom.max > zoom.min) {
                    ZoomWheel(zoom, session::setZoom, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Lumi", color = Color.White)
            TextButton(onClick = { settingsOpen = true }, modifier = Modifier.weight(1f)) {
                Text("模型", fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Text(config.safeText(config.model), Modifier.weight(1f), color = Color.White, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                Text(when {
                    !configured -> "未配置"
                    lastFailed -> "上次失败"
                    else -> "可用"
                }, fontSize = 12.sp)
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        when (proactivityMode) {
                            ProactivityMode.PROACTIVE -> Color(0xFF4FC3F7)
                            ProactivityMode.QUIET -> Color(0x22FFFFFF)
                            ProactivityMode.MANUAL -> Color(0x2EFFFFFF)
                        }
                    )
                    .clickable(role = Role.Button) {
                        val next = proactivityMode.next()
                        proactivityMode = next
                        uiPrefs.edit().putString("proactivity_mode", next.wire).apply()
                        session.setProactivityMode(next)
                        onMessage(when (next) {
                            ProactivityMode.PROACTIVE -> "主动模式：停稳或画面异常时自动分析"
                            ProactivityMode.QUIET -> "安静模式：仅保留水平仪与手动辅助线"
                            ProactivityMode.MANUAL -> "手动模式：点击推荐或轻点主体触发"
                        })
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = proactivityMode.label,
                    color = if (proactivityMode == ProactivityMode.PROACTIVE) Color(0xFF0A1C24) else Color(0xFFE0E5E2),
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (autoShoot) Color(0xFF38DF85) else Color(0x2EFFFFFF))
                    .clickable(role = Role.Switch) { autoShoot = !autoShoot }
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (autoShoot) "自动拍·开" else "自动拍",
                    color = if (autoShoot) Color(0xFF102016) else Color(0xFFE0E5E2),
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
            if (BuildConfig.DEBUG) Box {
                var debugMenuOpen by remember { mutableStateOf(false) }
                TextButton(onClick = { debugMenuOpen = true }) { Text("调试", fontSize = 12.sp) }
                DropdownMenu(expanded = debugMenuOpen, onDismissRequest = { debugMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("记住这个构图") },
                        enabled = bound && frame.canRemember,
                        onClick = {
                            debugMenuOpen = false
                            session.rememberComposition()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("查看上次推荐诊断") },
                        onClick = {
                            debugMenuOpen = false
                            diagnosticOpen = true
                        }
                    )
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                frame.reason?.let { Text(it, color = Color.White, fontSize = 15.sp, maxLines = 1) }
                val rollWarn = frame.rollDeg?.let { abs(it) > PoseGuidance.READY_MAX_ROLL_DEG } == true
                val pendingSuggestedZoom = frame.suggestedZoom
                val pendingStep = frame.stepProgress?.takeIf { !it.satisfied }
                Text(when {
                    frame.ready && pendingSuggestedZoom != null ->
                        "已对齐 · 建议切至 ${SubjectCandidates.formatZoomLabel(pendingSuggestedZoom)}（或手动拍）"
                    frame.ready && pendingStep != null ->
                        "构图已对齐 · ${pendingStep.direction.arrow} ${pendingStep.direction.label}更干净"
                    frame.ready && autoShoot && !autoShotFired -> "停稳自动拍摄中…"
                    frame.ready -> "可以拍"
                    !bound -> if (lens.front) "正在打开前置相机" else "正在打开后置相机"
                    recommendationStatus != null -> recommendationStatus.orEmpty()
                    frame.viewpointDrifted -> "视角已偏离，请回到推荐仰俯角"
                    frame.evidence == GuideEvidence.RETURN_DIRECTION -> "返回原构图"
                    frame.hasTarget && frame.ring == null -> "正在寻找原主体"
                    frame.hasTarget && rollWarn -> "构图引导中 · 调平画面"
                    frame.hasTarget -> "构图引导中"
                    proactivityMode == ProactivityMode.QUIET -> "安静模式 · 仅水平仪与辅助线"
                    proactivityMode == ProactivityMode.PROACTIVE -> "主动模式 · 停稳画面自动推荐构图"
                    else -> "轻点主体或点击推荐构图"
                }, color = Color.White, fontSize = 13.sp)
            }
            PresetPillBar(presetSelection, onSelect = session::selectPreset)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                lastReview?.let { review ->
                    LastPhotoThumb(review = review, onClick = {
                        reviewBannerVisible = false
                        reviewDialogOpen = true
                    })
                }
                Button(
                    session::recommend,
                    enabled = bound && !zoom.changing && configured && proactivityMode != ProactivityMode.QUIET,
                ) { Text("推荐构图") }
                OutlinedButton(onShoot, enabled = bound && !zoom.changing) { Text("拍照并保存") }
                if (lens.canSwitch) {
                    CameraFlipButton(
                        front = lens.front,
                        enabled = bound && !zoom.changing,
                        onClick = session::switchCamera,
                    )
                }
            }
        }
    }
    if (settingsOpen) ModelSettingsDialog(config, store, onDismiss = { settingsOpen = false },
        onTested = { draft, result -> if (draft == config) setFailure(result != ConnectionResult.SUCCESS) },
        onSaved = { saved, result ->
            setFailure(result?.let { it != ConnectionResult.SUCCESS } ?: (saved == config && lastFailed))
            session.useModelConfig(saved)
            config = saved
            settingsOpen = false
        })
    if (diagnosticOpen && BuildConfig.DEBUG) DiagnosticDialog(onDismiss = { diagnosticOpen = false })
    val activeReview = lastReview
    if (reviewDialogOpen && activeReview != null) {
        PostShotReviewDialog(
            review = activeReview,
            preferenceSummary = session.preferenceSummary(),
            onDismiss = { reviewDialogOpen = false },
            onSavedCopy = { proposalId, msg ->
                session.recordRecropPreference(proposalId)
                onMessage(msg)
            },
            onResetPreferences = session::resetPreferences,
        )
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
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("上次推荐构图诊断", style = MaterialTheme.typography.titleMedium)
                Text("黄框/点=模型主体  蓝线=本地边缘  红十字=原中心  绿框/环=建议成片",
                    fontSize = 11.sp, color = Color(0xFFB0BEC5))
                if (saved == null) {
                    Text("暂无推荐诊断记录，请先点击一次「推荐构图」。", fontSize = 13.sp)
                } else {
                    bitmap?.let { bmp ->
                        Image(bmp.asImageBitmap(), contentDescription = "推荐构图叠加诊断图",
                            modifier = Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height.toFloat()),
                            contentScale = ContentScale.Fit)
                    }
                    Text(saved?.report.orEmpty(), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

private fun DrawScope.drawHorizonLevel(mid: Offset, rollDeg: Double) {
    if (abs(rollDeg) > 35.0) return
    val level = abs(rollDeg) <= PoseGuidance.LEVEL_GREEN_DEG
    val color = when {
        level -> Color(0xFF38DF85)
        abs(rollDeg) <= 5.0 -> Color.White.copy(alpha = 0.78f)
        else -> Color(0xFFFFC107).copy(alpha = 0.88f)
    }
    val stroke = 1.6.dp.toPx()
    val refStroke = 1.2.dp.toPx()
    val innerGap = 11.dp.toPx()
    val armLen = 16.dp.toPx()
    val refGap = innerGap + armLen + 4.dp.toPx()
    val refLen = 6.dp.toPx()
    val refColor = if (level) Color(0xFF38DF85).copy(alpha = 0.65f) else Color.White.copy(alpha = 0.35f)
    // Fixed horizontal reference ticks on outer left and right.
    drawLine(refColor, Offset(mid.x - refGap - refLen, mid.y), Offset(mid.x - refGap, mid.y), refStroke, cap = StrokeCap.Round)
    drawLine(refColor, Offset(mid.x + refGap, mid.y), Offset(mid.x + refGap + refLen, mid.y), refStroke, cap = StrokeCap.Round)
    // Tilted horizon bar matching real-world horizontal plane.
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

private fun DrawScope.drawCandidateBrackets(candidates: List<CandidateOverlay>) {
    if (candidates.isEmpty()) return
    val stroke = 1.8.dp.toPx()
    for (c in candidates) {
        val color = if (c.userPicked) Color(0xFFFFD54F) else Color(0xAA4FC3F7)
        val w = c.right - c.left
        val h = c.bottom - c.top
        val corner = minOf(14.dp.toPx(), w * 0.25f, h * 0.25f)
        // Top-left
        drawLine(color, Offset(c.left, c.top), Offset(c.left + corner, c.top), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(c.left, c.top), Offset(c.left, c.top + corner), stroke, cap = StrokeCap.Round)
        // Top-right
        drawLine(color, Offset(c.right, c.top), Offset(c.right - corner, c.top), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(c.right, c.top), Offset(c.right, c.top + corner), stroke, cap = StrokeCap.Round)
        // Bottom-left
        drawLine(color, Offset(c.left, c.bottom), Offset(c.left + corner, c.bottom), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(c.left, c.bottom), Offset(c.left, c.bottom - corner), stroke, cap = StrokeCap.Round)
        // Bottom-right
        drawLine(color, Offset(c.right, c.bottom), Offset(c.right - corner, c.bottom), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(c.right, c.bottom), Offset(c.right, c.bottom - corner), stroke, cap = StrokeCap.Round)
    }
}

private fun DrawScope.drawTapReticle(tap: Point) {
    val color = Color(0xFFFFD54F)
    val radius = 18.dp.toPx()
    val stroke = 1.8.dp.toPx()
    val center = Offset(tap.x, tap.y)
    drawCircle(color, radius, center, style = Stroke(stroke))
    drawCircle(color, 3.dp.toPx(), center)
}

private fun DrawScope.drawCompositionGuides(selection: PresetSelection) {
    val w = size.width
    val h = size.height
    val lineColor = Color.White.copy(alpha = 0.26f)
    val accentColor = Color(0xFFB3DBBE).copy(alpha = 0.55f)
    val stroke = 1.dp.toPx()
    val dash = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
    when (selection.preset) {
        CompositionPreset.AUTO -> Unit
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
private fun PresetPillBar(selection: PresetSelection, onSelect: (CompositionPreset) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionPreset.entries.forEach { preset ->
            val selected = selection.preset == preset
            val text = when {
                preset == CompositionPreset.RULE_OF_THIRDS && selected ->
                    if (selection.thirdChoice == CompositionChoice.LEFT_THIRD) "三分·左" else "三分·右"
                else -> preset.label
            }
            val bg = if (selected) Color(0xFFB3DBBE) else Color(0x2EFFFFFF)
            val fg = if (selected) Color(0xFF102016) else Color(0xFFE0E5E2)
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(bg)
                    .clickable(role = Role.RadioButton) { onSelect(preset) }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text, color = fg, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun CameraFlipButton(front: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) Color(0xFFB3DBBE) else Color(0x55B3DBBE)
    val border = if (enabled) Color(0xFF5A6560) else Color(0x335A6560)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, border),
        modifier = Modifier
            .size(40.dp)
            .semantics { contentDescription = if (front) "切换到后置摄像头" else "切换到前置摄像头" },
    ) {
        Canvas(Modifier.fillMaxSize().padding(9.dp)) {
            val w = size.width
            val h = size.height
            val strokeWidth = 1.9.dp.toPx()
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val inset = strokeWidth * 0.6f
            val arcSize = Size(w - inset * 2f, h - inset * 2f)
            val topLeft = Offset(inset, inset)
            // Top-right arc + arrowhead
            drawArc(tint, startAngle = -145f, sweepAngle = 125f, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
            val topArrow = Path().apply {
                moveTo(w * 0.72f, h * 0.24f)
                lineTo(w * 0.92f, h * 0.34f)
                lineTo(w * 0.87f, h * 0.12f)
            }
            drawPath(topArrow, tint, style = stroke)
            // Bottom-left arc + arrowhead
            drawArc(tint, startAngle = 35f, sweepAngle = 125f, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
            val bottomArrow = Path().apply {
                moveTo(w * 0.28f, h * 0.76f)
                lineTo(w * 0.08f, h * 0.66f)
                lineTo(w * 0.13f, h * 0.88f)
            }
            drawPath(bottomArrow, tint, style = stroke)
        }
    }
}

@Composable
private fun DirectorCuePill(cue: DirectorCue) {
    val accent = when (cue.category) {
        DirectorCategory.LIGHTING -> Color(0xFFFFD54F)
        DirectorCategory.POSE -> Color(0xFFFFAB91)
        DirectorCategory.BACKGROUND -> Color(0xFF81D4FA)
        DirectorCategory.FRAMING -> Color(0xFFB3DBBE)
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xBF141917),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.72f)),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent.copy(alpha = 0.22f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(cue.category.badge, color = accent, fontSize = 11.sp, maxLines = 1)
            }
            Text(cue.tip, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LastPhotoThumb(review: PostShotReview, onClick: () -> Unit) {
    val context = LocalContext.current
    val thumb by produceState<android.graphics.Bitmap?>(null, review.photoUri) {
        value = withContext(Dispatchers.IO) { PostShotRecrop.loadPreviewBitmap(context, review.photoUri, maxEdge = 160) }
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = Color(0x33FFFFFF),
        border = BorderStroke(1.dp, Color(0xFFB3DBBE)),
        modifier = Modifier
            .size(40.dp)
            .semantics { contentDescription = "打开上次成片轻复盘与二次构图" },
    ) {
        val bmp = thumb
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "上次拍摄缩略图",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("复盘", color = Color(0xFFB3DBBE), fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun StepProgressPill(step: StepProgress) {
    val accent = if (step.satisfied) Color(0xFF38DF85) else Color(0xFF4FC3F7)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xC7111A1E),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.80f)),
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = if (step.satisfied) "✓ 机位已到位" else "${step.direction.arrow} ${step.direction.label}",
                color = accent,
                fontSize = 12.sp,
                maxLines = 1,
            )
            Box(
                Modifier
                    .width(96.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0x33FFFFFF)),
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
        "S" -> Color(0xFF38DF85)
        "A" -> Color(0xFFB3DBBE)
        "B" -> Color(0xFFFFD54F)
        else -> Color(0xFFFFAB91)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0x1FFFFFFF),
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
                Text("成片四维评估", color = Color.White, fontSize = 13.sp)
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
                    Text(label, color = Color(0xFFB8C5BE), fontSize = 11.sp, modifier = Modifier.width(52.dp))
                    Box(
                        Modifier
                            .weight(1f)
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0x28FFFFFF)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth((value / 100f).coerceIn(0.05f, 1f))
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (value >= 80) Color(0xFF38DF85) else Color(0xFFFFD54F)),
                        )
                    }
                    Text("$value", color = Color.White, fontSize = 11.sp, modifier = Modifier.width(24.dp))
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
        Surface(shape = MaterialTheme.shapes.large) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("拍后轻复盘 · 二次构图", style = MaterialTheme.typography.titleMedium)
                Text(review.summary, fontSize = 13.sp, color = Color(0xFFD5E0DA))
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
                            .clip(RoundedCornerShape(10.dp))
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
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF141816)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("正在加载照片…", color = Color(0xFFB0BEC5), fontSize = 13.sp)
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
                    Text(it.subtitle, fontSize = 12.sp, color = Color(0xFFB3DBBE))
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = prefText,
                        color = Color(0xFF9EB0A6),
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
                        Text("重置偏好", fontSize = 11.sp)
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("关闭") }
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
