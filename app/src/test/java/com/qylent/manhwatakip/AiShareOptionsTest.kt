package com.qylent.manhwatakip

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiShareOptionsTest {
    @Test fun actualZipMembersKeepReasonsIndependentFromNotesAndStatus() {
        listOf(emptySet(), setOf(AiShareField.REASONS), setOf(AiShareField.NOTES)).forEach { fields ->
            val files = AiPackage.files(records, categories, history, ChatGptMode.SERIES, AiShareOptions(fields, setOf(records.first().id)))
            java.util.zip.ZipInputStream(PortableFiles.zip(files).inputStream()).use { archive ->
                var members = 0
                while (archive.nextEntry != null) {
                    members++
                    val text = archive.readBytes().toString(Charsets.UTF_8)
                    assertFalse(text.contains("OTHER_NOTE")); assertFalse(text.contains("HISTORY_SENTINEL"))
                    assertFalse(text.contains("Durum:")); assertFalse(text.contains("Okunuyor"))
                    if (AiShareField.NOTES !in fields) assertFalse(text.contains(records.first().notes))
                    if (AiShareField.REASONS !in fields) assertFalse(text.contains(records.first().likedReason))
                }
                assertEquals(3, members)
            }
        }
    }
    @Test fun richPackageStillRejectsMoreThanFourMiB() {
        val many = (0..1100).map { records.first().copy(id = java.util.UUID.randomUUID().toString(), title = "Fixture $it", notes = "N".repeat(4000)) }
        try {
            AiPackage.files(many, emptyList(), emptyList(), ChatGptMode.SERIES, AiShareOptions(setOf(AiShareField.NOTES)))
            fail("4 MiB boundary must be enforced")
        } catch (_: IllegalArgumentException) { }
        assertTrue(AiPackage.files(many, emptyList(), emptyList(), ChatGptMode.SERIES, AiShareOptions(emptySet())).getValue("context.json").size < ChatGptTransfer.MAX_BYTES)
    }
    private val fixture = JSONObject(javaClass.getResource("/ai-share/contract.json")!!.readText())
    private val records = (0 until fixture.getJSONArray("records").length()).map { index ->
        val row = fixture.getJSONArray("records").getJSONObject(index)
        fun strings(key: String) = row.getJSONArray(key).let { array -> (0 until array.length()).map { array.getString(it) } }
        Series(id = row.getString("id"), title = row.getString("title"), url = row.getString("url"),
            lastChapter = row.getString("lastChapter"), status = ReadingStatus.valueOf(row.getString("status")),
            opinion = Opinion.valueOf(row.getString("opinion")), likedReason = row.getString("likedReason"),
            dislikedReason = row.getString("dislikedReason"), dropReason = row.getString("dropReason"), notes = row.getString("notes"),
            categories = strings("categories"), alternativeTitles = strings("alternativeTitles"),
            favorite = row.getBoolean("favorite"), pinned = row.getBoolean("pinned"), createdAt = 1, updatedAt = 2)
    }
    private val categories = listOf(CategoryDefinition("CATEGORY_SENTINEL", "blue", 0), CategoryDefinition("UNSELECTED_CATEGORY", "red", 1))
    private val history = (0 until fixture.getJSONArray("history").length()).map {
        val row = fixture.getJSONArray("history").getJSONObject(it)
        ProgressChange(row.getString("id"), row.getString("seriesId"), row.getString("before"), row.getString("after"), row.getLong("at"), null)
    }
    private fun root(files: Map<String, ByteArray>) = JSONObject(files.getValue("context.json").toString(Charsets.UTF_8))

    @Test fun sharedFieldCasesProjectExactKeysInBothExportPaths() {
        val cases = fixture.getJSONArray("cases")
        repeat(cases.length()) { index ->
            val case = cases.getJSONObject(index); val fields = case.getJSONArray("fields")
            val options = AiShareOptions((0 until fields.length()).map { AiShareField.valueOf(fields.getString(it)) }.toSet(), setOf(records.first().id))
            val direct = JSONObject(ChatGptTransfer.export(records, ChatGptMode.SERIES, options))
            val packaged = root(AiPackage.files(records, categories, history, ChatGptMode.SERIES, options))
            listOf(direct, packaged).forEach { output ->
                assertEquals("manhwa-chatgpt", output.getString("format")); assertEquals(1, output.getInt("version"))
                assertEquals(1, output.getJSONArray("series").length())
                val expected = case.getJSONObject("expected"); val actual = output.getJSONArray("series").getJSONObject(0)
                assertEquals("fields=$fields", expected.keys().asSequence().toSet(), actual.keys().asSequence().toSet())
                expected.keys().forEach { key ->
                    val value = expected.get(key)
                    if (value is org.json.JSONArray) assertEquals("fields=$fields key=$key", value.toString(), actual.getJSONArray(key).toString())
                    else assertEquals("fields=$fields key=$key", value, actual.get(key))
                }
            }
        }
    }

    @Test fun titleOnlyArchiveCannotLeakDeselectedValuesInAnyMode() {
        ChatGptMode.entries.forEach { mode ->
            val files = AiPackage.files(records, categories, history, mode, AiShareOptions(emptySet()))
            val output = root(files)
            listOf("profile", "readingHistory", "categoryDefinitions").forEach { assertFalse(output.has(it)) }
            val text = files.values.joinToString { it.toString(Charsets.UTF_8) }
            listOf("SENTINEL", "OTHER_NOTE", records.first().id, "https://example.org/read", "Kayıtlı bölüm değişikliği", "Durum:", "son bölüm:").forEach {
                assertFalse("Leaked $it in $mode", text.contains(it))
            }
            assertEquals("# Okuma profilim\n\n## Sentetik 雪\n\n## Diğer sentetik\n\n", files.getValue("profile.md").toString(Charsets.UTF_8))
        }
    }

    @Test fun emptyIdsMeansNoRecordsAndSelectionIntersectsCallerFilter() {
        val empty = AiShareOptions(emptySet(), emptySet())
        assertEquals(0, JSONObject(ChatGptTransfer.export(records, ChatGptMode.SERIES, empty)).getJSONArray("series").length())
        assertEquals(0, root(AiPackage.files(records, categories, history, ChatGptMode.SERIES, empty)).getJSONArray("series").length())
        val selected = AiShareOptions(emptySet(), setOf(records.last().id))
        val filtered = records.filter { it.status == ReadingStatus.READING }
        assertEquals(0, root(AiPackage.files(filtered, categories, history, ChatGptMode.SERIES, selected)).getJSONArray("series").length())
        assertEquals(2, JSONObject(ChatGptTransfer.export(records, ChatGptMode.SERIES, AiShareOptions(emptySet()))).getJSONArray("series").length())
    }

    @Test fun historyCategoriesAndStatusAggregatesAreOptInAndSelectionScoped() {
        val options = AiShareOptions(setOf(AiShareField.HISTORY, AiShareField.CATEGORIES, AiShareField.STATUS), setOf(records.first().id))
        val output = root(AiPackage.files(records, categories, history, ChatGptMode.SERIES, options))
        assertEquals(1, output.getJSONArray("readingHistory").length())
        assertEquals(records.first().id, output.getJSONArray("readingHistory").getJSONObject(0).getString("seriesId"))
        assertEquals(1, output.getJSONArray("categoryDefinitions").length())
        assertEquals(1, output.getJSONObject("profile").getInt("Okunuyor"))
        assertEquals(0, output.getJSONObject("profile").getInt("Okunacak"))
        val historyOnly = root(AiPackage.files(records, categories, history, ChatGptMode.SERIES, AiShareOptions(setOf(AiShareField.HISTORY))))
        assertFalse(historyOnly.has("profile")); assertFalse(historyOnly.has("categoryDefinitions"))
    }

    @Test fun legacyOverloadsRetainIdentityAndLocalMetadata() {
        val direct = JSONObject(ChatGptTransfer.export(records, ChatGptMode.SERIES)).getJSONArray("series").getJSONObject(0)
        assertEquals(records.first().id, direct.getString("id")); assertTrue(direct.getBoolean("favorite")); assertTrue(direct.has("alternativeTitles"))
        val output = root(AiPackage.files(records, categories, history, ChatGptMode.SERIES))
        assertTrue(output.has("profile")); assertEquals(2, output.getJSONArray("readingHistory").length())
    }

    @Test fun recommendationExampleRemainsImportableAndUnread() {
        val output = root(AiPackage.files(records, categories, history, ChatGptMode.RECOMMEND, AiShareOptions(emptySet())))
        val example = output.getJSONObject("responseFormat")
        val row = example.getJSONArray("series").getJSONObject(0)
        assertEquals("PLANNED", row.getString("status")); assertEquals("", row.getString("lastChapter"))
        assertEquals(ReadingStatus.PLANNED, ChatGptTransfer.preview(example.toString(), emptyList()).changes.single().after.status)
    }
}
