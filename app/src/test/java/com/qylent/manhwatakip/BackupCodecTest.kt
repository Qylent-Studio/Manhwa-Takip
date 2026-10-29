package com.qylent.manhwatakip

import java.io.ByteArrayInputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    private val entry = Series(
        id = "22222222-2222-4222-8222-222222222222", title = "Çılgın Okur 🐉",
        url = "https://example.com/48", lastChapter = "48.5", status = ReadingStatus.PAUSED,
        opinion = Opinion.LIKED, likedReason = "Karakterler", dislikedReason = "Yavaş tempo",
        dropReason = "Ara veriyorum", notes = "Yeniden okumak\nİkinci satır", createdAt = 1000, updatedAt = 2000,
    )

    @Test fun preservesEveryFieldAndUnicode() {
        val json = BackupCodec.encode(listOf(entry), 3000)
        assertEquals(listOf(entry), BackupCodec.decode(json))
        assertEquals(listOf(entry), BackupCodec.read(ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))))
        assertEquals(2, JSONObject(json).getInt("version"))
        assertFalse(json.contains("apiKey"))
    }

    @Test fun rejectsFutureVersionAndWrongFieldTypes() {
        val original = BackupCodec.encode(listOf(entry), 3000)
        val future = JSONObject(original).put("version", 5).toString()
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(future) }
        val wrong = JSONObject(original)
        wrong.getJSONArray("records").getJSONObject(0).put("title", 123)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(wrong.toString()) }
    }

    @Test fun rejectsMalformedAndPartialBackups() {
        listOf("", "{", "[]", "{}", "{\"format\":\"other\",\"version\":1,\"records\":[]}").forEach { json ->
            assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(json) }
        }
        val json = JSONObject(BackupCodec.encode(listOf(entry, entry.copy(id = "33333333-3333-4333-8333-333333333333", title = "Diğer")), 3000))
        for (field in listOf("status", "opinion")) {
            val invalid = JSONObject(json.toString())
            invalid.getJSONArray("records").getJSONObject(1).put(field, "UNKNOWN".repeat(1000))
            val error = assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(invalid.toString()) }
            assertTrue(error.message.orEmpty().startsWith("Yedekte"))
            assertTrue(error.message.orEmpty().length < 120)
        }
    }

    @Test fun rejectsDuplicateIdsAndDuplicateSeries() {
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(listOf(entry, entry), 3000) }
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.encode(listOf(entry, entry.copy(id = "33333333-3333-4333-8333-333333333333")), 3000)
        }
    }

    @Test fun rejectsOversizedAndInvalidUtf8File() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.read(ByteArrayInputStream(ByteArray(8 * 1024 * 1024 + 1) { 32 }))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)))
        }
    }

    @Test fun emptyBackupIsValidAndNeverMeansClear() {
        assertEquals(emptyList<Series>(), BackupCodec.decode(BackupCodec.encode(emptyList(), 3000)))
    }

    @Test fun rejectsInvalidIdentityAndTimestamps() {
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(listOf(entry.copy(id = "1-1-1-1-1")), 3000) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(listOf(entry.copy(updatedAt = -1)), 3000) }
    }
}
