package com.qylent.manhwatakip

import java.net.URL
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.*
import org.junit.Test

class AiClientTest {
    class Wire(val code: Int, val body: String) : HttpsURLConnection(URL("https://fixture.invalid/v1/chat/completions")) {
        val sent = ByteArrayOutputStream()
        override fun getOutputStream() = sent
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        override fun getResponseCode() = code
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
    @Test fun completionUsesConfiguredModelAndNeverPlacesKeyInBody() {
        val wire = Wire(200, """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"{\"message\":\"Yanıt\",\"changes\":[]}"}}]}""")
        val reply = AiClient { wire }.chat(AiSettings("https://fixture.invalid/v1", "chosen"), "test-key-only", "Öneri ver", emptyList())
        assertEquals("Yanıt", reply.message)
        assertTrue(wire.sent.toString("UTF-8").contains("chosen"))
        assertFalse(wire.sent.toString("UTF-8").contains("test-key-only"))
        assertEquals("Bearer test-key-only", wire.getRequestProperty("Authorization"))
        assertFalse(wire.instanceFollowRedirects)
    }
    @Test fun redirectsAuthenticationRateLimitAndTruncatedOutputNeverBecomeProposals() {
        for (status in listOf(302, 401, 403, 429, 503)) {
            val wire = Wire(status, "server-supplied-sensitive-content")
            val error = assertThrows(IllegalArgumentException::class.java) { AiClient { wire }.chat(AiSettings("https://fixture.invalid/v1", "chosen"), "test-key-only", "test", emptyList()) }
            assertFalse(error.message!!.contains("server-supplied-sensitive-content"))
        }
        val wire = Wire(200, """{"choices":[{"finish_reason":"length","message":{"content":"{\"message\":\"x\",\"changes\":[]}"}}]}""")
        assertThrows(IllegalArgumentException::class.java) { AiClient { wire }.chat(AiSettings("https://fixture.invalid/v1", "chosen"), "test-key-only", "test", emptyList()) }
    }

    @Test fun optionalLocalKeyNeverCreatesEmptyAuthorizationAndHttpsStillRequiresKey() {
        val wire = Wire(200, """{"choices":[{"finish_reason":"stop","message":{"content":"{\"message\":\"OK\",\"changes\":[]}"}}]}""")
        var requested: URL? = null
        val local = "http://192.0.2.10:20128/v1"
        AiClient(local) { url -> requested = url; wire }.chat(AiSettings(local, "fixture"), "", "test", emptyList())
        assertEquals("$local/chat/completions", requested.toString())
        assertNull(wire.getRequestProperty("Authorization"))
        val rejectingClient = AiClient("") { fail("Empty configuration must reject before transport"); wire }
        assertThrows(IllegalArgumentException::class.java) { rejectingClient.chat(AiSettings(local, "fixture"), "", "test", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            AiClient(local) { fail("Mismatched endpoint must reject before transport"); wire }
                .chat(AiSettings("http://192.0.2.11:20128/v1", "fixture"), "", "test", emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) { AiClient { wire }.chat(AiSettings("https://fixture.invalid/v1", "chosen"), "", "test", emptyList()) }
    }
    @Test fun coverCompletionCarriesImageAndRejectsOversizedRequestBeforeConnecting() {
        val wire = Wire(200, """{"choices":[{"finish_reason":"stop","message":{"content":"{\"language\":\"tr\"}"}}]}""")
        val messages = org.json.JSONArray().put(org.json.JSONObject().put("role", "user").put("content",
            org.json.JSONArray().put(org.json.JSONObject().put("type", "image_url").put("image_url", org.json.JSONObject().put("url", "data:image/jpeg;base64,AQ==")))))
        assertEquals("{\"language\":\"tr\"}", AiClient { wire }.complete(AiSettings("https://fixture.invalid/v1", "chosen"), "test-key-only", messages))
        val request = org.json.JSONObject(wire.sent.toString("UTF-8"))
        assertTrue(request.getJSONArray("messages").getJSONObject(0).get("content") is org.json.JSONArray)
        assertFalse(request.toString().contains("test-key-only"))
        assertThrows(IllegalArgumentException::class.java) {
            AiClient { fail("Büyük istek bağlanmamalı"); wire }.complete(AiSettings("https://fixture.invalid/v1", "chosen"), "test-key-only",
                org.json.JSONArray().put(org.json.JSONObject().put("role", "user").put("content", "x".repeat(512 * 1024))))
        }
    }
}
