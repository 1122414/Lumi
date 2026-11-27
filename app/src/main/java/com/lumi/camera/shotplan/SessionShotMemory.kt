package com.lumi.camera.shotplan

import java.util.Locale

internal data class SessionShotRecord(
    val capturedAtMs: Long,
    val subjectName: String?,
    val choice: CompositionChoice?,
    val zoomRatio: Float,
    val wasReady: Boolean,
    val rollDeg: Double?,
    val lightTag: String?,
    val tipGiven: String?,
)

// Keeps short-term memory of recent shots within the active shooting session so the AI director
// can give progressive "better than the previous shot" follow-up advice instead of repeating itself.
internal class SessionShotMemory(
    private val maxItems: Int = 3,
    private val expiryMs: Long = 180_000L,
) {
    private val records = ArrayDeque<SessionShotRecord>()

    fun record(shot: SessionShotRecord) {
        prune(shot.capturedAtMs)
        records.addLast(shot)
        while (records.size > maxItems) records.removeFirst()
    }

    fun clear() {
        records.clear()
    }

    fun recentCount(nowMs: Long): Int {
        prune(nowMs)
        return records.size
    }

    fun promptSummary(nowMs: Long): String? {
        prune(nowMs)
        val last = records.lastOrNull() ?: return null
        val ageSec = ((nowMs - last.capturedAtMs) / 1000L).coerceAtLeast(1L)
        val zoomText = String.format(Locale.ROOT, "%.1f×", last.zoomRatio).removeSuffix(".0×").let {
            if (it.endsWith("×")) it else "${it}×"
        }
        val subjectPart = last.subjectName?.takeIf { it.isNotBlank() } ?: "同场景"
        val choicePart = last.choice?.label ?: "自然构图"
        val statusPart = buildList {
            add(if (last.wasReady) "上次构图已对齐" else "上次未完全对齐")
            if (last.rollDeg != null && kotlin.math.abs(last.rollDeg) > 3.5) add("画面略歪")
            last.lightTag?.takeIf { it.isNotBlank() }?.let { add("光线:$it") }
        }.joinToString("，")
        val countHint = if (records.size >= 2) "（本组已连拍${records.size}张）" else ""
        return "【连拍迭代记忆】${ageSec}秒前刚拍过「$subjectPart·$choicePart」（$zoomText，$statusPart）$countHint。" +
            "如仍在拍同一场景，请避免机械重复，优先建议更进阶的景别/构图变化、表情神态或光影升级，帮助用户拍出比上一张更出片的一张。"
    }

    private fun prune(nowMs: Long) {
        while (records.isNotEmpty() && nowMs - records.first().capturedAtMs > expiryMs) {
            records.removeFirst()
        }
    }
}
