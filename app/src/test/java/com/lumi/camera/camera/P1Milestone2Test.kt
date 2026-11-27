package com.lumi.camera.camera

import com.lumi.camera.gallery.LumiGalleryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class P1Milestone2Test {

    @Test
    fun `capture pipeline executes jobs asynchronously in order and enforces maxPending backpressure`() {
        val gate = CountDownLatch(1)
        val done = CountDownLatch(2)
        val completed = mutableListOf<String?>()
        val states = mutableListOf<Int>()
        val directCallback = Executor { it.run() }

        val pipeline = CapturePipeline(
            maxPending = 2,
            callbackExecutor = directCallback,
            onStateChanged = { synchronized(states) { states += it.pendingCount } },
        )

        val accepted1 = pipeline.submit(
            work = {
                gate.await(2, TimeUnit.SECONDS)
                "shot_1"
            },
            onComplete = {
                synchronized(completed) { completed += it }
                done.countDown()
            },
        )
        val accepted2 = pipeline.submit(
            work = { "shot_2" },
            onComplete = {
                synchronized(completed) { completed += it }
                done.countDown()
            },
        )
        // Third job exceeds maxPending = 2 while job 1 is blocked
        val accepted3 = pipeline.submit(work = { "shot_3" }, onComplete = {})

        assertTrue(accepted1)
        assertTrue(accepted2)
        assertFalse(accepted3)
        assertEquals(2, pipeline.pendingCount)

        gate.countDown()
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertEquals(listOf("shot_1", "shot_2"), completed)
        assertEquals(0, pipeline.pendingCount)
        assertTrue(states.contains(2))
        assertEquals(0, states.last())

        pipeline.close()
        assertFalse(pipeline.submit(work = { "after_close" }, onComplete = {}))
    }

    @Test
    fun `capture pipeline recovers cleanly when work throws and resets pendingCount`() {
        val done = CountDownLatch(1)
        var received: String? = "initial"
        val pipeline = CapturePipeline(
            maxPending = 2,
            callbackExecutor = Executor { it.run() },
        )

        assertTrue(
            pipeline.submit<String>(
                work = { error("simulated GL failure") },
                onComplete = {
                    received = it
                    done.countDown()
                },
            ),
        )
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertNull(received)
        assertEquals(0, pipeline.pendingCount)
        pipeline.close()
    }

    @Test
    fun `gallery exif formatter parses rationals and formats clean camera summary`() {
        val info = LumiGalleryStore.formatExif(
            fNumber = "188/100",
            exposureTime = "0.01",
            iso = "249",
            focal35mm = "24",
            focalMm = "559/100",
            width = 3000,
            height = 4000,
        )
        assertEquals("f/1.88", info.aperture)
        assertEquals("1/100s", info.shutter)
        assertEquals("ISO 249", info.iso)
        assertEquals("24mm", info.focalLength)
        assertEquals("3000×4000", info.dimensions)
        assertEquals("f/1.88  ·  1/100s  ·  ISO 249  ·  24mm  ·  3000×4000", info.summaryLine())

        val longExposure = LumiGalleryStore.formatExif(
            fNumber = "2.0",
            exposureTime = "1.5",
            iso = "800",
            focal35mm = null,
            focalMm = "50/10",
            width = 0,
            height = 0,
        )
        assertEquals("f/2", longExposure.aperture)
        assertEquals("1.5s", longExposure.shutter)
        assertEquals("5mm", longExposure.focalLength)

        val empty = LumiGalleryStore.formatExif(null, null, null, null, null, 0, 0)
        assertEquals("无拍摄参数", empty.summaryLine())
    }
}
