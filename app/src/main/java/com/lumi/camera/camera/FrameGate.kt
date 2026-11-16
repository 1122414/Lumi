package com.lumi.camera.camera

// Used only on the UI thread. A new camera binding can start a different timestamp sequence.
internal class FrameGate {
    var session = 0L
        private set
    private var tap = 0L
    private var latest = Long.MIN_VALUE

    fun reset() { session++; tap = 0; latest = Long.MIN_VALUE }
    fun select(): Long = ++tap
    fun accept(run: Long, selection: Long, timestamp: Long): Boolean {
        if (run != session || selection != tap || timestamp <= latest) return false
        latest = timestamp
        return true
    }
}
