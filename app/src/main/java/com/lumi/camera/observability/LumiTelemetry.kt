package com.lumi.camera.observability

import android.os.SystemClock
import java.util.Locale
import kotlin.math.roundToInt

internal data class TelemetrySnapshot(
    val coldStartToFirstFrameMs: Long?,
    val avgShutterLatencyMs: Long?,
    val avgFilterSaveMs: Long?,
    val aiTriggeredCount: Int,
    val offlineBaselineCount: Int,
    val cloudSuccessCount: Int,
    val cloudFailureCount: Int,
    val cloudLatencyP50Ms: Long?,
    val cloudLatencyP95Ms: Long?,
    val guidanceReadyCount: Int,
    val readyShutterCount: Int,
    val totalCaptures: Int,
) {
    val cloudSuccessRatePct: Int?
        get() {
            val total = cloudSuccessCount + cloudFailureCount
            return if (total == 0) null else ((cloudSuccessCount * 100.0) / total).roundToInt()
        }

    val readyConversionPct: Int?
        get() = if (guidanceReadyCount == 0) null
        else ((readyShutterCount * 100.0) / guidanceReadyCount).roundToInt().coerceAtMost(100)

    fun compactSummary(): String {
        val parts = mutableListOf<String>()
        coldStartToFirstFrameMs?.let { parts += "首帧 ${it}ms" }
        cloudLatencyP50Ms?.let { parts += "AI P50 ${it}ms" }
        cloudSuccessRatePct?.let { parts += "云端成功率 ${it}%" }
        avgFilterSaveMs?.let { parts += "滤镜出图 ${it}ms" }
        if (totalCaptures > 0) parts += "本次拍摄 ${totalCaptures} 张"
        readyConversionPct?.let { parts += "绿环成片率 ${it}%" }
        return if (parts.isEmpty()) "本次会话尚无拍摄打点" else parts.joinToString(" · ")
    }

    fun detailedReport(): String = buildString {
        appendLine("冷启动首帧：${coldStartToFirstFrameMs?.let { "${it} ms" } ?: "—"}")
        appendLine("快门落盘均值：${avgShutterLatencyMs?.let { "${it} ms" } ?: "—"}")
        appendLine("滤镜异步渲染均值：${avgFilterSaveMs?.let { "${it} ms" } ?: "—"}")
        appendLine("AI 构图触发：${aiTriggeredCount} 次（端侧瞬时 ${offlineBaselineCount} 次）")
        val cloudRate = cloudSuccessRatePct?.let { "$it%" } ?: "—"
        appendLine("云端导演：成功 $cloudSuccessCount / 失败 $cloudFailureCount（$cloudRate）")
        appendLine("云端耗时：P50 ${cloudLatencyP50Ms?.let { "${it} ms" } ?: "—"} / P95 ${cloudLatencyP95Ms?.let { "${it} ms" } ?: "—"}")
        val conv = readyConversionPct?.let { "$it%" } ?: "—"
        append(
            String.format(
                Locale.ROOT,
                "对齐转化：就绪 %d 次 · 对齐快门 %d 次（%s） · 总快门 %d",
                guidanceReadyCount,
                readyShutterCount,
                conv,
                totalCaptures,
            ),
        )
    }
}

/**
 * Lightweight, privacy-safe in-memory North Star telemetry recorder.
 * Never records image pixels, user PII, or API credentials.
 */
internal object LumiTelemetry {
    private const val MAX_SAMPLES = 64
    private val lock = Any()

    private var processStartElapsedMs: Long? = null
    private var coldStartToFirstFrameMs: Long? = null

    private val shutterLatencies = ArrayDeque<Long>()
    private val filterSaveLatencies = ArrayDeque<Long>()
    private val cloudLatencies = ArrayDeque<Long>()

    private var aiTriggeredCount = 0
    private var offlineBaselineCount = 0
    private var cloudSuccessCount = 0
    private var cloudFailureCount = 0
    private var guidanceReadyCount = 0
    private var readyShutterCount = 0
    private var totalCaptures = 0

    fun markProcessStart(nowElapsedMs: Long = SystemClock.elapsedRealtime()) = synchronized(lock) {
        if (processStartElapsedMs == null) {
            processStartElapsedMs = nowElapsedMs
        }
    }

    fun markFirstViewfinderFrame(nowElapsedMs: Long = SystemClock.elapsedRealtime()) = synchronized(lock) {
        if (coldStartToFirstFrameMs == null) {
            val start = processStartElapsedMs ?: return@synchronized
            val delta = (nowElapsedMs - start).coerceAtLeast(0L)
            if (delta in 0..30_000L) {
                coldStartToFirstFrameMs = delta
            }
        }
    }

    fun recordAiTriggered(launchedOfflineBaseline: Boolean) = synchronized(lock) {
        aiTriggeredCount++
        if (launchedOfflineBaseline) offlineBaselineCount++
    }

    fun recordCloudOutcome(succeeded: Boolean, latencyMs: Long? = null) = synchronized(lock) {
        if (succeeded) {
            cloudSuccessCount++
            if (latencyMs != null && latencyMs in 0..60_000L) {
                addBounded(cloudLatencies, latencyMs)
            }
        } else {
            cloudFailureCount++
        }
    }

    fun recordGuidanceReady() = synchronized(lock) {
        guidanceReadyCount++
    }

    fun recordCapture(wasReady: Boolean, shutterLatencyMs: Long? = null, filterSaveMs: Long? = null) = synchronized(lock) {
        totalCaptures++
        if (wasReady) readyShutterCount++
        if (shutterLatencyMs != null && shutterLatencyMs in 0..30_000L) {
            addBounded(shutterLatencies, shutterLatencyMs)
        }
        if (filterSaveMs != null && filterSaveMs in 0..30_000L) {
            addBounded(filterSaveLatencies, filterSaveMs)
        }
    }

    fun snapshot(): TelemetrySnapshot = synchronized(lock) {
        TelemetrySnapshot(
            coldStartToFirstFrameMs = coldStartToFirstFrameMs,
            avgShutterLatencyMs = shutterLatencies.averageOrNull(),
            avgFilterSaveMs = filterSaveLatencies.averageOrNull(),
            aiTriggeredCount = aiTriggeredCount,
            offlineBaselineCount = offlineBaselineCount,
            cloudSuccessCount = cloudSuccessCount,
            cloudFailureCount = cloudFailureCount,
            cloudLatencyP50Ms = percentile(cloudLatencies, 0.50),
            cloudLatencyP95Ms = percentile(cloudLatencies, 0.95),
            guidanceReadyCount = guidanceReadyCount,
            readyShutterCount = readyShutterCount,
            totalCaptures = totalCaptures,
        )
    }

    fun reset() = synchronized(lock) {
        processStartElapsedMs = null
        coldStartToFirstFrameMs = null
        shutterLatencies.clear()
        filterSaveLatencies.clear()
        cloudLatencies.clear()
        aiTriggeredCount = 0
        offlineBaselineCount = 0
        cloudSuccessCount = 0
        cloudFailureCount = 0
        guidanceReadyCount = 0
        readyShutterCount = 0
        totalCaptures = 0
    }

    private fun addBounded(queue: ArrayDeque<Long>, value: Long) {
        if (queue.size >= MAX_SAMPLES) queue.removeFirst()
        queue.addLast(value)
    }

    private fun ArrayDeque<Long>.averageOrNull(): Long? =
        if (isEmpty()) null else (sum().toDouble() / size).roundToInt().toLong()

    private fun percentile(samples: ArrayDeque<Long>, p: Double): Long? {
        if (samples.isEmpty()) return null
        val sorted = samples.sorted()
        val idx = ((sorted.size - 1) * p).roundToInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }
}
