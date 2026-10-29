package com.qylent.manhwatakip

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class SelectedFieldsTest {
    @Test fun localFieldsRoundTripWithoutChangingMcpProjection() {
        val entry = Series(title = "Asıl ad", alternativeTitles = listOf("İkinci Ad"),
            favorite = true, pinned = true, coverUrl = "https://golgebahcesi.com/cover.jpg")
        val local = BackupCodec.encodeLibrary(listOf(entry), emptyList())
        assertEquals(entry, BackupCodec.decode(local).single())
        assertFalse(JSONObject(BackupCodec.encode(listOf(entry))).getJSONArray("records").getJSONObject(0).has("favorite"))
        assertEquals(2, JSONObject(BackupCodec.encode(listOf(entry))).getInt("version"))
        assertTrue(entry.matches("ikinci"))
        assertEquals(emptyList<String>(), BackupCodec.decode(BackupCodec.encode(listOf(entry))).single().alternativeTitles)
    }
    @Test fun malformedAlternativeNamesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { Series(title = "A", alternativeTitles = listOf("X", "x")).normalized() }
        assertThrows(IllegalArgumentException::class.java) { Series(title = "A", alternativeTitles = listOf("a\n")).normalized() }
        assertThrows(IllegalArgumentException::class.java) { Series(title = "A", coverUrl = "file:///private").normalized() }
    }
}
