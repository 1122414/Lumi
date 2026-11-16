package com.lumi.camera.camera

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicReference

internal data class Tap(val id: Long, val screen: Point?)
internal data class FrameInfo(val timestampNs: Long, val geometry: FrameGeometry)
internal data class AnalysisResult(
    val frame: FrameInfo, val tapId: Long, val marker: Point?,
    val failure: PatchFailure?, val matchedFrames: Int,
    val fps: Double, val elapsedMs: Double, val startedAtMs: Long,
)

internal class FrameAnalyzer(
    private val view: Extent,
    private val tap: AtomicReference<Tap>,
    private val deliver: (AnalysisResult) -> Unit,
) : ImageAnalysis.Analyzer {
    private var lastTimestamp = Long.MIN_VALUE
    private var lastGeometry: FrameGeometry? = null
    private var tracker = PatchTracker()
    private var consumedTap = 0L
    private var outside = false
    private var fps = 0.0
    private var windowStart = 0L
    private var intervals = 0

    override fun analyze(image: ImageProxy) {
        val start = SystemClock.elapsedRealtimeNanos()
        try {
            val timestamp = image.imageInfo.timestamp
            if (timestamp <= lastTimestamp) return
            lastTimestamp = timestamp
            val r = image.cropRect
            val geometry = FrameGeometry(Extent(image.width, image.height),
                Crop(r.left, r.top, r.right, r.bottom), image.imageInfo.rotationDegrees)
            if (lastGeometry != geometry) {
                tracker = PatchTracker()
                outside = false
                lastGeometry = geometry
            }
            val mapping = FrameMapping(geometry, view)
            val plane = image.planes[0]
            val y = Luma(plane.buffer, plane.rowStride, plane.pixelStride)
            val selection = tap.get()
            val freshTap = selection.id != consumedTap
            val wasTracking = tracker.matchedFrames > 0 && tracker.failure == null
            val point = if (freshTap) {
                consumedTap = selection.id
                val raw = selection.screen?.let(mapping::previewToAnalysis)
                outside = raw == null
                if (raw != null) tracker.select(y, geometry.crop, raw) else {
                    tracker = PatchTracker()
                    null
                }
            } else tracker.follow(y, geometry.crop)

            val failure = if (outside) PatchFailure.OUTSIDE else tracker.failure
            if (freshTap || (wasTracking && point == null && failure != null)) {
                Log.d("LumiMarker", "tap=${selection.id} confirmedFrames=${tracker.matchedFrames} result=${failure ?: "MATCH"}")
            }

            if (intervals == 0 && windowStart == 0L) windowStart = timestamp else intervals++
            val duration = timestamp - windowStart
            if (duration >= 500_000_000L) {
                fps = intervals * 1e9 / duration
                windowStart = timestamp
                intervals = 0
            }
            deliver(AnalysisResult(FrameInfo(timestamp, geometry), selection.id,
                point?.let(mapping::toPreview), failure, tracker.matchedFrames, fps, (SystemClock.elapsedRealtimeNanos() - start) / 1e6,
                start / 1_000_000L))
        } finally {
            image.close()
        }
    }
}
