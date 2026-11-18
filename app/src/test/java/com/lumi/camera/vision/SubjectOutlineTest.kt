package com.lumi.camera.vision

import com.lumi.camera.camera.Crop
import com.lumi.camera.camera.Extent
import com.lumi.camera.camera.FrameGeometry
import com.lumi.camera.guidance.*
import com.lumi.camera.shotplan.Recommendation
import com.lumi.camera.shotplan.RememberedComposition
import com.lumi.camera.shotplan.SelectedSubject
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class SubjectOutlineTest {
    // Two ears and a deep notch make a rectangle or a convex hull visibly wrong.
    private val vertices = listOf(listOf(250,250), listOf(285,225), listOf(335,340), listOf(400,325),
        listOf(450,220), listOf(475,260), listOf(465,480), listOf(510,580), listOf(465,685),
        listOf(275,685), listOf(220,560), listOf(255,470))
    private val fields = mapOf("name" to "兔子玩偶", "kind" to "object", "outline" to vertices)
    private val toy = SelectedSubject.parse(fields)!!
    private val source = Observation(0, Pose(1.0,0.0,0.0,0.0), Lens(1.1,0.85,0.5,0.5), null)

    private fun image(dx: Double = 0.0, dy: Double = 0.0, pose: Observation = source,
        covered: Boolean = false, visibleAbove: Double = 1.0): SubjectPixels {
        val inverse = RotationProjection(pose, source)
        val data = IntArray(120 * 160) { i ->
            val p = inverse.project(Uv((i%120 + 0.5)/120, (i/120 + 0.5)/160))
            val isToy = p != null && !covered && p.y < visibleAbove && toy.contains(Uv(p.x-dx, p.y-dy))
            val neighbour = p != null && toy.contains(Uv(p.x+0.32, p.y)) // Another instance on the left.
            if (isToy || neighbour) 0xdc6c99 else 0x668477
        }
        return SubjectPixels(120,160,data)
    }

    private val segment: SegmentAt = { image, seed ->
        val values = ByteArray(image.width*image.height) { i ->
            val color = image.at((i%image.width+0.5)/image.width, (i/image.width+0.5)/image.height)
            if (color == 0xdc6c99) 0 else 255.toByte()
        }
        ForegroundMask.at(image.width,image.height,values,seed)
    }

    @Test fun contourFollowsCameraRotationAndImageMotionAndKeepsTheNotchBetweenEars() {
        val outline = SubjectOutline.from(source, toy, image(), segment)!!
        for (i in 0..8) {
            val halfAngle = Math.toRadians(i * 0.7) / 2
            val now = source.copy(timeNs = (i+1)*40_000_000L, pose = Pose(cos(halfAngle),0.0,-sin(halfAngle),0.0))
            val dx = i * 0.012
            val dy = i * 0.004
            val seen = outline.observe(now, image(dx, dy, now))!!
            val observed = seen.points.filterNotNull()
            val projection = RotationProjection(source, now)
            val notch = projection.project(Uv(0.355 + dx, 0.335 + dy))!!
            val tip = projection.project(Uv(0.453 + dx, 0.232 + dy))!!
            assertTrue("notch at frame $i", observed.minOf { it.distance(notch) } < 0.034)
            assertTrue("ear at frame $i", observed.minOf { it.distance(tip) } < 0.034)
            val center = projection.project(Uv(0.365 + dx, 0.453 + dy))!!
            assertEquals(center.x, seen.position.center.x, 0.035)
        }
    }

    @Test fun hidingTheSelectedToyDoesNotTransferItsOutlineToTheNeighbour() {
        val outline = SubjectOutline.from(source, toy, image(), segment)!!
        val covered = source.copy(timeNs = 100_000_000L)
        assertNull(outline.observe(covered, image(covered = true)))
        val away = source.copy(timeNs = 200_000_000L, pose = Pose(0.5,0.0,0.8660254038,0.0))
        assertNull(outline.observe(away, image(pose = away)))
        val back = source.copy(timeNs = 800_000_000L)
        assertTrue(outline.observe(back, image())!!.complete)
        assertNull(SubjectOutline.from(source, toy, SubjectPixels(120,160,IntArray(120*160)), segment))
    }

    @Test fun nonHumanRecommendationCanReachReadyWithoutAnyFaceAndRejectsAnotherTargetsOutline() {
        val plan = Recommendation.validated(mapOf("aimPoint" to mapOf("x" to 0.5, "y" to 0.5),
            "confidence" to 0.9, "alreadyGood" to true, "reason" to "兔子玩偶耳边留白", "subject" to fields))!!
        val geometry = FrameGeometry(Extent(120,160), Crop(0,0,120,160), 0)
        val background = Background(IntArray(40*52))
        val pixels = image()
        val frame = SceneFrame(1,0,geometry,source,null,background,pixels)
        val target = RememberedComposition.from(frame, plan, SubjectOutline.from(source, toy, pixels, segment))!!
        val follower = target.outline!!
        val guidance = RotationGuidance(target.observation)
        var ready = false
        for (i in 1..10) {
            val now = source.copy(timeNs = i * 50_000_000L)
            val seen = follower.observe(now, pixels)
            val current = target.current(frame.copy(observation = now, follower = follower, outline = seen))!!
            ready = guidance.update(current, now.timeNs, true).ready
        }
        assertTrue(ready)
        val obsolete = SubjectOutline.from(source,toy,pixels,segment)
        assertNull(target.current(frame.copy(follower = obsolete, outline = follower.observe(source,pixels)))!!.subject)
    }

    @Test fun visiblePartsOfTheSilhouetteCanGuideWithoutDeclaringTheWholeSubjectReady() {
        val pixels = image(visibleAbove = 0.50)
        val frame = SceneFrame(1,0,FrameGeometry(Extent(120,160), Crop(0,0,120,160), 0), source,
            null, Background(IntArray(40*52)), pixels)
        val plan = Recommendation.validated(mapOf("aimPoint" to mapOf("x" to 0.55, "y" to 0.5),
            "confidence" to 0.86, "alreadyGood" to false, "reason" to "玩偶耳边留白", "subject" to fields))!!
        val target = RememberedComposition.from(frame, plan, SubjectOutline.from(source, toy, pixels, segment))!!
        val seen = target.outline!!.observe(source, pixels)!!
        assertTrue(seen.points.any { it != null })
        assertFalse(seen.complete)
        val current = target.current(frame.copy(follower = target.outline, outline = seen))!!
        val guidance = RotationGuidance(target.observation, verifyScene=true)
        for (i in 1..10) {
            val next = current.copy(timeNs = i*50_000_000L)
            val guide = guidance.update(next,next.timeNs,true)
            assertNotNull(guide.ring)
            assertFalse(guide.ready)
        }
    }

    @Test fun theSelectedComponentExcludesSeparateBackgroundObjectsAndInteriorHoles() {
        val w = 100
        val mask = ByteArray(w*w) { i ->
            val x=i%w
            val y=i/w
            val toy = x in 10..49 && y in 20..85 && !(x in 20..30 && y in 30..48)
            val mirror = x in 60..89 && y in 10..45
            if (toy || mirror) 0 else 255.toByte()
        }
        val shape = ForegroundMask.at(w,w,mask,Uv(0.37,0.5))!!
        assertFalse(shape.contains(Uv(0.75,0.2)))
        assertFalse(shape.contains(Uv(0.25,0.38)))
        val path = shape.points.filterNotNull()
        assertTrue(path.all { it.x==0.10 || it.x==0.50 || it.y==0.20 || it.y==0.86 })
        assertTrue((path + path.first()).zipWithNext().all { (a,b) -> a.distance(b) < 0.015 })
    }

    @Test fun aMirrorJoinedToTheHatDoesNotIntroduceAFakeClosingLine() {
        val w = 100
        val data = ByteArray(w*w) { i ->
            val x = i%w
            val y = i/w
            if ((x in 20..59 && y in 36..84) || (x in 33..45 && y in 8..46)) 0 else -1
        }
        val seed = Uv(0.4,0.56)
        val raw = ForegroundMask.at(w,w,data,seed)!!
        val selected = raw.constrained(listOf(Uv(.2,.36),Uv(.6,.36),Uv(.6,.85),Uv(.2,.85)),seed)!!
        assertTrue(selected.points.any { it == null })
        assertTrue(selected.points.filterNotNull().all { it.y >= 0.33 })
        assertFalse(selected.contains(Uv(.40,.17)))
    }

    @Test fun plainBackgroundStillHasToMatchWhenTexturesAreAbsent() {
        val wall = Background(IntArray(40*52) { 170 })
        val marks = wall.record(toy.bounds, wholeSubject=true)
        val samePose = RotationProjection(source,source)
        assertTrue(wall.agrees(marks,samePose))
        assertFalse(Background(IntArray(40*52) { 45 }).agrees(marks,samePose))
        assertFalse(wall.agrees(emptyList(),samePose))
    }

    @Test fun malformedAndSelfCrossingSubjectPolygonsAreRejected() {
        for (bad in listOf(fields - "name", fields + ("kind" to "unknown"), fields + ("outline" to vertices.take(4)),
            fields + ("outline" to vertices + listOf(listOf(-1,500))),
            fields + ("outline" to vertices.toMutableList().apply { set(1, vertices[8]); set(8, vertices[1]) }))) {
            assertNull(SelectedSubject.parse(bad))
        }
    }
}
