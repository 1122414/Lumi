package com.lumi.camera.shotplan

import android.os.SystemClock
import com.lumi.camera.BuildConfig
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

internal class VisionChat(private val config: ModelConfig, private val deadlineNs: Long) {
    companion object {
        const val TIMEOUT_NOTICE = "推荐超时（20 秒），可以重新推荐。"
    }

    private val connection = AtomicReference<HttpURLConnection?>()
    @Volatile private var cancelled = false

    fun cancel(worker: Executor) {
        cancelled = true
        connection.getAndSet(null)?.let { worker.execute(it::disconnect) }
    }

    fun remainingMs(): Int {
        val remaining = deadlineNs - SystemClock.elapsedRealtimeNanos()
        if (cancelled || remaining <= 0) throw ModelFailure(ConnectionResult.UNREACHABLE, TIMEOUT_NOTICE)
        return ((remaining + 999_999L) / 1_000_000L).toInt()
    }

    fun send(
        jpeg: ByteArray,
        preset: CompositionPreset = CompositionPreset.AUTO,
        candidates: List<SubjectCandidate> = emptyList(),
        zoomStops: List<Float> = emptyList(),
        currentZoom: Float = 1f,
        preferencePrior: String? = null,
        telemetrySummary: String? = null,
        requestExposureAdvice: Boolean = false,
    ): ModelReply {
        remainingMs()
        val payload = ChatProtocol.request(config.model, jpeg, preset, candidates, zoomStops, currentZoom, preferencePrior, telemetrySummary, requestExposureAdvice)
        val url = checkNotNull(chatEndpoint(config.address, BuildConfig.DEBUG))
        val conn = (if (url.protocol == "http") url.openConnection(Proxy.NO_PROXY) else url.openConnection()) as HttpURLConnection
        connection.set(conn)
        var keepAlive = false
        try {
            conn.connectTimeout = remainingMs()
            conn.readTimeout = remainingMs()
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Connection", "keep-alive")
            conn.setRequestProperty("Authorization", "Bearer " + config.apiKey)
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }
            remainingMs()
            val code = conn.responseCode
            if (code !in 200..299) {
                runCatching { conn.errorStream?.close() }
                throw ModelFailure(ConnectionResult.httpFailure(code), "推荐服务返回 HTTP $code，可以重新推荐。", code)
            }
            val body = conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                while (true) {
                    remainingMs()
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > 65_536) throw ModelFailure(ConnectionResult.FORMAT, "推荐返回内容过大。")
                    out.write(buf, 0, n)
                }
                out.toString("UTF-8")
            }
            // Fully reading and closing inputStream lets Android's HttpURLConnection pool reuse the TLS socket.
            keepAlive = !cancelled
            return try { ChatProtocol.response(body, candidates, zoomStops, currentZoom) } catch (_: Exception) { null }
                ?: throw ModelFailure(ConnectionResult.FORMAT, "模型未返回可识别的主体，请重新推荐。", rawReply = body.take(1500))
        } finally {
            connection.compareAndSet(conn, null)
            if (!keepAlive) conn.disconnect()
        }
    }
}
