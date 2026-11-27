package com.lumi.camera.vision

import com.lumi.camera.guidance.Observation
import com.lumi.camera.guidance.RotationProjection
import com.lumi.camera.guidance.SubjectPosition
import com.lumi.camera.guidance.Uv
import com.lumi.camera.shotplan.SelectedSubject
import kotlin.math.abs
import kotlin.math.hypot

internal data class SeenOutline(val mask: ForegroundMask, val position: SubjectPosition, val complete: Boolean,
    val matchedInterior: Boolean = false) {
    val points get() = mask.points
}
internal typealias SegmentAt = (SubjectPixels, Uv) -> ForegroundMask?

internal data class OutlineAttempt(val outline: SubjectOutline?, val status: String, val usedSeed: Uv? = null)

// The source appearance is immutable. Matching locates a prompt inside the same instance;
// it never supplies vertices for drawing. All blue pixels come from the current mask.
internal class SubjectOutline private constructor(private val source: Observation, private val seed: Uv,
    private val original: ForegroundMask, private val sourceRegion: FaceBox, private val selection: List<Uv>, private val referenceComplete: Boolean, private val samples: List<Sample>, private val segment: SegmentAt, val person: Boolean) {
    private data class Sample(val p: Uv, val color: Int)
    val bounds get() = original.bounds
    val capturePoints: List<Uv?> get() = original.points
    val sourceObservation: Observation get() = source
    val hasFullCaptureContour = referenceComplete && original.points.none { it == null }
    fun containsAtCapture(p: Uv) = original.contains(p)
    private var lastSeed = seed
    private var lastScale = 1.0
    private val distinctive = samples.any { a -> samples.count { colorError(a.color,it.color)>52 } >= samples.size/3 }
    val sourceSubject = position(original, seed)
    private var lastTime = source.timeNs
    private var retrySource = false

    fun observe(current: Observation, image: SubjectPixels): SeenOutline? {
        val recovering = retrySource
        val seen = if (recovering) recover(current,image) else match(current,image)
        retrySource = seen == null && !recovering
        return seen
    }

    private fun match(current: Observation, image: SubjectPixels): SeenOutline? {
        val projection = RotationProjection(source, current)
        val continuous = current.timeNs - lastTime in 0..500_000_000L
        val base = if (continuous) lastSeed else seed
        val predicted = projection.project(base) ?: return null
        val projected = samples.mapNotNull {
            val p = projection.project(Uv(it.p.x + base.x - seed.x, it.p.y + base.y - seed.y))
            p?.let { point -> Sample(point, it.color) }
        }
        if (projected.size < 5) return null
        var bestCost = Double.POSITIVE_INFINITY
        var at = predicted
        var scale = 1.0
        fun locate(reach: Int, step: Double, scales: List<Double>, around: Uv = predicted) {
            for (factor in scales) for (y in -reach..reach) for (x in -reach..reach) {
                val cx = around.x + x*step
                val cy = around.y + y*step
                if (cx !in 0.0..<1.0 || cy !in 0.0..<1.0) continue
                var visible = 0
                var error = 0.0
                for (sample in projected) {
                    val color = image.at(cx+(sample.p.x-predicted.x)*factor, cy+(sample.p.y-predicted.y)*factor) ?: continue
                    visible++
                    error += colorError(color,sample.color).coerceAtMost(95.0)
                }
                if (visible < maxOf(5,projected.size/3)) continue
                val cost = error/visible + 16*hypot(cx-predicted.x,cy-predicted.y) + 6*abs(factor-lastScale) + 4.0*(1-visible.toDouble()/projected.size)
                if (cost < bestCost) { bestCost = cost; at = Uv(cx,cy); scale = factor }
            }
        }
        locate(4, 0.032, listOf(lastScale,1.0,0.80,1.18))
        if (distinctive && bestCost > 19) locate(10,0.042,listOf(0.75,0.9,1.0,1.2))
        if (!bestCost.isFinite()) return null
        if (distinctive) locate(3, 0.007, listOf(scale), at)
        fun shifted(p: Uv) = Uv(at.x+(p.x-predicted.x)*scale, at.y+(p.y-predicted.y)*scale)
        fun project(p: Uv) = projection.project(Uv(p.x+base.x-seed.x,p.y+base.y-seed.y))?.let(::shifted)
        val projectedSelection = selection.mapNotNull(::project)
        if (projectedSelection.size != selection.size) return null
        val box = FaceBox(projectedSelection.minOf { it.x },projectedSelection.minOf { it.y },
            projectedSelection.maxOf { it.x },projectedSelection.maxOf { it.y })
        val raw = within(image, at, box, segment) ?: return null
        // Register measured edges to the same edges at selection time. The coarse model
        // polygon can extend beyond the actual toy even on a completely stationary frame.
        val expected = listOf(Uv(sourceRegion.left,sourceRegion.top),Uv(sourceRegion.right,sourceRegion.top),
            Uv(sourceRegion.right,sourceRegion.bottom),Uv(sourceRegion.left,sourceRegion.bottom)).map { project(it) ?: return null }
        val expectedBox = FaceBox(expected.minOf { it.x },expected.minOf { it.y },expected.maxOf { it.x },expected.maxOf { it.y })
        fun align(v: Double, lo: Double, hi: Double, measuredLo: Double, measuredHi: Double): Double {
            val width = hi-lo
            if (width <= 0) return v
            val start = if (measuredLo > 0 && abs(measuredLo-lo) <= width*0.18) measuredLo else lo
            val end = if (measuredHi < 1 && abs(measuredHi-hi) <= width*0.18) measuredHi else hi
            return start + (v-lo)*(end-start)/width
        }
        fun aligned(p: Uv) = Uv(align(p.x,expectedBox.left,expectedBox.right,raw.bounds.left,raw.bounds.right),
            align(p.y,expectedBox.top,expectedBox.bottom,raw.bounds.top,raw.bounds.bottom))
        val mask = raw.constrained(projectedSelection.map(::aligned),at) ?: return null
        val b = mask.bounds
        val clipped = b.left == 0.0 || b.top == 0.0 || b.right == 1.0 || b.bottom == 1.0
        var visible = 0
        var confirmed = 0
        for (sample in projected) {
            val p = shifted(sample.p)
            val color = image.at(p.x,p.y) ?: continue
            visible++
            if (mask.contains(p) && colorError(color,sample.color) <= 50.0) confirmed++
        }
        // Independently verify the colours of the *current* component against the locked
        // instance. Partial visibility changes which parts of the original can be compared.
        var total = 0
        var same = 0
        for (row in 0 until 8) for (col in 0 until 6) {
            val p = Uv(b.left+(col+0.5)*(b.right-b.left)/6, b.top+(row+0.5)*(b.bottom-b.top)/8)
            if (!mask.contains(p)) continue
            val color = image.at(p.x,p.y) ?: continue
            total++
            if (samples.any { colorError(color,it.color) <= 35.0 }) same++
        }
        if (total < 5 || same < total*0.72 || confirmed < maxOf(4,(visible*0.40).toInt())) return null
        val fraction = mask.area.toDouble() / (mask.width*mask.height)
        val reference = original.area.toDouble() / (original.width*original.height)
        if (fraction/reference !in 0.20..1.85) return null
        val entire = referenceComplete && !clipped && fraction/reference in 0.7..1.45 && confirmed >= samples.size*0.68
        // When interior colours cannot distinguish nearby positions, use the verified extent.
        val next = if (entire && !distinctive) aligned(at) else at
        lastSeed = RotationProjection(current,source).project(next) ?: base
        lastScale = scale
        lastTime = current.timeNs
        return SeenOutline(mask, position(mask, next), entire, matchedInterior = distinctive && mask.contains(next))
    }

    // After a miss the old crop may itself exclude the subject. Re-segment the full view,
    // then verify corresponding interior colours before adopting the newly measured bounds.
    private fun recover(current: Observation, image: SubjectPixels): SeenOutline? {
        val at = RotationProjection(source,current).project(seed) ?: return null
        if (at.x !in 0.0..<1.0 || at.y !in 0.0..<1.0) return null
        val raw = segment(image,at) ?: return null
        val b = raw.bounds
        if (b.left == 0.0 || b.top == 0.0 || b.right == 1.0 || b.bottom == 1.0) return null
        val sx = (b.right-b.left)/(bounds.right-bounds.left)
        val sy = (b.bottom-b.top)/(bounds.bottom-bounds.top)
        if (sx !in 0.55..1.5 || sy !in 0.55..1.5 || sx/sy !in 0.65..1.5) return null
        fun register(p: Uv) = Uv(b.left+(p.x-bounds.left)*sx, b.top+(p.y-bounds.top)*sy)
        val mask = raw.constrained(selection.map(::register),at) ?: return null
        val hits = samples.count {
            val p = register(it.p)
            val color = image.at(p.x,p.y)
            color != null && mask.contains(p) && colorError(color,it.color) <= 50.0
        }
        if (hits < samples.size*0.60) return null
        lastSeed = RotationProjection(current,source).project(register(seed)) ?: return null
        lastScale = (sx+sy)/2
        lastTime = current.timeNs
        return SeenOutline(mask,position(mask, register(seed)),referenceComplete && hits >= samples.size*0.72)
    }

    // A changing silhouette can shift its bounding-box center on a stationary toy.
    private fun position(mask: ForegroundMask, matchedSeed: Uv): SubjectPosition {
        val b = mask.bounds
        val center = if (distinctive) matchedSeed else Uv((b.left+b.right)/2, (b.top+b.bottom)/2)
        return SubjectPosition(0, center, b.right-b.left)
    }

    companion object {
        private fun colorError(a: Int, b: Int): Double = 0.55*abs((a shr 16)-(b shr 16)) +
            abs((a shr 8 and 255)-(b shr 8 and 255)) + abs((a and 255)-(b and 255))

        private fun within(image: SubjectPixels, seed: Uv, box: FaceBox, segment: SegmentAt): ForegroundMask? {
            val padX = 0.15*(box.right-box.left)
            val padY = 0.12*(box.bottom-box.top)
            val left = ((box.left-padX)*image.width).toInt().coerceIn(0,image.width)
            val top = ((box.top-padY)*image.height).toInt().coerceIn(0,image.height)
            val right = kotlin.math.ceil((box.right+padX)*image.width).toInt().coerceIn(0,image.width)
            val bottom = kotlin.math.ceil((box.bottom+padY)*image.height).toInt().coerceIn(0,image.height)
            if (right-left < 5 || bottom-top < 5) return null
            val prompt = Uv((seed.x*image.width-left)/(right-left), (seed.y*image.height-top)/(bottom-top))
            if (prompt.x !in 0.0..<1.0 || prompt.y !in 0.0..<1.0) return null
            val local = segment(image.crop(left,top,right-left,bottom-top), prompt) ?: return null
            return local.inImage(image.width,image.height,left,top,seed)
        }

        fun from(source: Observation, selected: SelectedSubject, image: SubjectPixels, segment: SegmentAt): SubjectOutline? =
            diagnose(source, selected, image, segment).outline

        fun diagnose(
            source: Observation,
            selected: SelectedSubject,
            image: SubjectPixels,
            segment: SegmentAt,
            allowBoxFallback: Boolean = false,
        ): OutlineAttempt {
            val box = selected.bounds
            val center = Uv((box.left+box.right)/2, (box.top+box.bottom)/2)
            val candidates = mutableListOf<Uv>()
            for (row in 0 until 9) for (col in 0 until 7) {
                val p = Uv(box.left+(col+0.5)*(box.right-box.left)/7, box.top+(row+0.5)*(box.bottom-box.top)/9)
                if (selected.contains(p)) candidates.add(p)
            }
            if (candidates.isEmpty()) return OutlineAttempt(null, "NO_INTERIOR_CANDIDATES")
            val primary = selected.seed ?: candidates.minByOrNull { it.distance(center) }
                ?: return OutlineAttempt(null, "NO_SEED")
            val bw = box.right - box.left
            val bh = box.bottom - box.top
            val fallbackPool = listOf(
                candidates.minByOrNull { it.distance(center) },
                Uv(primary.x - 0.14 * bw, primary.y),
                Uv(primary.x + 0.14 * bw, primary.y),
                Uv(primary.x, primary.y - 0.14 * bh),
                Uv(primary.x, primary.y + 0.14 * bh),
            ).filterNotNull().filter { selected.contains(it) }
            val seeds = buildList {
                add(primary)
                for (p in fallbackPool) {
                    if (none { it.distance(p) < 0.025 }) add(p)
                    if (size >= 4) break
                }
            }
            var lastReason = "SEGMENT_EMPTY"
            for ((index, seed) in seeds.withIndex()) {
                val region = within(image, seed, box, segment)
                if (region == null) { lastReason = "SEGMENT_EMPTY"; continue }
                val baseMask = region.constrained(selected.outline, seed, minRetention = 0.35)
                if (baseMask == null) { lastReason = "CONSTRAINED_TOO_SMALL"; continue }
                // Multi-colored objects (e.g. hat+head vs overalls) can split across a collar;
                // if the first click misses a large vertical/horizontal portion of the box, probe once more and merge.
                var mask = baseMask
                val mb = mask.bounds
                if (bh > 0.12 && (mb.bottom - mb.top) < bh * 0.68) {
                    val missedTop = (mb.top - box.top) > (box.bottom - mb.bottom)
                    // For portraits, only probe upward (e.g. missed head/hair); never probe downward into tables or trays.
                    val probeY = when {
                        missedTop -> (box.top + mb.top) / 2.0
                        !selected.person -> (mb.bottom + box.bottom) / 2.0
                        else -> null
                    }
                    val probe = probeY?.let { Uv((box.left + box.right) / 2.0, it) }
                    if (probe != null && selected.contains(probe) && !mask.contains(probe)) {
                        val extraRaw = within(image, probe, box, segment)
                        val extra = extraRaw?.constrained(selected.outline, probe, minRetention = 0.35)
                        if (extra != null) {
                            mask = mask.mergeWith(extra, seed) ?: mask
                        }
                    }
                }
                // A partially occluded instance may still have a traceable visible component.
                // Full-subject eligibility is checked separately against the extent of the selection.
                if (candidates.count(mask::contains) < candidates.size*0.20) { lastReason = "LOW_COVERAGE"; continue }
                val b = mask.bounds
                val overlap = (minOf(b.right,box.right)-maxOf(b.left,box.left)).coerceAtLeast(0.0) *
                    (minOf(b.bottom,box.bottom)-maxOf(b.top,box.top)).coerceAtLeast(0.0)
                if (overlap < (b.right-b.left)*(b.bottom-b.top)*0.35) { lastReason = "LOW_OVERLAP"; continue }
                val samples = mutableListOf<Sample>()
                for (row in 0 until 9) for (col in 0 until 7) {
                    val x = ((b.left+(col+0.5)*(b.right-b.left)/7)*image.width).toInt()
                    val y = ((b.top+(row+0.5)*(b.bottom-b.top)/9)*image.height).toInt()
                    val p = Uv((x+0.5)/image.width, (y+0.5)/image.height)
                    if (mask.contains(p)) image.at(p.x,p.y)?.let { samples.add(Sample(p,it)) }
                }
                if (samples.size < 6) { lastReason = "TOO_FEW_SAMPLES"; continue }
                val complete = b.right-b.left >= 0.78*(box.right-box.left) && b.bottom-b.top >= 0.78*(box.bottom-box.top) &&
                    b.left > 0 && b.top > 0 && b.right < 1 && b.bottom < 1
                val outline = SubjectOutline(source, seed, mask, region.bounds, selected.outline, complete, samples, segment, selected.person)
                return OutlineAttempt(outline, if (index == 0) "OK" else "OK_FALLBACK_SEED_$index", seed)
            }
            if (allowBoxFallback) {
                boxFallback(source, selected, image, segment)?.let { return it }
            }
            return OutlineAttempt(null, lastReason)
        }

        fun instantBox(
            source: Observation,
            selected: SelectedSubject,
            image: SubjectPixels,
        ): SubjectOutline? = boxFallback(source, selected, image) { _, _ -> null }?.outline

        private fun boxFallback(
            source: Observation,
            selected: SelectedSubject,
            image: SubjectPixels,
            segment: SegmentAt,
        ): OutlineAttempt? {
            val b = selected.bounds
            val left = (b.left * image.width).toInt().coerceIn(0, image.width - 1)
            val top = (b.top * image.height).toInt().coerceIn(0, image.height - 1)
            val right = kotlin.math.ceil(b.right * image.width).toInt().coerceIn(left + 1, image.width)
            val bottom = kotlin.math.ceil(b.bottom * image.height).toInt().coerceIn(top + 1, image.height)
            if (right - left < 4 || bottom - top < 4) return null
            val bytes = ByteArray(image.width * image.height) { -1 }
            for (y in top until bottom) {
                bytes.fill(0, y * image.width + left, y * image.width + right)
            }
            val seed = selected.seed?.takeIf {
                it.x in b.left..<b.right && it.y in b.top..<b.bottom
            } ?: Uv((b.left + b.right) / 2.0, (b.top + b.bottom) / 2.0)
            val mask = ForegroundMask.at(image.width, image.height, bytes, seed) ?: return null
            val samples = mutableListOf<Sample>()
            for (row in 0 until 9) for (col in 0 until 7) {
                val x = ((b.left + (col + 0.5) * (b.right - b.left) / 7) * image.width).toInt()
                val y = ((b.top + (row + 0.5) * (b.bottom - b.top) / 9) * image.height).toInt()
                val p = Uv((x + 0.5) / image.width, (y + 0.5) / image.height)
                if (mask.contains(p)) image.at(p.x, p.y)?.let { samples.add(Sample(p, it)) }
            }
            if (samples.size < 6) return null
            val outline = SubjectOutline(source, seed, mask, b, selected.outline, referenceComplete = false, samples, segment, selected.person)
            return OutlineAttempt(outline, "OK_BOX_FALLBACK", seed)
        }
    }
}
