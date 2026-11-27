package com.lumi.camera.camera

import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal data class CaptureQueueState(
    val pendingCount: Int = 0,
) {
    val isProcessing: Boolean get() = pendingCount > 0
}

// Bounded asynchronous post-capture processing pipeline.
// Keeps the shutter button immediately responsive while heavy crop / offscreen GL filter / EXIF /
// MediaStore writes finish off the camera analysis thread without OOM spikes.
internal class CapturePipeline(
    private val maxPending: Int = 4,
    private val callbackExecutor: Executor,
    private val processorExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LumiCapturePipeline").apply { isDaemon = true }
    },
    private val onStateChanged: (CaptureQueueState) -> Unit = {},
) {
    private val pending = AtomicInteger(0)
    @Volatile private var closed = false

    val pendingCount: Int get() = pending.get()

    fun <T> submit(
        work: () -> T?,
        onComplete: (T?) -> Unit,
    ): Boolean {
        if (closed) return false
        while (true) {
            val cur = pending.get()
            if (cur >= maxPending) return false
            if (pending.compareAndSet(cur, cur + 1)) {
                publish(cur + 1)
                break
            }
        }
        return try {
            processorExecutor.execute {
                val result = try {
                    work()
                } catch (_: Throwable) {
                    null
                } finally {
                    val remaining = pending.decrementAndGet().coerceAtLeast(0)
                    publish(remaining)
                }
                if (!closed) {
                    callbackExecutor.execute {
                        if (!closed) onComplete(result)
                    }
                }
            }
            true
        } catch (_: RuntimeException) {
            val remaining = pending.decrementAndGet().coerceAtLeast(0)
            publish(remaining)
            false
        }
    }

    fun close() {
        closed = true
        processorExecutor.shutdown()
    }

    private fun publish(count: Int) {
        if (closed) return
        callbackExecutor.execute {
            if (!closed) onStateChanged(CaptureQueueState(pendingCount = count))
        }
    }
}
