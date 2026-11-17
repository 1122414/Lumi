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
}
