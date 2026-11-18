package com.lumi.camera.shotplan

import android.content.res.Resources
import android.os.SystemClock
import com.lumi.camera.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal data class ProbeResult(val result: ConnectionResult, val httpCode: Int?, val elapsedMs: Long) {
    val text get() = result.label + (httpCode?.let { "（HTTP $it）" } ?: "") + " · ${elapsedMs} ms"
}

internal suspend fun testConnection(config: ModelConfig, resources: Resources): ProbeResult {
    val start = SystemClock.elapsedRealtimeNanos()
    val call = VisionChat(config, start + 20_000_000_000L)
    val worker = Dispatchers.IO.asExecutor()
    val response = withTimeoutOrNull(20_000L) {
        suspendCancellableCoroutine<Pair<ConnectionResult, Int?>> { continuation ->
            continuation.invokeOnCancellation { call.cancel(worker) }
            worker.execute {
                val outcome = try {
                    val image = resources.openRawResource(R.raw.model_probe).use { it.readBytes() }
                    ConnectionResult.fromReply(call.send(image)) to null
                } catch (e: Exception) {
                    ConnectionResult.fromFailure(e) to (e as? ModelFailure)?.httpCode
                }
                continuation.resume(outcome)
            }
        }
    } ?: (ConnectionResult.UNREACHABLE to null)
    return ProbeResult(response.first, response.second, (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000)
}
