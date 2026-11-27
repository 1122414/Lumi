package com.lumi.camera.vision

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.lumi.camera.camera.CameraOptics
import com.lumi.camera.camera.CameraPose
import com.lumi.camera.camera.Crop
import com.lumi.camera.camera.Extent
import com.lumi.camera.camera.FrameGeometry
import com.lumi.camera.camera.LumaStats
import com.lumi.camera.camera.Point
import com.lumi.camera.guidance.Observation
import com.lumi.camera.guidance.ParallaxStepTracker
import com.lumi.camera.guidance.RotationGuidance
import com.lumi.camera.guidance.StepProgress
import com.lumi.camera.guidance.SubjectPosition
import com.lumi.camera.guidance.Uv
import com.lumi.camera.shotplan.PendingRecommendationFrame
import com.lumi.camera.shotplan.PhotoQualityEvaluator
import com.lumi.camera.shotplan.RequestToken
import com.lumi.camera.shotplan.SelectedSubject
import com.lumi.camera.shotplan.SubjectCandidate
import com.lumi.camera.shotplan.SubjectCandidates

internal data class FaceBox(val left: Double, val top: Double, val right: Double, val bottom: Double)
internal data class TrackedFace(val id: Int?, val box: FaceBox)
internal data class SceneFrame(
    val sequence: Long,
    val capturedNs: Long,
    val geometry: FrameGeometry,
    val observation: Observation?,
    val box: FaceBox?,
    val background: Background?,
    val pixels: SubjectPixels? = null,
    val follower: SubjectOutline? = null,
    val outline: SeenOutline? = null,
    val shift: TrackedShift? = null,
    val faces: List<FaceBox> = emptyList(),
    val meanLuma: Int? = null,
    val trackedFaces: List<TrackedFace> = emptyList(),
    val stepProgress: StepProgress? = null,
    val backgroundClutter: Double? = null,
    val lumaStats: LumaStats? = null,
)

internal class CompositionAnalyzer(
    context: Context,
    private val optics: CameraOptics,
    private val poses: CameraPose,
    private val mirrored: Boolean = false,
    private val modelFrameReady: (RequestToken, RecommendationFrame) -> Unit,
    private val deliver: (SceneFrame) -> Unit,
) : ImageAnalysis.Analyzer, AutoCloseable {
    private val segmenter = SubjectSegmenter(context.applicationContext)
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .enableTracking()
            .build()
    )
    private val objectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableMultipleObjects()
            .build()
    )
    private var reportedFailure = false
    val pendingFrame = PendingRecommendationFrame()

    @Volatile var watchPerson = false
    @Volatile var proactiveMonitor = false
    @Volatile var filterPreviewEnabled = false
    @Volatile var latestFilterPreview: SubjectPixels? = null
    @Volatile var tracker: SubjectTracker? = null
    @Volatile var stepTracker: ParallaxStepTracker? = null
    @Volatile var expectedZoom = 1f
    private var lastProactiveFaceCheckNs = 0L
    private var lastFilterPreviewNs = 0L

    fun prepareSelection() = segmenter.prepare()

    fun selectDiagnosed(source: SceneFrame, subject: SelectedSubject, allowBoxFallback: Boolean = false): OutlineAttempt {
        val observation = source.observation ?: return OutlineAttempt(null, "NO_OBSERVATION")
        val pixels = source.pixels ?: return OutlineAttempt(null, "NO_PIXELS")
        return SubjectOutline.diagnose(observation, subject, pixels, segmenter::segment, allowBoxFallback)
    }

    private fun rectToFaceBox(bounds: Rect, frame: FrameGeometry): FaceBox? {
        val size = frame.referenceSize
        val a = frame.uprightToReference(Point(bounds.left.toFloat(), bounds.top.toFloat()))
        val b = frame.uprightToReference(Point(bounds.right.toFloat(), bounds.bottom.toFloat()))
        val box = FaceBox(
            left = (minOf(a.x, b.x).toDouble() / size.width).coerceIn(0.0, 1.0),
            top = (minOf(a.y, b.y).toDouble() / size.height).coerceIn(0.0, 1.0),
            right = (maxOf(a.x, b.x).toDouble() / size.width).coerceIn(0.0, 1.0),
            bottom = (maxOf(a.y, b.y).toDouble() / size.height).coerceIn(0.0, 1.0),
        )
        return box.takeIf { it.right > it.left && it.bottom > it.top }
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        try {
            val ts = image.imageInfo.timestamp
            val captured = optics.captureTimeNs(ts)
            val request = pendingFrame.forCapture(captured)
            val r = image.cropRect
            val frame = FrameGeometry(
                Extent(image.width, image.height),
                Crop(r.left, r.top, r.right, r.bottom),
                image.imageInfo.rotationDegrees,
                mirrored,
            )
            val empty = SceneFrame(ts, captured, frame, null, null, null)
            var candidates: List<SubjectCandidate> = emptyList()
            val result = try {
                val pose = poses.at(captured)
                val lens = optics.lens(image, frame, expectedZoom)
                val media = image.image
                if (media == null || pose == null || lens == null ||
                    SystemClock.elapsedRealtimeNanos() - captured !in 0..RotationGuidance.FRESH_NS) empty else {
                    // The single CameraX analysis worker owns the proxy until ML Kit finishes.
                    val activeTracker = tracker
                    val runProactiveFace = proactiveMonitor && (captured - lastProactiveFaceCheckNs >= PROACTIVE_FACE_INTERVAL_NS)
                    if (runProactiveFace) lastProactiveFaceCheckNs = captured
                    val input = if (request != null || watchPerson || runProactiveFace) InputImage.fromMediaImage(media, frame.rotation) else null
                    val detection = input?.let { detector.process(it) }
                    val objectDetection = if (request != null && input != null) objectDetector.process(input) else null
                    val runFilterPreview = filterPreviewEnabled &&
                        (captured - lastFilterPreviewNs >= FILTER_PREVIEW_INTERVAL_NS)
                    val pixels = try {
                        when {
                            request != null -> SubjectPixels.read(image, frame, targetHeight = 256)
                            activeTracker != null -> SubjectPixels.read(image, frame, targetHeight = 128)
                            runFilterPreview -> SubjectPixels.read(image, frame, targetHeight = 96)
                            else -> null
                        }
                    } finally {
                        // ML Kit owns the media image until its tasks complete, including on a pixel-read failure.
                        if (detection != null) runCatching { Tasks.await(detection) }
                        if (objectDetection != null) runCatching { Tasks.await(objectDetection) }
                    }
                    if (runFilterPreview && pixels != null) {
                        lastFilterPreviewNs = captured
                        latestFilterPreview = pixels
                    }
                    val rawFaces = detection?.takeIf { it.isSuccessful }?.result.orEmpty()
                    val faceEntries = rawFaces.mapNotNull { f ->
                        rectToFaceBox(f.boundingBox, frame)?.let { b -> f.trackingId to b }
                    }
                    val faceBoxes = faceEntries.map { it.second }
                    val primaryBox = when {
                        faceBoxes.size == 1 -> faceBoxes.first()
                        faceBoxes.size >= 2 -> SubjectCandidates.unionOf(faceBoxes)
                        else -> null
                    }
                    val lumaStats = sampleLumaStats(image, frame, primaryBox)
                    val meanLuma = lumaStats?.meanLuma
                    val detected = when {
                        faceEntries.size == 1 -> {
                            val (id, b) = faceEntries.first()
                            val center = Uv((b.left + b.right) / 2.0, (b.top + b.bottom) / 2.0)
                            if (id != null && center.x in 0.0..1.0 && center.y in 0.0..1.0) {
                                SubjectPosition(id, center, b.right - b.left)
                            } else null
                        }
                        faceBoxes.size >= 2 && primaryBox != null -> {
                            val center = Uv((primaryBox.left + primaryBox.right) / 2.0, (primaryBox.top + primaryBox.bottom) / 2.0)
                            if (center.x in 0.0..1.0 && center.y in 0.0..1.0) {
                                SubjectPosition(0, center, primaryBox.right - primaryBox.left)
                            } else null
                        }
                        else -> null
                    }
                    if (request != null) {
                        val objectBoxes = objectDetection?.takeIf { it.isSuccessful }?.result.orEmpty()
                            .mapNotNull { obj -> rectToFaceBox(obj.boundingBox, frame) }
                        candidates = SubjectCandidates.build(faceBoxes, objectBoxes, request.tapUv)
                    }
                    val observation = Observation(captured, pose, lens, detected)
                    val shift = if (activeTracker != null && pixels != null) activeTracker.track(observation, pixels) else null
                    val activeStep = stepTracker
                    val stepProgress = if (activeStep != null) activeStep.update(observation, pixels, shift) else null
                    val clutter = if (pixels != null) PhotoQualityEvaluator.estimateBackgroundClutter(pixels, primaryBox) else null
                    SceneFrame(
                        sequence = ts,
                        capturedNs = captured,
                        geometry = frame,
                        observation = observation,
                        box = primaryBox,
                        background = null,
                        pixels = if (request != null) pixels else null,
                        shift = shift,
                        faces = faceBoxes,
                        meanLuma = meanLuma,
                        trackedFaces = faceEntries.map { (id, b) -> TrackedFace(id, b) },
                        stepProgress = stepProgress,
                        backgroundClutter = clutter,
                        lumaStats = lumaStats,
                    )
                }
            } catch (e: Exception) {
                if (!reportedFailure) Log.w("LumiCamera", "Composition observation unavailable", e)
                reportedFailure = true
                empty
            }
            deliver(result)
            if (request != null && result.pixels != null) {
                val bitmap = try { image.toBitmap() } catch (_: RuntimeException) { return }
                if (pendingFrame.claim(request, captured, SystemClock.elapsedRealtimeNanos())) {
                    modelFrameReady(request, RecommendationFrame(result, bitmap, candidates, request.tapUv))
                } else bitmap.recycle()
            }
        } finally {
            image.close()
        }
    }

    private fun sampleLumaStats(image: ImageProxy, frame: FrameGeometry, subjectBox: FaceBox?): LumaStats? {
        val yPlane = image.planes.firstOrNull() ?: return null
        val buf = yPlane.buffer
        val start = buf.position()
        val ref = frame.referenceSize
        val centerBox = subjectBox ?: FaceBox(0.32, 0.28, 0.68, 0.72)
        var sum = 0
        var count = 0
        var highlights = 0
        var shadows = 0
        var subjSum = 0
        var subjCount = 0
        var bgSum = 0
        var bgCount = 0
        var leftSum = 0; var leftCount = 0
        var rightSum = 0; var rightCount = 0
        var topSum = 0; var topCount = 0
        var bottomSum = 0; var bottomCount = 0
        var brightWeightSum = 0.0
        var brightX = 0.0
        var brightY = 0.0

        fun readY(nx: Double, ny: Double): Int? {
            val px = (nx.coerceIn(0.01, 0.99) * ref.width).toFloat()
            val py = (ny.coerceIn(0.01, 0.99) * ref.height).toFloat()
            val raw = frame.referenceToAnalysis(px, py)
            val rx = raw.x.toInt().coerceIn(0, image.width - 1)
            val ry = raw.y.toInt().coerceIn(0, image.height - 1)
            val idx = start + ry * yPlane.rowStride + rx * yPlane.pixelStride
            return if (idx in 0 until buf.limit()) buf.get(idx).toInt() and 0xFF else null
        }

        for (row in 1..6) {
            val ny = row / 7.0
            for (col in 1..6) {
                val nx = col / 7.0
                val y = readY(nx, ny) ?: continue
                sum += y
                count++
                if (y >= 242) highlights++
                if (y <= 24) shadows++
                if (col <= 2) { leftSum += y; leftCount++ }
                if (col >= 5) { rightSum += y; rightCount++ }
                if (row <= 2) { topSum += y; topCount++ }
                if (row >= 5) { bottomSum += y; bottomCount++ }
                if (y >= 175) {
                    val w = (y - 165).toDouble()
                    brightWeightSum += w
                    brightX += nx * w
                    brightY += ny * w
                }
                if (nx in centerBox.left..centerBox.right && ny in centerBox.top..centerBox.bottom) {
                    subjSum += y
                    subjCount++
                } else {
                    bgSum += y
                    bgCount++
                }
            }
        }
        if (count == 0) return null
        val meanLuma = sum / count
        val diff = if (subjCount >= 2 && bgCount >= 4) (subjSum / subjCount) - (bgSum / bgCount) else null
        val highlightSpot = if (brightWeightSum > 0.0) {
            com.lumi.camera.guidance.Uv(
                (brightX / brightWeightSum).coerceIn(0.08, 0.92),
                (brightY / brightWeightSum).coerceIn(0.08, 0.92),
            )
        } else null

        var sLeft: Int? = null
        var sRight: Int? = null
        var sTop: Int? = null
        var sBottom: Int? = null
        if (subjectBox != null) {
            val cx = (subjectBox.left + subjectBox.right) * 0.5
            val cy = (subjectBox.top + subjectBox.bottom) * 0.5
            val sw = (subjectBox.right - subjectBox.left).coerceAtLeast(0.04)
            val sh = (subjectBox.bottom - subjectBox.top).coerceAtLeast(0.04)
            sLeft = readY(cx - sw * 0.26, cy)
            sRight = readY(cx + sw * 0.26, cy)
            sTop = readY(cx, cy - sh * 0.26)
            sBottom = readY(cx, cy + sh * 0.26)
        }

        val lightGuidance = LightFinder.analyze(
            LightSample(
                meanLuma = meanLuma,
                leftMean = if (leftCount > 0) leftSum / leftCount else meanLuma,
                rightMean = if (rightCount > 0) rightSum / rightCount else meanLuma,
                topMean = if (topCount > 0) topSum / topCount else meanLuma,
                bottomMean = if (bottomCount > 0) bottomSum / bottomCount else meanLuma,
                subjectVsBgDiff = diff,
                subjectLeftMean = sLeft,
                subjectRightMean = sRight,
                subjectTopMean = sTop,
                subjectBottomMean = sBottom,
                highlightSpot = highlightSpot,
                hasFace = subjectBox != null,
            ),
        )
        return LumaStats(
            meanLuma = meanLuma,
            highlightClipPct = highlights * 100 / count,
            shadowClipPct = shadows * 100 / count,
            subjectVsBgDiff = diff,
            lightGuidance = lightGuidance,
        )
    }

    override fun close() {
        pendingFrame.await(null)
        detector.close()
        objectDetector.close()
        segmenter.close()
    }

    companion object {
        private const val PROACTIVE_FACE_INTERVAL_NS = 350_000_000L
        private const val FILTER_PREVIEW_INTERVAL_NS = 360_000_000L
    }
}
