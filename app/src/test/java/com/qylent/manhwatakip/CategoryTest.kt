package com.qylent.manhwatakip

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class CategoryTest {
    @Test fun fullBackupPreservesUnusedCategoriesColorAndOrderAndRejectsInvalidMetadata() {
        val entry = Series(title = "Yedek", categories = listOf("Macera"))
        val definitions = listOf(CategoryDefinition("Boş", "rose", 0), CategoryDefinition("Macera", "blue", 1))
        val text = BackupCodec.encodeLibrary(listOf(entry), definitions)
        assertEquals(LibraryBackup(listOf(entry), definitions), BackupCodec.decodeLibrary(text))
        assertEquals(2, JSONObject(BackupCodec.encode(listOf(entry))).getInt("version"))
        for (color in listOf("invalid", "")) {
            val root = JSONObject(text)
            root.getJSONArray("categoryDefinitions").getJSONObject(0).put("colorId", color)
            assertThrows(IllegalArgumentException::class.java) { BackupCodec.decodeLibrary(root.toString()) }
        }
        val duplicate = JSONObject(text)
        duplicate.getJSONArray("categoryDefinitions").getJSONObject(1).put("position", 0)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decodeLibrary(duplicate.toString()) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encodeLibrary(listOf(entry), emptyList()) }
    }
    @Test fun versionThreeBackupRemainsReadableAsSeries() {
        val entry = Series(title = "Yeni yedek", categories = listOf("Macera"))
        val root = JSONObject(BackupCodec.encode(listOf(entry))).put("version", 3)
            .put("categoryDefinitions", JSONArray().put(JSONObject().put("name", "Macera").put("colorId", "blue").put("position", 0)))
        assertEquals(listOf(entry), BackupCodec.decode(root.toString()))
    }
    @Test fun customCategoriesSurviveBackupAndOldBackupsRemainReadable() {
        val entry = Series(title = "Kategori testi", categories = listOf("Harem", "Macera"))
        assertEquals(entry, BackupCodec.decode(BackupCodec.encode(listOf(entry))).single())
        val old = JSONObject(BackupCodec.encode(listOf(entry))).put("version", 1)
        old.getJSONArray("records").getJSONObject(0).remove("categories")
        assertTrue(BackupCodec.decode(old.toString()).single().categories.isEmpty())
        val wrong = JSONObject(BackupCodec.encode(listOf(entry)))
        wrong.getJSONArray("records").getJSONObject(0).put("categories", JSONArray().put(1))
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(wrong.toString()) }
    }
    @Test fun labelsNormalizeAndRejectDuplicatesOrInvalidNames() {
        assertEquals(listOf("Harem"), Series(title = "Test", categories = listOf(" Harem ")).normalized().categories)
        assertThrows(IllegalArgumentException::class.java) { Series(title = "Test", categories = listOf("IŞIK", "ışık")).normalized() }
        assertThrows(IllegalArgumentException::class.java) { Series(title = "Test", categories = listOf("\n")).normalized() }
    }
    @Test fun aiCategoryProposalRequiresReviewAndKeepsOtherFields() {
        val entry = Series(title = "Test", categories = listOf("Macera"))
        val reply = AiProtocol.decode("""{"message":"Öneri","changes":[{"seriesId":"${entry.id}","fields":{"categories":["Harem","Macera"]}}]}""", listOf(entry))
        assertEquals(listOf("Harem", "Macera"), reply.proposals.single().after.categories)
        assertEquals(entry, reply.proposals.single().before)
        assertTrue(AiProtocol.preview(reply.proposals.single()).contains("Özel kategoriler"))
        val comma = entry.copy(categories = listOf("Harem, Macera"))
        assertTrue(AiProtocol.preview(AiProposal(comma, comma.copy(categories = listOf("Harem", "Macera")))).contains("Özel kategoriler"))
    }
}
