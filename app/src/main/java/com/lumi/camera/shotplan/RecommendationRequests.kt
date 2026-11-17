package com.lumi.camera.shotplan

internal data class RequestToken(val id: Long, val deadlineNs: Long)

// Owned by the camera session's main thread. A completed or cancelled token is single-use.
internal class RecommendationRequests {
    private var serial = 0L
    var active: RequestToken? = null
        private set

    fun begin(nowNs: Long) = RequestToken(++serial, nowNs + 20_000_000_000L).also { active = it }
    fun cancel() { active = null }

    enum class Completion { ACCEPT, EXPIRED, OBSOLETE }
    fun finish(token: RequestToken, nowNs: Long): Completion {
        if (token != active) return Completion.OBSOLETE
        active = null
        return if (nowNs >= token.deadlineNs) Completion.EXPIRED else Completion.ACCEPT
    }
}
