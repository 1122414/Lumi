package com.lumi.camera.shotplan

internal enum class DirectorCategory(val wire: String, val badge: String) {
    FRAMING("framing", "构图"),
    BACKGROUND("background", "背景"),
    LIGHTING("lighting", "光线"),
    POSE("pose", "姿势");

    companion object {
        fun parse(raw: Any?): DirectorCategory? {
            val key = (raw as? String)?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wire == key }
        }
    }
}

internal data class DirectorCue(
    val category: DirectorCategory,
    val tip: String,
) {
    companion object {
        const val MAX_TIP_CHARS = 12

        fun parse(categoryRaw: Any?, tipRaw: Any?): DirectorCue? {
            val rawTip = (tipRaw as? String)?.trim().orEmpty()
            if (rawTip.isEmpty() || rawTip.length > 60) return null
            if (rawTip.any { it.isISOControl() || it == '\u2028' || it == '\u2029' }) return null
            val category = DirectorCategory.parse(categoryRaw) ?: inferCategory(rawTip)
            val compact = compactTip(rawTip)
            if (compact.isEmpty()) return null
            return DirectorCue(category, compact)
        }

        private fun inferCategory(tip: String): DirectorCategory = when {
            listOf("光", "亮", "暗", "逆光", "窗", "阴影", "曝光").any { it in tip } -> DirectorCategory.LIGHTING
            listOf("笑", "抬头", "低头", "侧身", "看镜头", "手", "肩", "站姿", "坐姿", "转头", "下巴").any { it in tip } -> DirectorCategory.POSE
            listOf("背景", "杂物", "路人", "柱", "移半步", "左移", "右移", "避开", "挡").any { it in tip } -> DirectorCategory.BACKGROUND
            else -> DirectorCategory.FRAMING
        }

        internal fun compactTip(raw: String): String {
            val trimmed = raw.trim().trimEnd('。', '.', '！', '!', '；', ';')
            if (trimmed.codePointCount(0, trimmed.length) <= MAX_TIP_CHARS) return trimmed
            val firstClause = trimmed.takeWhile { it !in "，。；！？、,;.!?" }.trim()
            if (firstClause.isNotEmpty() && firstClause.codePointCount(0, firstClause.length) <= MAX_TIP_CHARS) {
                return firstClause
            }
            val end = trimmed.offsetByCodePoints(0, MAX_TIP_CHARS - 1)
            return trimmed.substring(0, end) + "…"
        }
    }
}
