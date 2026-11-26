package com.lumi.camera.shotplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatEndpointTest {
    @Test fun debugAcceptsLoopbackAndLanButReleaseRequiresHttps() {
        for (base in listOf("http://127.0.0.1:3425/v1", "http://192.168.1.2:3425/v1", "http://studio.local/v1")) {
            assertEquals("$base/chat/completions", chatEndpoint(base, debug = true)?.toString())
            assertNull(chatEndpoint(base, debug = false))
        }
    }

    @Test fun rejectsMalformedAddressesAndEmbeddedCredentials() {
        for (url in listOf("", "localhost/v1", "ftp://host/v1", "https:///v1", "https://host name/v1",
            "https://name@host/v1", "http://127.0.0.1@other.example/v1", "https://host/v1?key=example",
            "https://host/v1#fragment", "https://host:0/v1", "https://host:65536/v1", "https://host:/v1",
            "https://host:bad/v1", "https://host/\nv1")) {
            assertNull(url, chatEndpoint(url, debug = true))
        }
    }

    @Test fun httpsAcceptsRootBaseAndFullEndpoint() {
        val expected = "https://model.example/v1/chat/completions"
        for (base in listOf("https://model.example", "https://model.example/v1/", expected, "$expected/")) {
            assertEquals(expected, chatEndpoint(base, debug = false)?.toString())
        }
        assertEquals("https://host/proxy/v1/chat/completions",
            chatEndpoint("https://host/proxy/v1", debug = false)?.toString())
    }

    @Test fun disablesDefaultHighReasoningForDeepSeekFlashAndV4Models() {
        for (model in listOf("deepseek-flash", "workbuddy/deepseek-v4.1-flash", "deepseek-v4-pro", "deepseek-v4-flash")) {
            assertTrue(model, ChatProtocol.disablesReasoning(model))
        }
        for (model in listOf("gpt-4o-mini", "qwen-vl-plus", "gemini-2.5-flash")) {
            assertFalse(model, ChatProtocol.disablesReasoning(model))
        }
    }
}
