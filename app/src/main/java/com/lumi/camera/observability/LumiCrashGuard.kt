package com.lumi.camera.observability

import android.content.Context
import android.os.Build
import com.lumi.camera.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Privacy-sanitized local crash recorder.
 * Strips bearer tokens, API keys, and query credentials before persisting crash snapshots.
 */
internal object LumiCrashGuard {
    private const val DIR_NAME = "lumi_crash_logs"
    private const val MAX_FILES = 5

    private val secretPatterns = listOf(
        Regex("""(?i)(bearer\s+)[a-zA-Z0-9._\-+/=]{6,}"""),
        Regex("""(?i)(sk-[a-zA-Z0-9_\-]{8,})"""),
        Regex("""(?i)((?:api[_-]?key|token|authorization|password|secret)\s*[:=]\s*)[^\s,;'"&]+"""),
        Regex("""https?://[^/\s:@]+:[^/\s@]+@"""),
    )

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val appContext = context.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching { recordCrash(appContext, thread.name, throwable) }
                previous?.uncaughtException(thread, throwable)
            }
            installed = true
        }
    }

    internal fun sanitize(raw: String): String {
        var result = raw
        result = secretPatterns[0].replace(result) { "${it.groupValues[1]}[REDACTED]" }
        result = secretPatterns[1].replace(result, "[REDACTED_KEY]")
        result = secretPatterns[2].replace(result) { "${it.groupValues[1]}[REDACTED]" }
        result = secretPatterns[3].replace(result, "https://[REDACTED]@")
        return result
    }

    internal fun formatCrashReport(
        threadName: String,
        throwable: Throwable,
        timestampMs: Long = System.currentTimeMillis(),
        versionName: String = BuildConfig.VERSION_NAME,
        deviceLabel: String = "${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})",
    ): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(timestampMs))
        val body = buildString {
            appendLine("time=$time")
            appendLine("version=$versionName")
            appendLine("device=$deviceLabel")
            appendLine("thread=$threadName")
            appendLine("exception=${throwable.javaClass.name}: ${throwable.message.orEmpty()}")
            append(sw.toString().take(4000))
        }
        return sanitize(body)
    }

    private fun recordCrash(context: Context, threadName: String, throwable: Throwable) {
        val dir = crashDir(context)
        if (!dir.exists()) dir.mkdirs()
        val now = System.currentTimeMillis()
        val file = File(dir, "crash_$now.log")
        file.writeText(formatCrashReport(threadName, throwable, now))
        val existing = dir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
        existing.drop(MAX_FILES).forEach { runCatching { it.delete() } }
    }

    fun crashCount(context: Context): Int =
        crashDir(context).listFiles()?.count { it.isFile && it.name.endsWith(".log") } ?: 0

    fun latestCrashSummary(context: Context): String? {
        val latest = crashDir(context)
            .listFiles()
            ?.filter { it.isFile && it.name.endsWith(".log") }
            ?.maxByOrNull { it.lastModified() }
            ?: return null
        return runCatching {
            latest.readLines().take(5).joinToString("\n")
        }.getOrNull()
    }

    fun latestCrashDetail(context: Context): String? {
        val latest = crashDir(context)
            .listFiles()
            ?.filter { it.isFile && it.name.endsWith(".log") }
            ?.maxByOrNull { it.lastModified() }
            ?: return null
        return runCatching { latest.readText() }.getOrNull()
    }

    fun clearLogs(context: Context) {
        crashDir(context).listFiles()?.forEach { runCatching { it.delete() } }
    }

    private fun crashDir(context: Context): File = File(context.filesDir, DIR_NAME)
}
