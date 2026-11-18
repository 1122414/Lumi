package com.lumi.camera.shotplan

import java.net.ConnectException
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class ModelConfigTest {
    private val config = ModelConfig("https://studio.example/private/v1", "vision", "example-secret")

    @Test fun savedSettingsIncludingAnUnreadableKeyNeverFallBackToBuildDefaults() {
        val emptyKey = config.copy(apiKey = "")
        for (debug in listOf(true, false)) {
            for (saved in listOf(config, emptyKey)) {
                assertEquals(saved, initialModelConfig(true, debug, { saved }, { error("read defaults") }))
            }
        }
        assertEquals(config, initialModelConfig(false, true, { error("read saved") }, { config }))
        assertEquals(ModelConfig(), initialModelConfig(false, false, { error("read saved") }, { error("read defaults") }))
        assertNotNull(emptyKey.validationError(true))
        assertNotNull(config.copy(model = "").validationError(true))
        assertNotNull(config.copy(apiKey = "key\r\nother").validationError(true))
        assertNull(config.validationError(false))
    }

    @Test fun savingInvalidatesBothPendingAndReturningRequests() {
        val requests = RecommendationRequests(config)
        val waiting = PendingRecommendationFrame()
        val old = requests.begin(0)
        waiting.await(old)
        val next = config.copy(model = "other")
        requests.useConfig(next)
        waiting.await(null)
        assertNull(waiting.forCapture(1))
        assertNull(requests.active)
        val live = requests.begin(2)
        assertEquals(config, old.config)
        assertEquals(next, live.config)
        assertEquals(RecommendationRequests.Completion.OBSOLETE, requests.finish(old, 3))
        assertEquals(RecommendationRequests.Completion.ACCEPT, requests.finish(live, 4))
        val same = requests.begin(5)
        requests.useConfig(next)
        assertEquals(RecommendationRequests.Completion.OBSOLETE, requests.finish(same, 6))
    }

    @Test fun recordsUseOnlyTheHostAndRedactEvenEchoedKeys() {
        val echoes = config.copy(model = "vision-${config.apiKey}")
        val record = echoes.recordText("outcome=ACCEPTED", true)
        assertTrue(record.contains("host=studio.example"))
        assertTrue(record.contains("model=vision-"))
        assertFalse(record.contains("private"))
        assertFalse(record.contains(config.apiKey))
        assertFalse(echoes.safeText("returned ${config.apiKey}").contains(config.apiKey))
        assertFalse(echoes.toString().contains(config.apiKey))
        assertFalse(RecommendationRequests(echoes).begin(0).toString().contains(config.apiKey))
    }

    @Test fun testConnectionClassifiesTransportHttpAndReplyFailures() {
        for (error in listOf(ConnectException(), SocketTimeoutException(), java.net.UnknownHostException())) {
            assertEquals(ConnectionResult.UNREACHABLE, ConnectionResult.fromFailure(error))
        }
        val codes = mapOf(401 to ConnectionResult.AUTH, 403 to ConnectionResult.AUTH,
            400 to ConnectionResult.FORMAT, 415 to ConnectionResult.FORMAT, 422 to ConnectionResult.FORMAT,
            408 to ConnectionResult.UNREACHABLE, 504 to ConnectionResult.UNREACHABLE,
            429 to ConnectionResult.SERVICE, 503 to ConnectionResult.SERVICE)
        for ((code, expected) in codes) {
            val failure = ModelFailure(ConnectionResult.httpFailure(code), "HTTP $code", code)
            assertEquals(expected, ConnectionResult.fromFailure(failure))
        }
        val fields = mapOf("subject" to mapOf("name" to "苹果", "kind" to "object",
            "box" to listOf(200,200,800,800), "point" to listOf(500,500)), "alreadyGood" to true,
            "aimPoint" to mapOf("x" to 0.5, "y" to 0.5), "confidence" to 0.9, "reason" to "苹果居中")
        fun check(expected: ConnectionResult, reply: ModelReply?) = assertEquals(expected, ConnectionResult.fromReply(reply))
        check(ConnectionResult.SUCCESS, ModelReply.from(fields, true))
        check(ConnectionResult.SUCCESS, ModelReply.from(fields + ("confidence" to 0.4), true))
        check(ConnectionResult.FORMAT, null)
        check(ConnectionResult.FORMAT, ModelReply.from(fields, false))
        check(ConnectionResult.FORMAT, ModelReply.from(fields - "reason", true))
    }
}
