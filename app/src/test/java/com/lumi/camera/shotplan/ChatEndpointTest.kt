package com.lumi.camera.shotplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatEndpointTest {
    @Test fun onlyDebugCanUseTheUsbLoopbackEndpoint() {
        val base = "http://127.0.0.1:3425/v1"
        assertEquals("$base/chat/completions", chatEndpoint(base, debug = true)?.toString())
        assertNull(chatEndpoint(base, debug = false))
        for (url in listOf("http://192.168.1.1/v1", "http://127.0.0.1.example/v1",
            "http://127.0.0.1@other.example/v1", "http://user@127.0.0.1:3425/v1",
            "$base?key=example", "$base#fragment")) assertNull(chatEndpoint(url, debug = true))
    }

    @Test fun httpsStillAcceptsBothBaseAndFullEndpoint() {
        val expected = "https://model.example/v1/chat/completions"
        for (base in listOf("https://model.example", "https://model.example/v1/", expected)) {
            assertEquals(expected, chatEndpoint(base, debug = false)?.toString())
        }
    }
}
