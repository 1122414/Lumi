package com.lumi.camera.guidance

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import org.junit.Assert.*
import org.junit.Test

class RotationGuidanceTest {
    private val origin = Pose(1.0, 0.0, 0.0, 0.0)
    private val lens = Lens(1.2, 0.9, 0.5, 0.5)
    private val face = FacePosition(7, Uv.CENTER, 0.22)
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

    @Test fun observedFaceCorrectsTheProjectionButCannotReplaceAttitude() {
        val g = RotationGuidance(target)
        val ring = g.feed(sample(30, uv = Uv(0.55, 0.5))).ring!!
        assertEquals(0.53, ring.x, 1e-6)
        assertFalse(g.feed(sample(60, uv = Uv(0.85, 0.5))).trusted)
    }

    @Test fun projectionIsRelativeToTheRememberedPoseNotNorthOrAnUprightPhone() {
        val start = right(67.0) * pitch(23.0)
        val remembered = target.copy(pose = start, face = face.copy(center = Uv(0.36, 0.42)))
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
                1 -> it.copy(face = null)
                2 -> it.copy(face = face.copy(id = 8))
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
        val missing = sample(500, right(5.0)).copy(face = null)
        val pending = g.update(missing, missing.timeNs, false)
        assertNotNull(pending.ring)
        assertEquals(0.5 - lens.fx * tan(Math.toRadians(5.0)), pending.ring!!.x, 1e-6)
        assertFalse(pending.ready)
        assertFalse(pending.trusted)
        assertNull(g.feed(sample(740, right(6.0)).copy(face = null)).ring)
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
        val miss = g.feed(sample(500).copy(face = null))
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
            assertNull(g.feed(sample(0).copy(face = null)).ring)
            g.feed(sample(100))
            val o = sample(150).let { if (stale) it else it.copy(face = face.copy(id = 23)) }
            val rejected = g.update(o, o.timeNs + if (stale) 251_000_000L else 0L, true)
            assertNull(rejected.ring)
            assertFalse(rejected.ready)
            assertNull(g.feed(sample(if (stale) 420 else 180).copy(face = null)).ring)
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
            val current = if (i in 4..5) seen.copy(face = null) else seen
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
