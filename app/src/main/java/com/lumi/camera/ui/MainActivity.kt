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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import com.lumi.camera.camera.CameraPermissions
import com.lumi.camera.camera.CameraSession
import com.lumi.camera.camera.Point
import java.util.Locale

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
    var marker by remember { mutableStateOf<Point?>(null) }
    var stats by remember { mutableStateOf("等待分析帧") }
    var bound by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(false) }
    val session = remember(preview, owner) {
        var nextStatsNs = Long.MIN_VALUE
        CameraSession(preview, owner, { result ->
            marker = result?.marker
            if (result != null && result.frame.timestampNs >= nextStatsNs) {
                val f = result.frame.geometry
                stats = String.format(Locale.ROOT, "%.1f fps  ·  %.1f ms\n%d × %d  ·  %d°",
                    result.fps, result.elapsedMs, f.buffer.width, f.buffer.height, f.rotation)
                nextStatsNs = result.frame.timestampNs + 250_000_000L
            }
        }, { active ->
            bound = active
            if (!active) { selected = false; stats = "等待分析帧"; nextStatsNs = Long.MIN_VALUE }
        }, onMessage)
    }
    DisposableEffect(session) {
        onSession(session)
        session.attach()
        onDispose { onSession(null); session.dispose() }
    }
    Text("Lumi   /   坐标验证", Modifier.padding(8.dp), color = Color.White)
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(3f / 4f).background(Color.Black)) {
            AndroidView({ preview }, Modifier.matchParentSize())
            Canvas(Modifier.matchParentSize().pointerInput(session) {
                detectTapGestures { if (bound) {
                    selected = true
                    session.select(Point(it.x, it.y))
                } }
            }) {
                marker?.let {
                    val center = Offset(it.x, it.y)
                    drawCircle(Color.Black, 10.dp.toPx(), center, style = Stroke(4.dp.toPx()))
                    drawCircle(Color(0xFFFFCF73), 10.dp.toPx(), center, style = Stroke(2.dp.toPx()))
                    drawCircle(Color.White, 1.5.dp.toPx(), center)
                }
            }
            Text(stats, Modifier.padding(8.dp).background(Color.Black.copy(alpha = .7f)).padding(5.dp),
                color = Color.White, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
    Text(when {
        !bound -> "正在打开后置相机"
        !selected -> "轻点一个有纹理的物体，验证标记位置"
        marker == null -> "未确认标记，可重新点选"
        else -> "移动手机，观察标记是否贴住原处"
    }, Modifier.padding(top = 8.dp), color = Color.White, fontSize = 13.sp)
    Button(onClick = onShoot, enabled = bound, modifier = Modifier.padding(8.dp)) { Text("拍照并保存") }
}
