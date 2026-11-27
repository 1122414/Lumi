package com.lumi.camera.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lumi.camera.BuildConfig
import com.lumi.camera.camera.DeviceQuirks
import com.lumi.camera.observability.LumiCrashGuard
import com.lumi.camera.observability.LumiTelemetry

internal object PrivacyConsentStore {
    private const val PREFS = "lumi_privacy_prefs"
    private const val KEY_VERSION = "accepted_policy_version"
    const val CURRENT_POLICY_VERSION = 1

    fun hasAccepted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_VERSION, 0) >= CURRENT_POLICY_VERSION

    fun markAccepted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_VERSION, CURRENT_POLICY_VERSION)
            .apply()
    }
}

@Composable
internal fun PrivacyAndHealthDialog(
    firstLaunch: Boolean,
    crashCount: Int,
    latestCrashSummary: String?,
    onClearLocalData: () -> Unit,
    onAcceptOrClose: () -> Unit,
) {
    val context = LocalContext.current
    val quirks = DeviceQuirks.current()
    val telemetry = LumiTelemetry.snapshot()

    Dialog(onDismissRequest = { if (!firstLaunch) onAcceptOrClose() }) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = LumiColors.SurfaceDark,
            border = BorderStroke(1.dp, LumiColors.Hairline),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = if (firstLaunch) "欢迎使用 Lumi · 隐私与拍摄说明" else "关于 Lumi · 隐私与运行健康",
                    color = LumiColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x10FFFFFF))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("隐私保护承诺", color = LumiColors.Ivory, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = "• 实时取景、端侧离线构图、3D LUT 滤镜与相册管理均在本地完成，不静默上传照片。\n" +
                            "• 仅当你主动触发「AI 构图」或载入参考图且启用云端服务时，才会发送经压缩的低分辨率单帧用于实时构图分析。\n" +
                            "• 异常日志与性能打点均自动脱敏，不收集任何个人身份信息或密钥。",
                        color = LumiColors.TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }

                if (!firstLaunch) {
                    HorizontalDivider(color = LumiColors.Hairline)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "版本与设备兼容（v${BuildConfig.VERSION_NAME}）",
                            color = LumiColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "机型策略：${quirks.summaryLabel}（扩展支持：${if (quirks.allowVendorExtensions) "已启用" else "兼容避让"}）",
                            color = LumiColors.TextSecondary,
                            fontSize = 11.sp,
                        )
                        Text(
                            text = telemetry.detailedReport(),
                            color = LumiColors.TextMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 16.sp,
                        )
                    }

                    HorizontalDivider(color = LumiColors.Hairline)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (crashCount == 0) "崩溃防护：运行稳定（0 条异常记录）" else "崩溃防护：本地记录 $crashCount 条脱敏异常",
                            color = if (crashCount == 0) LumiColors.Emerald else LumiColors.Amber,
                            fontSize = 12.sp,
                        )
                        if (!latestCrashSummary.isNullOrBlank()) {
                            Text(
                                text = latestCrashSummary,
                                color = LumiColors.TextMuted,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            TextButton(
                                onClick = {
                                    val full = LumiCrashGuard.latestCrashDetail(context) ?: latestCrashSummary
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    cm?.setPrimaryClip(ClipData.newPlainText("Lumi Crash Log", full))
                                    Toast.makeText(context, "已复制脱敏异常日志", Toast.LENGTH_SHORT).show()
                                },
                            ) {
                                Text("复制完整异常日志", color = LumiColors.Ivory, fontSize = 11.sp)
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (firstLaunch) Arrangement.End else Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!firstLaunch) {
                        TextButton(onClick = onClearLocalData) {
                            Text("清除本地偏好与日志", color = LumiColors.TextSecondary, fontSize = 12.sp)
                        }
                    }
                    Button(onClick = onAcceptOrClose) {
                        Text(if (firstLaunch) "知晓并开始拍摄" else "完成")
                    }
                }
            }
        }
    }
}
