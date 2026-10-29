package com.qylent.manhwatakip

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BridgeClientTest {
    private class Wire(val code: Int, val body: String = "{}") : HttpURLConnection(URL("http://127.0.0.1:8765/health")) {
        val sent = ByteArrayOutputStream()
        override fun getOutputStream() = sent
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
        override fun getResponseCode() = code
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun connect() = Unit
    }
    private val key = "fixture-key-" + "x".repeat(32)
    @Test fun healthAuthenticatesWithoutLibraryOrSharing() {
        val wire = Wire(200, """{"server":"manhwa-usb-bridge","pid":42}""")
        var requested: URL? = null
        BridgeClient { url -> requested = url; wire }.check(key)
        assertEquals("/health", requested!!.path)
        assertEquals("{}", wire.sent.toString("UTF-8"))
        assertEquals("Bearer $key", wire.getRequestProperty("Authorization"))
        assertFalse(wire.instanceFollowRedirects)
        assertThrows(IllegalArgumentException::class.java) { BridgeClient { Wire(200, "{}") }.check(key) }
    }
    @Test fun errorsDistinguishAuthenticationAccessInputAndTransportWithoutEchoingServerData() {
        val messages = listOf(401, 403, 400, 503).map { code ->
            assertThrows(IllegalArgumentException::class.java) { BridgeClient { Wire(code, "sensitive-server-data") }.check(key) }.message!!
        }
        assertEquals(4, messages.distinct().size)
        assertTrue(messages[0].contains("eşleşmiyor"))
        assertTrue(messages[1].contains("erişimi engelledi"))
        assertTrue(messages[2].contains("verisini"))
        assertFalse(messages.any { it.contains("sensitive-server-data") })
        val error = assertThrows(IllegalArgumentException::class.java) { BridgeClient { throw java.net.ConnectException() }.check(key) }
        assertTrue(error.message!!.contains("ulaşılamadı"))
    }
    @Test fun syncStillUsesVersionTwoWithoutVisualMetadata() {
        val wire = Wire(200, """{"proposals":[],"lastSync":1}""")
        BridgeClient { wire }.sync(key, listOf(Series(title = "Test", categories = listOf("Macera"))), emptyMap())
        val library = JSONObject(wire.sent.toString("UTF-8")).getJSONObject("library")
        assertEquals(2, library.getInt("version"))
        assertFalse(library.has("categoryDefinitions"))
    }
}
