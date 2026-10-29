package com.qylent.manhwatakip

import org.junit.Assert.*
import org.junit.Test

class AiProtocolTest {
    private val original = Series(title = "Kule", lastChapter = "10", notes = "Kişisel not", likedReason = "Dünya")

    @Test fun patchPreservesUnmentionedFieldsAndSnapshot() {
        val reply = AiProtocol.decode("""{"message":"İlerlemeni hazırladım","changes":[{"seriesId":"${original.id}","fields":{"lastChapter":"12,5","status":"READING"}}]}""", listOf(original))
        assertEquals("12.5", reply.proposals.single().after.lastChapter)
        assertEquals("Kişisel not", reply.proposals.single().after.notes)
        assertEquals("Dünya", reply.proposals.single().after.likedReason)
        assertEquals(original, reply.proposals.single().before)
        assertEquals("10", original.lastChapter)
    }

    @Test fun unknownIdFieldInvalidEnumAndWrongTypeRejectWholeReply() {
        val changes = listOf(
            """{"seriesId":"00000000-0000-0000-0000-000000000000","fields":{"title":"Yanlış"}}""",
            """{"seriesId":"${original.id}","fields":{"apiKey":"secret"}}""",
            """{"seriesId":"${original.id}","fields":{"status":"UNKNOWN"}}""",
            """{"seriesId":"${original.id}","fields":{"lastChapter":12}}"""
        )
        changes.forEach { bad -> assertThrows(IllegalArgumentException::class.java) {
            AiProtocol.decode("""{"message":"x","changes":[{"seriesId":null,"fields":{"title":"Yeni"}},$bad]}""", listOf(original))
        } }
    }

    @Test fun candidatesRemainSeparateAndMalformedResponsesAreRejected() {
        val reply = AiProtocol.decode("""{"message":"Adayları doğrula","changes":[{"seriesId":null,"fields":{"title":"Aday 1"}},{"seriesId":null,"fields":{"title":"Aday 2"}}]}""", emptyList())
        assertEquals(listOf("Aday 1", "Aday 2"), reply.proposals.map { it.after.title })
        assertNotEquals(reply.proposals[0].after.id, reply.proposals[1].after.id)
        listOf("{}", "not json", """{"message":"x","changes":{}}""").forEach {
            assertThrows(IllegalArgumentException::class.java) { AiProtocol.decode(it, emptyList()) }
        }
    }

    @Test fun trailingContentIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { AiProtocol.decode("""{"message":"x","changes":[]} garbage""", emptyList()) }
    }

    @Test fun settingsRejectCredentialUrlsAndInsecureEndpoints() {
        listOf("http://example.com/v1", "https://key@example.com", "https://example.com/?key=a", "https://example.com/#key", "https://example.com:0").forEach {
            assertThrows(IllegalArgumentException::class.java) { AiSettings(it, "model").validated() }
        }
        assertEquals("https://example.com/v1/chat/completions", AiSettings("https://example.com/v1/", "model").validated().endpoint)
        assertThrows(IllegalArgumentException::class.java) { AiSettings("https://example.com/v1", "").validated() }
    }

    @Test fun requestContainsSelectedSnapshotAndEditableUserTextOnly() {
        val request = org.json.JSONObject(AiProtocol.request(AiSettings("https://example.com/v1", "my-model"), "Kule'yi 12'ye güncelle", listOf(original)))
        assertEquals("my-model", request.getString("model"))
        assertFalse(request.toString().contains("Authorization"))
        assertTrue(request.getJSONArray("messages").getJSONObject(1).getString("content").contains("Kişisel not"))
        assertTrue(request.getJSONArray("messages").getJSONObject(1).getString("content").contains("Kule'yi 12'ye güncelle"))
    }

    @Test fun localHttpExceptionPinsExactAddressPortAndPath() {
        val configured = "http://192.0.2.10:20128/v1"
        assertEquals("$configured/chat/completions", AiSettings("$configured/", "model").validated(configured).endpoint)
        listOf("http://192.0.2.11:20128/v1", "http://192.0.2.10:20129/v1", "http://192.0.2.10/v1", "http://192.0.2.10:20128/other", "$configured?key=a", "http://key@192.0.2.10:20128/v1").forEach {
            assertThrows(IllegalArgumentException::class.java) { AiSettings(it, "model").validated(configured) }
        }
        assertThrows(IllegalArgumentException::class.java) { AiSettings(configured, "model").validated("") }
        assertFalse(AiSettings("", "model").isLocalHttp)
    }
}
