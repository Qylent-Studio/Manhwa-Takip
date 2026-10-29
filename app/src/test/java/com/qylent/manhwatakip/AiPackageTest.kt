package com.qylent.manhwatakip

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiPackageTest {
    @Test fun profileAndHistoryAreLimitedToSelectedSeries() {
        val selected = Series(title = "Türkçe", status = ReadingStatus.READING, lastChapter = "3", notes = "Not", categories = listOf("Seçilen"))
        val other = Series(title = "Diğer")
        val changes = listOf(ProgressChange(id = java.util.UUID.randomUUID().toString(), seriesId = selected.id, before = "2", after = "3", at = 10), ProgressChange(id = java.util.UUID.randomUUID().toString(), seriesId = other.id, before = "1", after = "2", at = 11))
        val files = AiPackage.files(listOf(selected), listOf(CategoryDefinition("Seçilen"), CategoryDefinition("Özel diğer", position = 1)), changes, ChatGptMode.SERIES)
        val root = JSONObject(String(files.getValue("context.json")))
        assertEquals(1, root.getJSONArray("readingHistory").length()); assertEquals(1, root.getJSONArray("categoryDefinitions").length())
        assertEquals(1, root.getJSONObject("profile").getInt("Okunuyor")); assertFalse(root.has("chats")); assertFalse(root.has("keys"))
        assertTrue(String(files.getValue("profile.md")).contains("Türkçe")); assertEquals(1, ChatGptTransfer.preview(root.toString(), listOf(selected)).unchanged)
    }
}
