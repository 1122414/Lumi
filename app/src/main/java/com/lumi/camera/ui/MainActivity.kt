package com.lumi.camera.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import com.lumi.camera.BuildConfig
import com.lumi.camera.camera.CameraPermissions
import com.lumi.camera.camera.CameraSession
import com.lumi.camera.camera.Viewfinder
import com.lumi.camera.camera.Point
import com.lumi.camera.shotplan.VisionChat
import kotlin.math.abs
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
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFB3DBBE))) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                    .safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (allowed) {
                        CameraScreen(this@MainActivity, { session = it }, permissions::requestAlbum, ::notifyUser)
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
private fun ColumnScope.CameraScreen(owner: MainActivity, onSession: (CameraSession?) -> Unit,
    onShoot: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val preview = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var frame by remember { mutableStateOf(Viewfinder()) }
    var paintRing by remember { mutableStateOf<Point?>(null) }
    var bound by remember { mutableStateOf(false) }
    var recommendationStatus by remember { mutableStateOf<String?>(null) }
    val session = remember(preview, owner) {
        CameraSession(preview, owner, { next ->
            frame = next
            if (next.ring != null || !next.hasTarget) paintRing = next.ring
        }, { bound = it }, onMessage, { recommendationStatus = it })
    }
    DisposableEffect(session) {
        onSession(session)
        session.attach()
        onDispose { onSession(null); session.dispose() }
    }

    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(frame.merged) {
        settled = false
        if (frame.merged) {
            delay(500)
            settled = true
        }
    }
    val alpha by animateFloatAsState(if (frame.ring != null && !settled) 1f else 0f,
        tween(120), label = "ring visibility")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val controlsHeight = minOf(90.dp, maxHeight * 0.24f)
        Box(Modifier.fillMaxSize().padding(vertical = controlsHeight), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(3f / 4f).background(Color.Black)) {
                AndroidView({ preview }, Modifier.matchParentSize())
                Canvas(Modifier.matchParentSize().clipToBounds()) {
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
                    drawCircle(Color.White, 3.dp.toPx(), mid)
                }
            }
        }
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Lumi", color = Color.White)
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
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter), horizontalAlignment = Alignment.CenterHorizontally) {
            frame.reason?.let { Text(it, color = Color.White, fontSize = 16.sp, maxLines = 1) }
            VisionChat.unavailableReason?.let { Text(it, color = Color.LightGray, fontSize = 12.sp) }
            Text(when {
                frame.ready -> "可以拍"
                !bound -> "正在打开后置相机"
                recommendationStatus != null -> recommendationStatus.orEmpty()
                frame.hasTarget && frame.ring == null -> "构图暂不可确认"
                frame.hasTarget -> "构图引导中"
                else -> "对准想拍的人或物"
            }, color = Color.White, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(session::recommend, enabled = bound && VisionChat.unavailableReason == null) { Text("推荐构图") }
                OutlinedButton(onShoot, enabled = bound) { Text("拍照并保存") }
            }
        }
    }
}
