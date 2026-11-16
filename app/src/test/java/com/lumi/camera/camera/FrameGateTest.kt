package com.lumi.camera.camera

import org.junit.Assert.*
import org.junit.Test

class FrameGateTest {
    @Test fun lateAndDuplicateResultsCannotRestoreAMarkerAfterItWasLost() {
        val gate = FrameGate()
        val tap = gate.select()
        assertTrue(gate.accept(gate.session, tap, 110))
        assertFalse(gate.accept(gate.session, tap, 105))
        assertFalse(gate.accept(gate.session, tap, 110))
    }

    @Test fun queuedResultsAfterRetapAndAfterRebindingAreIgnored() {
        val gate = FrameGate()
        val firstRun = gate.session
        val firstTap = gate.select()
        val secondTap = gate.select()
        assertFalse(gate.accept(firstRun, firstTap, 400))
        assertTrue(gate.accept(firstRun, secondTap, 410))
        gate.reset()
        assertEquals(firstTap, gate.select())
        assertFalse(gate.accept(firstRun, firstTap, 500))
        assertTrue(gate.accept(gate.session, firstTap, 10))
    }
}
