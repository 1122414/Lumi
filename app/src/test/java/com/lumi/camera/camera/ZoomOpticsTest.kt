package com.lumi.camera.camera

import com.lumi.camera.guidance.*
import com.lumi.camera.ui.dragWheel
import com.lumi.camera.ui.wheelStop
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import org.junit.Assert.*
import org.junit.Test

class ZoomOpticsTest {
    @Test fun keyStopsUseEquivalentFieldsOfViewAndTheConfirmedStockShortcut() {
        val main = 5.59f / 10.24f
        val tele = 7.1f / 6.5536f
        assertEquals(listOf(1f, 2f), lensZoomStops(main, listOf(main, tele), "other"))
        assertEquals(listOf(1f, 2f, 4f), lensZoomStops(main, listOf(main, tele), "PKX110"))
        assertEquals(listOf(1f), lensZoomStops(null, emptyList(), "unknown"))
        assertEquals(listOf(0.6f, 1f, 3.5f), lensZoomStops(1f, listOf(0.6f, 1f, 3.5f, Float.NaN), "other"))
    }

    @Test fun dialAccumulatesSmallMovesAndCanStopBetweenLenses() {
        val distance = -0.63 * kotlin.math.ln(2.8 / 2.0)
        var raw = 2.0
        repeat(30) { raw = dragWheel(raw, distance / 30, 1f, 20f) }
        assertEquals(2.8f, wheelStop(raw, 1f, 20f), 0f)
        assertEquals(2f, wheelStop(dragWheel(raw, -distance, 1f, 20f), 1f, 20f), 0f)
        assertEquals(0.65f, wheelStop(dragWheel(2.0, 9.0, 0.65f, 7.54f), 0.65f, 7.54f), 0f)
        assertEquals(7.54f, wheelStop(dragWheel(2.0, -9.0, 0.65f, 7.54f), 0.65f, 7.54f), 0f)
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
