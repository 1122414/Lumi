package com.lumi.camera.guidance

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import org.junit.Assert.*
import org.junit.Test

class RotationGuidanceTest {
    private val origin = Pose(1.0, 0.0, 0.0, 0.0)
    private val lens = Lens(1.2, 0.9, 0.5, 0.5)
    private val face = SubjectPosition(7, Uv.CENTER, 0.22)
    private val target = Observation(0, origin, lens, face)
    private fun pitch(deg: Double): Pose {
        val half = Math.toRadians(deg) / 2
        return Pose(cos(half), sin(half), 0.0, 0.0)
    }
    private fun right(deg: Double): Pose {
        val half = Math.toRadians(deg) / 2
        return Pose(cos(half), 0.0, -sin(half), 0.0)
    }
    private fun sample(ms: Long, pose: Pose = origin, uv: Uv = Uv.CENTER) =
        Observation(ms * 1_000_000L, pose, lens, face.copy(center = uv))
    private fun RotationGuidance.feed(o: Observation) = update(o, o.timeNs, true)

    @Test fun shortVerificationGapsReprojectTheLastImageWithoutReusingReadiness() {
        val continuity = RingContinuity()
        val frame = sample(100)
        continuity.measured(Guide(Uv.CENTER, ready = true, evidence = GuideEvidence.CONFIRMED), frame)
        for ((i, evidence) in listOf(GuideEvidence.MISMATCH, GuideEvidence.BACKGROUND_UNCERTAIN,
            GuideEvidence.NO_FACE, GuideEvidence.STALE).withIndex()) {
            val time = frame.timeNs + (i+1)*80_000_000L
            val gap = continuity.measured(Guide(null, evidence = evidence), frame.copy(timeNs = time))
            val projected = continuity.display(gap, time, right(4.0))
            assertEquals(0.5-lens.fx*tan(Math.toRadians(4.0)), projected.ring!!.x, 1e-6)
            assertFalse(projected.ready || projected.merged || projected.trusted)
        }
        val gap = Guide(null, evidence = GuideEvidence.STALE)
        assertNull(continuity.display(gap, 451_000_000L, origin).ring)
        assertNull(continuity.display(gap, 200_000_000L, right(20.0)).ring)
        assertNull(continuity.display(gap, 200_000_000L, null).ring)
        continuity.measured(Guide(null, evidence = GuideEvidence.OTHER_FACE), sample(201))
        assertNull(continuity.display(gap, 202_000_000L, origin).ring)
    }

    @Test fun returnBearingSurvivesTheSideAndRearHemisphereWithoutAFaceOrCameraFrame() {
        for (sign in listOf(1.0, -1.0)) {
            val direction = ReturnDirection(target)
            for (angle in listOf(45.0, 89.9, 90.0, 135.0, 179.7, 180.0, 179.9, 120.0, 45.0)) {
                val g = direction.display(Guide(null), null, right(sign * angle))
                assertEquals(if (sign > 0) 0.0 else 1.0, g.ring!!.x, 1e-8)
                assertEquals(0.5, g.ring.y, 1e-8)
                assertFalse(g.ready)
            }
            val inside = direction.display(Guide(null), null, right(sign * 12.0))
            assertEquals(0.5 - lens.fx * tan(Math.toRadians(sign * 12)), inside.ring!!.x, 1e-6)
            assertNull(direction.display(Guide(null), null, origin).ring)
            assertNull(direction.display(Guide(null), null, null).ring)
        }
    }

    @Test fun verticalReturnPointsToTheEdgeAndNeverReusesAReadyFrame() {
        val direction = ReturnDirection(target)
        val ready = Guide(Uv.CENTER, true, GuideEvidence.CONFIRMED)
        for (angle in listOf(85.0, 120.0, -95.0)) {
            val g = direction.display(ready, target, pitch(angle))
            assertEquals(0.5, g.ring!!.x, 1e-8)
            assertEquals(if (angle > 0) 1.0 else 0.0, g.ring.y, 1e-8)
            assertFalse(g.ready)
            assertFalse(g.merged)
        }
        assertEquals(ready, direction.display(ready, target, origin))
    }

    @Test fun aSubjectSidewaysToTheCameraStillHasAnUnambiguousReturnDirection() {
        val corners = listOf(Uv(0.10,0.20), Uv(0.67,0.20), Uv(0.67,0.82), Uv(0.10,0.82))
        val direction = ReturnDirection(target, target, corners)
        for (sign in listOf(1.0, -1.0)) {
            val sideways = right(sign * 92.0)
            val projection = RotationProjection(target, target.copy(pose = sideways))
            assertTrue(corners.any { projection.project(it) == null })
            assertTrue(corners.any { projection.project(it) != null })
            val guide = direction.display(Guide(null), null, sideways)
            assertEquals(if (sign > 0) 0.0 else 1.0, guide.ring!!.x, 1e-8)
            assertFalse(guide.ready)
        }
        assertNull(direction.display(Guide(null), null, right(20.0)).ring)
    }

    @Test fun worldDirectionUsesTheRecommendedPoseAndTurnsTheOtherWayAfterPassingTheAntipode() {
        val start = right(65.0) * pitch(13.0)
        val direction = ReturnDirection(target.copy(pose = start, lens = lens.copy(cx = 0.46, cy = 0.52)))
        val lost = Guide(null)
        val a = direction.display(lost, null, start * right(120.0))
        val b = direction.display(lost, null, start * right(235.0))
        assertEquals(0.0, a.ring!!.x, 1e-8)
        assertEquals(1.0, b.ring!!.x, 1e-8)
        assertNull(direction.display(lost, null, start).ring)
    }

    @Test fun lostVisibleSubjectPausesTheSameRecommendationAndOffscreenOffersOnlyABearing() {
        val direction = ReturnDirection(target, target, listOf(Uv(0.3,0.2),Uv(0.7,0.2),Uv(0.7,0.8),Uv(0.3,0.8)))
        val missing = Guide(null, evidence = GuideEvidence.NO_FACE)
        assertNull(direction.display(missing, null, right(12.0)).ring)
        val back = direction.display(missing, null, right(100.0))
        assertEquals(GuideEvidence.RETURN_DIRECTION, back.evidence)
        assertFalse(back.ready)
        assertNull(direction.display(missing, null, right(100.0), subjectVisible=true).ring)
        val guidance = RotationGuidance(target, verifyScene=true)
        for (t in 0L..450L step 50) guidance.feed(sample(t))
        val changed = sample(900)
        val checking = guidance.update(changed, changed.timeNs, false)
        assertEquals(Uv.CENTER, checking.ring)
        assertEquals(GuideEvidence.VERIFYING_COMPOSITION, checking.evidence)
        assertFalse(checking.ready)
        for (t in 950L..1250L step 50) assertFalse(guidance.feed(sample(t)).ready)
        assertTrue(guidance.feed(sample(1300)).ready)
    }

    @Test fun aPendingCompositionShowsTheOriginalBearingOnlyForTheFreshlyMatchedInstance() {
        val targetPose = right(8.0) * pitch(3.0)
        val goal = target.copy(pose = targetPose)
        val direction = ReturnDirection(goal, target,
            listOf(Uv(0.3,0.2), Uv(0.7,0.2), Uv(0.7,0.8), Uv(0.3,0.8)))
        val current = sample(100)
        val mismatch = Guide(null, evidence = GuideEvidence.MISMATCH)
        val measured = Uv(0.58, 0.43)
        val backgroundGap = Guide(measured, evidence = GuideEvidence.VERIFYING_COMPOSITION)
        for (pose in listOf(origin, right(12.0), targetPose)) {
            val projected = RotationProjection(current, current.copy(pose = pose)).project(measured)!!
            val shown = direction.display(backgroundGap, current, pose, subjectVisible = true)
            assertEquals(GuideEvidence.VERIFYING_COMPOSITION, shown.evidence)
            assertTrue(projected.distance(shown.ring!!) < 1e-6)
            assertFalse(shown.ready)
            assertFalse(shown.merged)
        }
        assertNull(direction.display(mismatch, current, origin, subjectVisible = true).ring)
        assertNull(direction.display(mismatch, current, origin, subjectVisible = false).ring)
        assertNull(direction.display(mismatch, current.copy(subject = face.copy(id = 8)), origin, true).ring)
        assertNull(direction.display(mismatch, current.copy(subject = null), origin, true).ring)
        assertNull(direction.display(Guide(null, evidence = GuideEvidence.STALE), current, origin, true).ring)
        assertNull(direction.display(mismatch, null, origin, true).ring)
    }

    @Test fun aimingRightMovesRingLeftAndAimingUpMovesItDown() {
        val d = tan(Math.toRadians(12.0))
        for ((pose, expected) in listOf(right(12.0) to Uv(0.5 - lens.fx*d, 0.5),
            pitch(12.0) to Uv(0.5, 0.5 + lens.fy*d))) {
            val guide = RotationGuidance(target).feed(sample(33, pose, expected))
            assertEquals(expected.x, guide.ring!!.x, 1e-6)
            assertEquals(expected.y, guide.ring.y, 1e-6)
            assertFalse(guide.ready)
        }
    }

    @Test fun imagePlacementDeterminesTheDirectionEvenWhenHeadingPointsTheOtherWay() {
        val wanted = target.copy(subject = face.copy(center = Uv(0.35, 0.5)))
        val direction = ReturnDirection(wanted, wanted, listOf(Uv(0.2,0.3), Uv(0.5,0.7)))
        val guidance = RotationGuidance(wanted, verifyScene = true)
        // Independent horizontal pinhole calculation from measured pixel positions.
        // Move the subject left through its desired position, with the same biased IMU reading.
        for ((i, x) in listOf(0.48, 0.41, 0.35, 0.30).withIndex()) {
            val frame = sample((i+1)*80L, right(12.0), Uv(x,0.5))
            val actual = direction.display(guidance.update(frame, frame.timeNs, false), frame, frame.pose, true)
            val a = (x - lens.cx) / lens.fx
            val b = (0.35 - lens.cx) / lens.fx
            val expected = lens.cx + lens.fx*(a-b)/(1+a*b)
            assertEquals("ring for measured x=$x", expected, actual.ring!!.x, 0.002)
            assertEquals(0.5, actual.ring.y, 0.002)
            assertFalse(actual.ready)
        }
    }

    @Test fun rollRotatesTheDesiredOffsetAroundTheMeasuredSubjectAndDoesNotSwapPhoneAxes() {
        val h = Math.PI/4
        val rolled = Pose(cos(h), 0.0, 0.0, sin(h))
        val wanted = target.copy(subject = face.copy(center = Uv(0.6,0.5)))
        // After a quarter turn the +x subject offset appears down the portrait image.
        val y = 0.5 + 0.1 * lens.fy / lens.fx
        val current = sample(50, rolled, Uv(0.5,y))
        val actual = RotationGuidance(wanted, true).update(current,current.timeNs,false)
        assertEquals(0.5, actual.ring!!.x, 1e-6)
        assertEquals(0.5, actual.ring.y, 1e-6)
        assertFalse(actual.ready)
    }

    @Test fun identifiedInteriorKeepsTheOvershootDirectionEvenWhenTheSilhouetteIsIncomplete() {
        val guidance = RotationGuidance(target, true)
        for (i in 1..12) {
            val frame = sample(i*50L, right(-3.0), Uv(0.47, 0.5)).copy(completeSubject = false, matchedInterior = true)
            val guide = guidance.update(frame, frame.timeNs, true)
            assertEquals(0.47, guide.ring!!.x, 1e-6)
            assertEquals(GuideEvidence.PARTIAL_SUBJECT, guide.evidence)
            assertFalse(guide.ready)
        }
    }

    @Test fun alignedPixelsStillNeedMatchingBackgroundAndAStationaryPhone() {
        val guidance = RotationGuidance(target, true)
        for (i in 1..18) {
            val current = sample(i*50L, right(if (i % 2 == 0) 8.0 else 12.0))
            val guide = guidance.update(current, current.timeNs) { measured ->
                assertTrue(measured.pose.degreesTo(origin) < 0.001)
                i > 8
            }
            assertEquals(0.5, guide.ring!!.x, 1e-6)
            assertFalse(guide.ready)
        }
        for (i in 19..30) {
            val current = sample(i*50L, right(5.0))
            assertEquals(i >= 26, guidance.feed(current).ready)
        }
    }

    @Test fun observedFaceCorrectsTheProjectionButCannotReplaceAttitude() {
        val g = RotationGuidance(target)
        val ring = g.feed(sample(30, uv = Uv(0.55, 0.5))).ring!!
        assertEquals(0.53, ring.x, 1e-6)
        assertFalse(g.feed(sample(60, uv = Uv(0.85, 0.5))).trusted)
    }

    @Test fun projectionIsRelativeToTheRememberedPoseNotNorthOrAnUprightPhone() {
        val start = right(67.0) * pitch(23.0)
        val remembered = target.copy(pose = start, subject = face.copy(center = Uv(0.36, 0.42)))
        val current = sample(40, start * right(9.0))
        val ring = RotationProjection(remembered, current).project(Uv.CENTER)!!
        assertEquals(0.5 - lens.fx * tan(Math.toRadians(9.0)), ring.x, 1e-6)
        assertEquals(0.5, ring.y, 1e-6)
    }

    @Test fun displayCompensationKeepsTheUprightViewAxesAfterAPhysicalQuarterTurn() {
        val half = Math.PI / 4
        val physical = Pose(cos(half), 0.0, 0.0, sin(half))
        assertEquals(0.0, physical.forDisplay(1).degreesTo(origin), 1e-5)
        assertEquals(0.0, physical.inverse().forDisplay(3).degreesTo(origin), 1e-5)
    }

    @Test fun repeatedFrameCannotAccumulateHoldTime() {
        val g = RotationGuidance(target)
        val o = sample(50)
        assertTrue(g.feed(o).trusted)
        for (ms in 100L..500L step 50) assertFalse(g.update(o, ms * 1_000_000, true).ready)
    }

    @Test fun returnAndHoldNeedsFreshStableFrames() {
        val g = RotationGuidance(target)
        assertFalse(g.feed(sample(50, right(10.0), Uv(0.29, 0.5))).ready)
        for (ms in 100L..400L step 50) assertFalse(g.feed(sample(ms)).ready)
        val ready = g.feed(sample(450))
        assertTrue(ready.ready)
        assertEquals(Uv.CENTER, ready.ring)
    }

    @Test fun aFastPassOrAFrameGapDoesNotCountAsHolding() {
        val g = RotationGuidance(target)
        assertFalse(g.feed(sample(10, right(8.0), Uv(0.33, 0.5))).ready)
        assertFalse(g.feed(sample(50)).ready)
        assertFalse(g.feed(sample(90, right(-8.0), Uv(0.67, 0.5))).ready)
        assertFalse(g.feed(sample(130)).ready)
        assertFalse(g.feed(sample(600)).ready)
    }

    @Test fun staleMissingOtherFaceOrLostBackgroundClearReadiness() {
        for (reason in 0..3) {
            val g = RotationGuidance(target)
            for (ms in 0L..400L step 50) g.feed(sample(ms))
            assertTrue(g.feed(sample(450)).ready)
            val o = sample(500).let { when (reason) {
                1 -> it.copy(subject = null)
                2 -> it.copy(subject = face.copy(id = 8))
                else -> it
            } }
            val result = g.update(o, o.timeNs + if (reason == 0) 251_000_000 else 0, reason != 3)
            assertFalse(result.trusted)
            assertFalse(result.ready)
            assertFalse(g.feed(sample(800)).ready)
        }
    }

    @Test fun aNoisyBackgroundCannotBlinkOutARotationSupportedByTheSameFace() {
        val g = RotationGuidance(target)
        for (i in 0..12) {
            val angle = 2.0 + i * 0.5
            val expected = 0.5 - lens.fx * tan(Math.toRadians(angle))
            val o = sample(50L * i, right(angle), Uv(expected, 0.5))
            val guide = g.update(o, o.timeNs, backgroundAgrees = i % 4 == 0)
            assertNotNull("direction at frame $i", guide.ring)
            assertEquals(expected, guide.ring!!.x, 1e-6)
            assertFalse(guide.ready)
        }
    }

    @Test fun aBriefFaceGapReprojectsFromFreshPoseButCannotGrantOrExtendReadiness() {
        val g = RotationGuidance(target)
        for (ms in 0L..400L step 50) g.feed(sample(ms))
        assertTrue(g.feed(sample(450)).ready)
        val missing = sample(500, right(5.0)).copy(subject = null)
        val pending = g.update(missing, missing.timeNs, false)
        assertNotNull(pending.ring)
        assertEquals(0.5 - lens.fx * tan(Math.toRadians(5.0)), pending.ring!!.x, 1e-6)
        assertFalse(pending.ready)
        assertFalse(pending.trusted)
        assertNull(g.feed(sample(740, right(6.0)).copy(subject = null)).ring)
        assertFalse(g.feed(sample(780)).ready)
        assertNotNull(g.feed(sample(820)).ring)
    }

    @Test fun smallVerifiedHandTremorAfterSettlingDoesNotReopenTheRing() {
        val g = RotationGuidance(target)
        for (ms in 0L..400L step 50) g.feed(sample(ms))
        assertTrue(g.feed(sample(450)).ready)
        fun turned(ms: Long, deg: Double) =
            sample(ms, right(deg), Uv(0.5 - lens.fx * tan(Math.toRadians(deg)), 0.5))
        assertTrue(g.feed(turned(500, 1.5)).ready)
        val departed = g.feed(turned(550, 6.0))
        assertFalse(departed.ready)
        assertTrue(departed.ring!!.x < 0.4)
        assertFalse(g.feed(sample(600)).ready)
    }

    @Test fun aMissDoesNotFlashTheMergedOverlayOrSkipTheNextHold() {
        val g = RotationGuidance(target)
        for (ms in 0L..450L step 50) g.feed(sample(ms))
        val miss = g.feed(sample(500).copy(subject = null))
        assertTrue(miss.merged)
        assertFalse(miss.ready)
        for (ms in 550L..850L step 50) {
            val restored = g.feed(sample(ms))
            assertTrue(restored.merged)
            assertFalse(restored.ready)
        }
        assertTrue(g.feed(sample(900)).ready)
        for (ms in 950L..1500L step 50) {
            val o = sample(ms)
            val unconfirmed = g.update(o, o.timeNs, false)
            assertFalse(unconfirmed.ready)
            if (ms == 1500L) assertFalse(unconfirmed.merged)
        }
    }

    @Test fun aWrongSubjectOrExpiredFrameDropsTheDirectionUntilFreshFaceEvidence() {
        for (stale in listOf(false, true)) {
            val g = RotationGuidance(target)
            assertNull(g.feed(sample(0).copy(subject = null)).ring)
            g.feed(sample(100))
            val o = sample(150).let { if (stale) it else it.copy(subject = face.copy(id = 23)) }
            val rejected = g.update(o, o.timeNs + if (stale) 251_000_000L else 0L, true)
            assertNull(rejected.ring)
            assertFalse(rejected.ready)
            assertNull(g.feed(sample(if (stale) 420 else 180).copy(subject = null)).ring)
            val recovered = g.feed(sample(470))
            assertNotNull(recovered.ring)
            assertFalse(recovered.ready)
        }
    }

    @Test fun smoothingReducesFaceJitterWithoutMakingTheRingLagBehindRotation() {
        val g = RotationGuidance(target)
        g.feed(sample(0))
        val jitter = g.feed(sample(30, uv = Uv(0.55, 0.5))).ring!!
        assertTrue(jitter.x in 0.50..0.52)
        val angle = 10.0
        val projected = 0.5 - lens.fx * tan(Math.toRadians(angle))
        val turned = g.feed(sample(60, right(angle), Uv(projected, 0.5)))
        assertEquals(projected, turned.ring!!.x, 0.015)
        assertFalse(turned.ready)
    }

    @Test fun aSmoothedRingStillNearTheCenterCannotHideARealDeparture() {
        val g = RotationGuidance(target)
        for (ms in 0L..450L step 50) g.feed(sample(ms))
        val departed = g.feed(sample(466, uv = Uv(0.585, 0.5)))
        assertTrue(departed.ring!!.distance(Uv.CENTER) < 0.025)
        assertFalse(departed.ready)
        assertFalse(departed.merged)
    }

    @Test fun turningAndTiltingTogetherKeepsTheDirectionThroughIntermittentVision() {
        val g = RotationGuidance(target)
        for (i in 0..10) {
            val yaw = 10.0 - i
            val tilt = -yaw * 0.6
            val x = 0.5 - lens.fx * tan(Math.toRadians(yaw)) / cos(Math.toRadians(tilt))
            val y = 0.5 + lens.fy * tan(Math.toRadians(tilt))
            val seen = sample(40L * i, right(yaw) * pitch(tilt), Uv(x, y))
            val current = if (i in 4..5) seen.copy(subject = null) else seen
            val guide = g.update(current, current.timeNs, backgroundAgrees = i % 3 != 1)
            assertNotNull("both axes keep following the phone at frame $i", guide.ring)
            assertEquals(x, guide.ring!!.x, 1e-6)
            assertEquals(y, guide.ring.y, 1e-6)
            assertFalse(guide.ready)
        }
        for (ms in 450L..750L step 50) assertFalse(g.feed(sample(ms)).ready)
        assertTrue(g.feed(sample(800)).ready)
    }
}
