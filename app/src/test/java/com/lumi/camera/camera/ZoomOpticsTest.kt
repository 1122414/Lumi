package com.lumi.camera.camera

import com.lumi.camera.guidance.*
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import org.junit.Assert.*
import org.junit.Test

class ZoomOpticsTest {
    @Test fun shortcutsIncludeTheReportedLimitsAndOnlyAvailableIntermediateRatios() {
        assertEquals(listOf(1f, 2f, 3f, 5f, 10f, 20f), ViewfinderZoom(min = 1f, max = 20f).shortcuts())
        assertEquals(listOf(0.6f, 1f, 2f, 3f, 5f, 7.5f), ViewfinderZoom(min = 0.6f, max = 7.5f).shortcuts())
        assertEquals(listOf(1f, 2f, 2.4f), ViewfinderZoom(max = 2.4f).shortcuts())
        assertEquals(listOf(1f), ViewfinderZoom().shortcuts())
        val high = ViewfinderZoom(min = 0.5f, max = 120f).shortcuts()
        assertEquals(listOf(0.5f, 1f, 2f, 3f, 5f, 10f, 20f, 50f, 100f, 120f), high)
    }

    @Test fun effectiveFocalUsesTheAppliedFieldOfViewAndRejectsTransitionFrames() {
        for (ratio in listOf(0.6f, 1f, 2f, 3.25f)) {
            assertEquals(5f*ratio, zoomedFocal(5f, ratio, ratio)!!, 0.0001f)
        }
        assertNull(zoomedFocal(5f, 1f, 2f))
        assertNull(zoomedFocal(null, 2f, 2f))
        assertNull(zoomedFocal(5f, Float.NaN, 2f))
        assertNull(zoomedFocal(5f, 2f, Float.NaN))
    }

    @Test fun theSameRotationMovesTheRingTwiceAsFarAtDoubleZoom() {
        val turn = Math.toRadians(3.0)
        val pose = Pose(cos(turn/2), 0.0, -sin(turn/2), 0.0)
        for (ratio in listOf(1f, 2f)) {
            val fx = zoomedFocal(1f, ratio, ratio)!!.toDouble()
            val source = Observation(0L, Pose(1.0,0.0,0.0,0.0), Lens(fx,fx,.5,.5), null)
            val projected = RotationProjection(source, source.copy(pose = pose)).project(Uv.CENTER)!!
            assertEquals(0.5-ratio*tan(turn), projected.x, 1e-6)
        }
    }
}
