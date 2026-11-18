package com.lumi.camera.shotplan

import org.junit.Assert.*
import org.junit.Test

class PendingRecommendationFrameTest {
    @Test fun aTapSurvivesMissingAndLateFramesAndClaimsOneFreshFrame() {
        val pending = PendingRecommendationFrame()
        val token = RecommendationRequests().begin(1_000_000_000L)
        pending.await(token)
        assertNull(pending.forCapture(token.startedNs - 1))
        assertFalse(pending.claim(token, token.startedNs, token.startedNs + 300_000_000))
        val next = token.startedNs + 350_000_000
        assertEquals(token, pending.forCapture(next))
        assertTrue(pending.claim(token, next, next + 10_000_000))
        assertFalse(pending.claim(token, next, next + 10_000_000))
    }

    @Test fun replacingCancellingOrExpiringTheRequestDiscardsFramesInFlight() {
        val pending = PendingRecommendationFrame()
        val requests = RecommendationRequests()
        val first = requests.begin(100)
        pending.await(first)
        val second = requests.begin(200)
        pending.await(second)
        assertFalse(pending.claim(first, 300, 310))
        assertNull(pending.forCapture(second.deadlineNs))
        assertFalse(pending.claim(second, second.deadlineNs - 5, second.deadlineNs))
        pending.await(null)
        assertFalse(pending.claim(second, 300, 310))
    }
}
