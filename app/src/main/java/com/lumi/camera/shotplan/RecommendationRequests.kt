package com.lumi.camera.shotplan

import com.lumi.camera.guidance.RotationGuidance
import java.util.concurrent.atomic.AtomicReference

internal data class RequestToken(val id: Long, val startedNs: Long, val deadlineNs: Long)

internal class PendingRecommendationFrame {
    private val waiting = AtomicReference<RequestToken?>()
    fun await(token: RequestToken?) = waiting.set(token)
    fun forCapture(capturedNs: Long) = waiting.get()?.takeIf { capturedNs in it.startedNs until it.deadlineNs }
    fun claim(token: RequestToken, capturedNs: Long, nowNs: Long): Boolean =
        capturedNs >= token.startedNs && nowNs < token.deadlineNs &&
            nowNs - capturedNs in 0..RotationGuidance.FRESH_NS && waiting.compareAndSet(token, null)
}

// Owned by the camera session's main thread. A completed or cancelled token is single-use.
internal class RecommendationRequests {
    private var serial = 0L
    var active: RequestToken? = null
        private set

    fun begin(nowNs: Long) = RequestToken(++serial, nowNs, nowNs + 20_000_000_000L).also { active = it }
    fun cancel() { active = null }

    enum class Completion { ACCEPT, EXPIRED, OBSOLETE }
    fun finish(token: RequestToken, nowNs: Long): Completion {
        if (token != active) return Completion.OBSOLETE
        active = null
        return if (nowNs >= token.deadlineNs) Completion.EXPIRED else Completion.ACCEPT
    }
}
