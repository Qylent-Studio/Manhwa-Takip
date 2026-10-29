package com.qylent.manhwatakip

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class ChatGptTransferTest {
    @Test fun unknownVersionCannotHideBehindMissingFormat() {
        assertThrows(IllegalArgumentException::class.java) {
            ChatGptTransfer.preview("""{"version":99,"series":[{"title":"New"}]}""", emptyList())
        }
    }
    @Test fun recommendationsDefaultToPlannedAndOmissionsKeepExistingCoverAndNotes() {
        val old = existing.copy(coverUrl = "https://example.com/cover.jpg")
        val plan = ChatGptTransfer.preview("""[{"id":"${old.id}","url":"https://example.com/changed"},{"title":"New"}]""", listOf(old))
        assertEquals(old.copy(url = "https://example.com/changed"), plan.changes.first().after)
        assertEquals(ReadingStatus.PLANNED, plan.changes.last().after.status)
        assertEquals("", plan.changes.last().after.lastChapter)
        assertThrows(IllegalArgumentException::class.java) {
            ChatGptTransfer.preview("""[{"title":"Gölge"},{"title":"Shadow"}]""", listOf(old))
        }
        assertThrows(IllegalArgumentException::class.java) { ChatGptTransfer.preview(" ".repeat(ChatGptTransfer.MAX_BYTES + 1), listOf(old)) }
    }
    private val existing = Series(title = "Gölge", url = "https://example.com/shadow", lastChapter = "12",
        status = ReadingStatus.READING, opinion = Opinion.LIKED, notes = "Korunacak not", categories = listOf("Bir"),
        alternativeTitles = listOf("Shadow"), favorite = true, pinned = true, createdAt = 1000, updatedAt = 2000)

    @Test fun plainExportContainsReadingContextAndNoPromptOrCredentials() {
        val root = JSONObject(ChatGptTransfer.export(listOf(existing), ChatGptMode.SERIES))
        assertEquals("manhwa-chatgpt", root.getString("format")); assertFalse(root.has("prompt"))
        val row = root.getJSONArray("series").getJSONObject(0)
        assertEquals("12", row.getString("lastChapter")); assertEquals("READING", row.getString("status"))
        assertEquals("Korunacak not", row.getString("notes")); assertEquals("LIKED", row.getString("opinion"))
        assertFalse(root.toString().contains("apiKey")); assertFalse(root.toString().contains("bridgeKey"))
        assertEquals(1, ChatGptTransfer.preview(root.toString(), listOf(existing)).unchanged)
    }
    @Test fun recommendationAndSummaryPromptsArePurposeSpecific() {
        val recommendations = JSONObject(ChatGptTransfer.export(listOf(existing), ChatGptMode.RECOMMEND))
        val prompt = recommendations.getString("prompt")
        assertTrue(prompt.contains("ücretsiz")); assertTrue(prompt.contains("tekrar önerme")); assertTrue(prompt.contains("uydurma"))
        assertTrue(prompt.contains("Gölge Bahçesi")); assertTrue(prompt.contains("OkuToon"))
        assertEquals("manhwa-chatgpt", recommendations.getJSONObject("responseFormat").getString("format"))
        val summary = JSONObject(ChatGptTransfer.export(listOf(existing), ChatGptMode.SUMMARIZE)).getString("prompt")
        assertTrue(summary.contains("spoiler")); assertTrue(summary.contains("lastChapter")); assertTrue(summary.contains("yeni seri önerme"))
        assertFalse(JSONObject(ChatGptTransfer.export(listOf(existing), ChatGptMode.SUMMARIZE)).has("responseFormat"))
    }
    @Test fun partialImportUsesIdUrlOrUniqueAliasAndKeepsUnsentFields() {
        val incoming = """{"series":[{"id":"${existing.id}","lastChapter":13.5,"url":"https://example.com/new"}]}"""
        val plan = ChatGptTransfer.preview(incoming, listOf(existing))
        val change = plan.changes.single()
        assertEquals(existing, change.before)
        assertEquals(existing.copy(lastChapter = "13.5", url = "https://example.com/new"), change.after)
        val alias = ChatGptTransfer.preview("""{"title":"Shadow","lastChapter":"14"}""", listOf(existing)).changes.single()
        assertEquals(existing.id, alias.after.id); assertEquals("Gölge", alias.after.title)
        val url = ChatGptTransfer.preview("""[{"url":"${existing.url}","lastChapter":"14"}]""", listOf(existing)).changes.single()
        assertEquals(existing.id, url.after.id)
    }
    @Test fun newSeriesHasNoInventedReadDateAndFencedJsonIsAccepted() {
        val plan = ChatGptTransfer.preview("```json\n{\"series\":[{\"title\":\"Yeni seri\",\"url\":\"https://example.com/new\",\"lastChapter\":\"48,5\",\"status\":\"Okunuyor\"}]}\n```", emptyList())
        val proposal = plan.changes.single()
        assertNull(proposal.before); assertEquals("48.5", proposal.after.lastChapter); assertEquals(ReadingStatus.READING, proposal.after.status)
        assertEquals(proposal.after.createdAt, proposal.after.updatedAt)
    }
    @Test fun ambiguousIdentityMalformedTailAndUnsafeLinksRejectWholePreview() {
        val other = existing.copy(id = "33333333-3333-4333-8333-333333333333", url = "https://example.com/other")
        listOf(
            """{"series":[{"title":"Gölge","lastChapter":"13"}]}""",
            """{"series":[{"id":"${existing.id}","url":"${other.url}","lastChapter":"13"}]}""",
            """{"series":[{"title":"Yeni"},{"title":"Son","url":"javascript:alert(1)"}]}""",
            """{"series":[{"title":"Yeni","lastChapter":null}]}""",
            """{"series":[{"title":"Yeni","lastChapter":-1}]}""",
            """{"series":[{"title":"Yeni","status":"UNKNOWN"}]}""",
            """{"format":"other","series":[]}""",
            """{"series":[{"id":"${existing.id}","lastChapter":"13"},{"id":"${existing.id}","lastChapter":"14"}]}""",
            """{"series":[{"title":123}]}""",
            """{"series":[{"title":"Yeni","url":"https://user:pass@example.com"}]}""",
        ).forEach { text -> assertThrows(IllegalArgumentException::class.java) { ChatGptTransfer.preview(text, listOf(existing, other)) } }
    }
    @Test fun untrustedMetadataCannotClearLocalFieldsAndIdClashesAreRejected() {
        val change = ChatGptTransfer.preview("""{"series":[{"id":"${existing.id}","lastChapter":"13","notes":"sil","categories":[],"favorite":false}]}""", listOf(existing)).changes.single()
        assertEquals(existing.notes, change.after.notes); assertEquals(existing.categories, change.after.categories); assertTrue(change.after.favorite)
        assertThrows(IllegalArgumentException::class.java) {
            ChatGptTransfer.preview("""{"id":"33333333-3333-4333-8333-333333333333","title":"Gölge","url":"${existing.url}"}""", listOf(existing))
        }
    }
    @Test fun strictUtf8LimitsAndTrailingContentAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ChatGptTransfer.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))) }
        assertThrows(IllegalArgumentException::class.java) { ChatGptTransfer.read(ByteArrayInputStream(ByteArray(ChatGptTransfer.MAX_BYTES + 1) { 32 })) }
        assertThrows(IllegalArgumentException::class.java) { ChatGptTransfer.preview("{\"title\":\"Yeni\"} trailing", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { ChatGptTransfer.preview("{\"series\":[]}", emptyList()) }
    }
    @Test fun nonStandardGrammarDuplicateKeysAndHiddenTailAreRejected() {
        listOf("{title:'Yeni',lastChapter:010}", "{\"title\":\"Yeni\",\"lastChapter\":010}",
            "{\"title\":\"Yeni\",\"lastChapter\":\"1\",\"lastChapter\":\"2\"}",
            "{\"title\":\"Yeni\",\"ti\\u0074le\":\"Diğer\"}", "{\"title\":\"Yeni\"}\u0000bad",
            "{\"title\":\"Yeni\",}", "{\"title\":\"Yeni\"} // explanation",
            "{\"title\":\"Yeni\",\"meta\":" + "[".repeat(65) + "1" + "]".repeat(65) + "}")
            .forEach { assertThrows(IllegalArgumentException::class.java) { ChatGptTransfer.preview(it, emptyList()) } }
    }
    @Test fun standardEscapesUnicodeAndNestedMetadataRemainValid() {
        val text = """{"title":"Türkçe 雪 🐉","lastChapter":1.5,"extra":{"number":1e2,"flag":true,"none":null,"list":["a\"b",false,{"escaped":"\u0041"}]}}"""
        assertEquals("Türkçe 雪 🐉", ChatGptTransfer.preview(text, emptyList()).changes.single().after.title)
        assertEquals("1.5", ChatGptTransfer.preview(text, emptyList()).changes.single().after.lastChapter)
    }
    @Test fun newBatchCannotRepeatTheSameSeriesAcrossTitlesOrLinks() {
        listOf("""[{"title":"A","url":"https://example.com/a"},{"title":"A","url":"https://example.com/b"}]""",
            """[{"title":"A","url":"https://example.com/a"},{"title":"B","url":"https://example.com/a"}]""")
            .forEach { assertThrows(IllegalArgumentException::class.java) { ChatGptTransfer.preview(it, emptyList()) } }
    }
}
