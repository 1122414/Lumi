package com.lumi.camera.shotplan

import android.os.SystemClock
import com.lumi.camera.BuildConfig
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

internal class ModelFailure(val notice: String) : Exception()

internal class VisionChat(private val token: RequestToken) {
    companion object {
        private val endpoint: URL? = runCatching {
            val base = URI(BuildConfig.LUMI_LLM_BASE_URL)
            require(base.scheme.equals("https", true) && !base.host.isNullOrBlank() &&
                base.userInfo == null && base.query == null && base.fragment == null)
            val root = base.toASCIIString().trimEnd('/')
            when {
                base.path.trim('/').isEmpty() -> URL(root + "/v1/chat/completions")
                root.endsWith("/chat/completions") -> URL(root)
                else -> URL(root + "/chat/completions")
            }
        }.getOrNull()
        val unavailableReason: String? = when {
            !BuildConfig.DEBUG -> "此版本未启用推荐服务。"
            listOf(BuildConfig.LUMI_LLM_BASE_URL, BuildConfig.LUMI_LLM_MODEL, BuildConfig.LUMI_LLM_API_KEY)
                .any(String::isBlank) -> "推荐服务未配置，暂不能推荐构图。"
            endpoint == null -> "推荐服务地址无效，请配置 HTTPS 接口。"
            else -> null
        }
        const val TIMEOUT_NOTICE = "推荐超时（20 秒），可以重新推荐。"
        fun logReason(reason: String): String = if (BuildConfig.LUMI_LLM_API_KEY.isEmpty()) reason
            else reason.replace(BuildConfig.LUMI_LLM_API_KEY, "［已隐藏］")
    }

    private val connection = AtomicReference<HttpURLConnection?>()
    @Volatile private var cancelled = false

    fun cancel(worker: Executor) {
        cancelled = true
        connection.getAndSet(null)?.let { worker.execute(it::disconnect) }
    }

    fun remainingMs(): Int {
        val remaining = token.deadlineNs - SystemClock.elapsedRealtimeNanos()
        if (cancelled || remaining <= 0) throw ModelFailure(TIMEOUT_NOTICE)
        return ((remaining + 999_999L) / 1_000_000L).toInt()
    }

    fun send(jpeg: ByteArray): Recommendation {
        remainingMs()
        val payload = ChatProtocol.request(BuildConfig.LUMI_LLM_MODEL, jpeg)
        val conn = checkNotNull(endpoint).openConnection() as HttpURLConnection
        connection.set(conn)
        try {
            conn.connectTimeout = remainingMs()
            conn.readTimeout = remainingMs()
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer " + BuildConfig.LUMI_LLM_API_KEY)
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }
            remainingMs()
            val code = conn.responseCode
            if (code !in 200..299) throw ModelFailure("推荐服务返回 HTTP $code，可以重新推荐。")
            val body = conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                while (true) {
                    remainingMs()
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > 65_536) throw ModelFailure("推荐返回内容过大。")
                    out.write(buf, 0, n)
                }
                out.toString("UTF-8")
            }
            return try { ChatProtocol.response(body) } catch (_: Exception) { null }
                ?: throw ModelFailure("未获得可信的推荐构图，可以重新推荐。")
        } finally {
            connection.compareAndSet(conn, null)
            conn.disconnect()
        }
    }
}
