package com.lumi.camera.vision

import com.lumi.camera.camera.Crop
import com.lumi.camera.camera.Extent
import com.lumi.camera.camera.FrameGeometry
import com.lumi.camera.guidance.*
import com.lumi.camera.shotplan.ChatProtocol
import com.lumi.camera.shotplan.CompositionChoice
import com.lumi.camera.shotplan.CompositionPreset
import com.lumi.camera.shotplan.PlanProblem
import com.lumi.camera.shotplan.PlanResult
import com.lumi.camera.shotplan.PresetSelection
import com.lumi.camera.shotplan.validatedPlan
import com.lumi.camera.shotplan.RememberedComposition
import com.lumi.camera.shotplan.SelectedSubject
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class SubjectOutlineTest {
    @Test fun compactSelectionRequiresATightBoxAndAnInteriorPointAndStillDrawsAMeasuredContour() {
        val compact = mapOf("name" to "玩偶", "kind" to "object", "box" to listOf(220,220,510,685),
            "point" to listOf(365,480))
        val selected = SelectedSubject.parse(compact)!!
        assertEquals(Uv(.365,.48), selected.seed)
        val pixels = image()
        val lock = SubjectOutline.from(source, selected, pixels, segment)!!
        val seen = lock.observe(source.copy(timeNs = 40_000_000L), pixels)!!
        assertTrue(seen.points.filterNotNull().size > 8)
        assertFalse(seen.mask.contains(Uv(.23,.23)))
        for (bad in listOf(compact - "point", compact + ("point" to listOf(220,400)),
            compact + ("box" to listOf(510,220,220,685)), compact + ("box" to listOf(220,220,1100,685)))) {
            assertNull(SelectedSubject.parse(bad))
        }
    }
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

    @Test fun aCoarseSelectionDoesNotMoveColourSamplesOnAStationaryTexturedToy() {
        val coarseOutline = listOf(listOf(160,210), listOf(500,210), listOf(540,280),
            listOf(540,730), listOf(160,730), listOf(120,670))
        val selection = SelectedSubject.parse(fields + ("outline" to coarseOutline))!!
        val w = 120
        val h = 160
        val wall = 0x668477
        val pixels = SubjectPixels(w,h,IntArray(w*h) { i ->
            val x = i % w
            val y = i / w
            if (x !in 20..61 || y !in 40..111) wall else {
                val level = (x*31 + y*47) % 164 + 48
                (level shl 16) or ((255-level) shl 8) or level
            }
        })
        var omitRightEdge = false
        val foreground: SegmentAt = { image, seed ->
            val mask = ByteArray(image.width*image.height) { j ->
                val x = (j % image.width + 0.5) / image.width
                val y = (j / image.width + 0.5) / image.height
                if (image.at(x, y) == wall || (omitRightEdge && x > 0.68)) -1 else 0
            }
            ForegroundMask.at(image.width,image.height,mask,seed)
        }
        val outline = SubjectOutline.from(source, selection, pixels, foreground)!!
        repeat(8) { index ->
            val now = source.copy(timeNs = (index + 1) * 70_000_000L)
            assertEquals("same toy at frame $index", true, outline.observe(now, pixels)?.complete)
        }
        omitRightEdge = true
        val seen = outline.observe(source.copy(timeNs = 630_000_000L), pixels)!!
        assertTrue(seen.mask.bounds.right < outline.bounds.right - 0.02)
        assertTrue("source=${outline.sourceSubject.center} measured=${seen.position.center} interior=${seen.matchedInterior}",
            seen.position.center.distance(outline.sourceSubject.center) < 0.005)
    }

    @Test fun nonHumanRecommendationCanReachReadyWithoutAnyFaceAndRejectsAnotherTargetsOutline() {
        val plan = validatedPlan(mapOf("aimPoint" to mapOf("x" to 0.5, "y" to 0.5),
            "confidence" to 0.9, "alreadyGood" to true, "reason" to "兔子玩偶耳边留白", "subject" to fields))!!
        val geometry = FrameGeometry(Extent(120,160), Crop(0,0,120,160), 0)
        val background = Background(IntArray(40*52))
        val pixels = image()
        val frame = SceneFrame(1,0,geometry,source,null,background,pixels)
        val target = (RememberedComposition.recommended(frame, plan, SubjectOutline.from(source, toy, pixels, segment)) as PlanResult.Accepted).value
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

    @Test fun aTrackedToyKeepsItsRecommendedAimWhileTheBackgroundIsBeingChecked() {
        val pixels = image()
        val geometry = FrameGeometry(Extent(120,160), Crop(0,0,120,160), 0)
        val wall = Background(IntArray(40*52) { 160 })
        val changedWall = Background(IntArray(40*52) { 45 })
        val frame = SceneFrame(1,0,geometry,source,null,wall,pixels)
        val aim = Uv(0.58, 0.45)
        val plan = validatedPlan(mapOf("aimPoint" to mapOf("x" to aim.x, "y" to aim.y),
            "confidence" to 0.9, "alreadyGood" to false, "reason" to "玩偶耳边留白", "subject" to fields))!!
        val follower = SubjectOutline.from(source, toy, pixels, segment)!!
        val target = (RememberedComposition.recommended(frame, plan, follower) as PlanResult.Accepted).value
        val guidance = RotationGuidance(target.observation, verifyScene = true)
        val b = follower.bounds
        val direction = ReturnDirection(target.observation, source, listOf(Uv(b.left,b.top),
            Uv(b.right,b.top), Uv(b.right,b.bottom), Uv(b.left,b.bottom)))
        for (i in 1..22) {
            val atGoal = i >= 3
            val pose = if (atGoal) target.observation.pose else source.pose
            val capture = source.copy(timeNs = i*50_000_000L, pose = pose)
            val seen = follower.observe(capture, image(pose = capture))
            assertTrue("blue contour at frame $i", seen?.points?.any { it != null } == true)
            val current = target.current(frame.copy(observation = capture, follower = follower, outline = seen))!!
            val background = if (i <= 12) changedWall else wall
            val agrees = background.agrees(target.marks, RotationProjection(source, current))
            val visual = guidance.update(current, current.timeNs, agrees)
            val shown = direction.display(visual, current, pose, subjectVisible = true)
            val ring = requireNotNull(shown.ring) { "visible toy without a ring at frame $i" }
            val expected = if (atGoal) Uv.CENTER else aim
            assertEquals(expected.x, ring.x, 0.02)
            assertEquals(expected.y, ring.y, 0.02)
            if (i <= 12) {
                assertFalse(agrees)
                assertFalse(shown.trusted)
                assertFalse(shown.merged)
            }
            assertEquals("fresh hold at frame $i", i >= 20, shown.ready)
        }
    }

    @Test fun visiblePartsOfTheSilhouetteCanGuideWithoutDeclaringTheWholeSubjectReady() {
        val pixels = image(visibleAbove = 0.50)
        val frame = SceneFrame(1,0,FrameGeometry(Extent(120,160), Crop(0,0,120,160), 0), source,
            null, Background(IntArray(40*52)), pixels)
        val plan = validatedPlan(mapOf("aimPoint" to mapOf("x" to 0.55, "y" to 0.5),
            "confidence" to 0.86, "alreadyGood" to false, "reason" to "玩偶耳边留白", "subject" to fields))!!
        val target = (RememberedComposition.recommended(frame, plan, SubjectOutline.from(source, toy, pixels, segment)) as PlanResult.Accepted).value
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

    @Test fun aTurnCanCropEmptySpaceInTheModelSelectionButMustRetainTheMeasuredToy() {
        val selection = fields + ("outline" to listOf(listOf(166,218), listOf(450,214), listOf(515,244),
            listOf(520,680), listOf(480,698), listOf(165,698)))
        val pixels = image()
        val frame = SceneFrame(1,0,FrameGeometry(Extent(120,160), Crop(0,0,120,160), 0), source,
            null, Background(IntArray(40*52)), pixels)
        fun plan(x: Double) = validatedPlan(mapOf("aimPoint" to mapOf("x" to x, "y" to 0.5),
            "confidence" to 0.86, "alreadyGood" to false, "reason" to "耳边留白", "subject" to selection))!!
        val smallTurn = plan(0.66)
        val locked = SubjectOutline.from(source, smallTurn.subject, pixels, segment)!!
        val target = smallTurn.targetFrom(source.copy(subject = locked.sourceSubject), locked.bounds)!!
        val projection = RotationProjection(source, target)
        assertTrue(smallTurn.subject.outline.any { projection.project(it)!!.x < 0 })
        assertTrue(RememberedComposition.recommended(frame, smallTurn, locked) is PlanResult.Accepted)
        assertEquals(PlanResult.Rejected(PlanProblem.SUBJECT_CROPPED),
            RememberedComposition.recommended(frame, plan(0.80), locked))
        val partial = SubjectOutline.from(source, smallTurn.subject, image(visibleAbove = 0.56), segment)!!
        assertFalse(partial.hasFullCaptureContour)
        assertEquals(PlanResult.Rejected(PlanProblem.SUBJECT_CROPPED),
            RememberedComposition.recommended(frame, smallTurn, partial))
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

    @Test fun fallbackInteriorSeedRecoversWhenModelPointLandsOnBackgroundGap() {
        // Point (365, 250) falls in the notch between the rabbit's ears (background), while box covers the toy.
        val offSeed = SelectedSubject.parse(mapOf("name" to "玩偶", "kind" to "object",
            "box" to listOf(220, 220, 510, 685), "point" to listOf(365, 250)))!!
        val attempt = SubjectOutline.diagnose(source, offSeed, image(), segment)
        assertNotNull(attempt.outline)
        assertTrue(attempt.status.startsWith("OK_FALLBACK_SEED"))
    }

    @Test fun multiPartToyMergesUpperAndLowerHalvesInsideTheModelBox() {
        val w = 120
        val h = 160
        // Upper head (color A) and lower body (color B) separated by a 1px seam inside the box.
        val pixels = SubjectPixels(w, h, IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            when {
                x in 36..84 && y in 36..76 -> 0xeeaa44
                x in 38..82 && y in 78..122 -> 0x445566
                else -> 0xdddddd
            }
        })
        val twoPartSegment: SegmentAt = { img, seed ->
            val seedColor = img.at(seed.x, seed.y)
            val mask = ByteArray(img.width * img.height) { i ->
                val c = img.at((i % img.width + 0.5) / img.width, (i / img.width + 0.5) / img.height)
                if (c != 0xdddddd && c == seedColor) 0 else 255.toByte()
            }
            ForegroundMask.at(img.width, img.height, mask, seed)
        }
        val boxSubject = SelectedSubject.parse(mapOf("name" to "玩偶", "kind" to "object",
            "box" to listOf(280, 210, 720, 790), "point" to listOf(500, 340)))!!
        val attempt = SubjectOutline.diagnose(source, boxSubject, pixels, twoPartSegment)
        val outline = attempt.outline!!
        assertTrue("merged outline should reach lower body, bottom=${outline.bounds.bottom}", outline.bounds.bottom > 0.72)
        assertTrue("merged outline should include upper head, top=${outline.bounds.top}", outline.bounds.top < 0.25)
    }

    @Test fun lightweightSubjectTrackerFollowsTranslationRotationAndZoom() {
        val pixels0 = image()
        val outline = SubjectOutline.from(source, toy, pixels0, segment)!!
        val tracker = SubjectTracker.from(outline, pixels0)!!
        // Move the toy right by +0.08, down by -0.05 while rotating camera slightly and zooming to 1.5x.
        val halfAngle = Math.toRadians(2.0) / 2
        val zoomedObs = source.copy(
            timeNs = 60_000_000L,
            pose = Pose(cos(halfAngle), 0.0, -sin(halfAngle), 0.0),
            lens = source.lens.copy(fx = source.lens.fx * 1.5, fy = source.lens.fy * 1.5),
        )
        val movedPixels = image(dx = 0.08, dy = -0.05, pose = zoomedObs)
        val shift = tracker.track(zoomedObs, movedPixels)
        assertTrue("tracker should match moved subject", shift.matched)
        val proj = RotationProjection(source, zoomedObs)
        val expectedCenter = proj.project(Uv(0.365 + 0.08, 0.453 - 0.05))!!
        val baseProjected = proj.project(Uv(0.365, 0.453))!!
        val trackedCenter = Uv(baseProjected.x + shift.dx, baseProjected.y + shift.dy)
        assertEquals(expectedCenter.x, trackedCenter.x, 0.025)
        assertEquals(expectedCenter.y, trackedCenter.y, 0.025)

        // Removing the toy completely causes matched = false.
        val removed = tracker.track(zoomedObs.copy(timeNs = 120_000_000L), image(covered = true, pose = zoomedObs))
        assertFalse("removed subject must not match background", removed.matched)

        // Bringing the toy back far away on the right side triggers full-frame re-acquisition.
        val farMoved = image(dx = 0.42, dy = 0.0, pose = source)
        val reacquired = tracker.track(source.copy(timeNs = 180_000_000L), farMoved)
        assertTrue("re-entering subject should be re-acquired", reacquired.matched)
        assertEquals(0.42, reacquired.dx, 0.03)
    }

    @Test fun greenGuidanceRingFollowsMovedSubjectAndAdaptsOnZoom() {
        val pixels0 = image()
        val geometry = FrameGeometry(Extent(120, 160), Crop(0, 0, 120, 160), 0)
        val frame = SceneFrame(1, 0, geometry, source, null, Background(IntArray(40 * 52)), pixels0)
        // Toy sits left-of-center (~0.365) and model chooses keep.
        val plan = validatedPlan(mapOf(
            "subject" to fields,
            "choice" to "keep",
            "confidence" to 0.9,
            "reason" to "玩偶突出",
        ))!!
        val outline = SubjectOutline.from(source, toy, pixels0, segment)!!
        val remembered = (RememberedComposition.recommended(frame, plan, outline) as PlanResult.Accepted).value
        val guidance = PoseGuidance(remembered.observation)

        // 1. Move the toy right by +0.20 while phone stays still: green ring must shift right to follow!
        val shiftRight = TrackedShift(dx = 0.20, dy = 0.0, lens = source.lens, timeNs = 50_000_000L, matched = true)
        val liveTargetMoved = remembered.targetFor(source.pose, source.lens, shiftRight)
        val guideMoved = guidance.update(source.pose, source.lens, 50_000_000L, liveTargetMoved)
        assertNotNull(guideMoved.ring)
        assertTrue("green ring must move right when subject moves right, got ${guideMoved.ring}", guideMoved.ring!!.x > 0.65)
        assertFalse(guideMoved.ready)

        // 2. Zoom to 2x with the toy still on the left (dx = 0): at 2x the off-center toy shifts toward the left edge,
        // so the green ring must point left to keep the zoomed-in toy safely inside the frame!
        val zoomedLens = source.lens.copy(fx = source.lens.fx * 2.0, fy = source.lens.fy * 2.0)
        val liveTargetZoomed = remembered.targetFor(source.pose, zoomedLens, shift = null)
        val guideZoomed = PoseGuidance(remembered.observation).update(source.pose, zoomedLens, 100_000_000L, liveTargetZoomed)
        assertNotNull(guideZoomed.ring)
        assertTrue("zooming to 2x on a left-side subject must pull aim left, got ${guideZoomed.ring}", guideZoomed.ring!!.x < 0.42)
    }

    @Test fun switchingCompositionPresetsLocallyRecalculatesGreenRingAndRestoresAuto() {
        val pixels0 = image()
        val geometry = FrameGeometry(Extent(120, 160), Crop(0, 0, 120, 160), 0)
        val frame = SceneFrame(1, 0, geometry, source, null, Background(IntArray(40 * 52)), pixels0)
        // Toy sits left-of-center (~0.365, ~0.453); initial model choice is keep.
        val plan = validatedPlan(mapOf(
            "subject" to fields,
            "choice" to "keep",
            "confidence" to 0.9,
            "reason" to "玩偶突出",
        ))!!
        val outline = SubjectOutline.from(source, toy, pixels0, segment)!!
        val base = (RememberedComposition.recommended(frame, plan, outline) as PlanResult.Accepted).value

        // Switching to SYMMETRY (center) on a left-side toy must aim LEFT (ring.x < 0.44) to bring toy to center.
        val centered = base.withPreset(PresetSelection(CompositionPreset.SYMMETRY), source.pose, source.lens, null)
        val centerGuide = PoseGuidance(centered.observation).update(source.pose, source.lens, 50_000_000L, centered.targetFor(source.pose, source.lens, null))
        assertTrue("symmetry preset should pull left-side toy toward center, ring=${centerGuide.ring}", centerGuide.ring!!.x < 0.42)

        // Switching to RIGHT_THIRD must aim even further LEFT than SYMMETRY.
        val rightThird = centered.withPreset(PresetSelection(CompositionPreset.RULE_OF_THIRDS, CompositionChoice.RIGHT_THIRD), source.pose, source.lens, null)
        val rightGuide = PoseGuidance(rightThird.observation).update(source.pose, source.lens, 100_000_000L, rightThird.targetFor(source.pose, source.lens, null))
        assertTrue("right third should aim further left than center, right=${rightGuide.ring} center=${centerGuide.ring}", rightGuide.ring!!.x < centerGuide.ring!!.x - 0.05)

        // Switching back to AUTO restores the original KEEP choice (ring back at center 0.5).
        val restored = rightThird.withPreset(PresetSelection(CompositionPreset.AUTO), source.pose, source.lens, null)
        val restoredGuide = PoseGuidance(restored.observation).update(source.pose, source.lens, 150_000_000L, restored.targetFor(source.pose, source.lens, null))
        assertEquals(0.5, restoredGuide.ring!!.x, 0.02)
        assertEquals(0.5, restoredGuide.ring!!.y, 0.02)

        // Non-AUTO presets append framing guidance to ChatProtocol prompt; AUTO keeps default prompt unchanged.
        assertEquals(ChatProtocol.prompt, ChatProtocol.promptFor(CompositionPreset.AUTO))
        assertTrue(ChatProtocol.promptFor(CompositionPreset.RULE_OF_THIRDS).contains("left_third"))
        assertTrue(ChatProtocol.promptFor(CompositionPreset.SYMMETRY).contains("center"))
    }
}
