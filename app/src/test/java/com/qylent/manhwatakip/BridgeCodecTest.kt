package com.qylent.manhwatakip

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class BridgeCodecTest {
    @Test fun originalSnapshotAndStableNewIdArePreserved() {
        val before = Series(title = "Kule", notes = "Eski not", lastChapter = "10")
        val after = before.copy(lastChapter = "20")
        fun json(entry: Series) = JSONObject(BackupCodec.encode(listOf(entry))).getJSONArray("records").getJSONObject(0)
        val response = JSONObject().put("lastSync", 5).put("proposals", org.json.JSONArray().put(
            JSONObject().put("token", "00000000-0000-0000-0000-000000000001").put("before", json(before)).put("after", json(after))))
        val pending = BridgeCodec.decode(response.toString()).proposals.single()
        assertEquals(before, pending.proposal.before)
        assertEquals("20", pending.proposal.after.lastChapter)
        assertEquals(before.id, pending.proposal.after.id)
    }
    @Test fun changedIdentityMalformedBatchAndDuplicateTokensRejectWholeResponse() {
        val before = Series(title = "A")
        fun json(entry: Series) = JSONObject(BackupCodec.encode(listOf(entry))).getJSONArray("records").getJSONObject(0)
        val item = JSONObject().put("token", "00000000-0000-0000-0000-000000000001").put("before", json(before)).put("after", json(Series(title = "B")))
        val root = JSONObject().put("lastSync", 5).put("proposals", org.json.JSONArray().put(item))
        assertThrows(IllegalArgumentException::class.java) { BridgeCodec.decode(root.toString()) }
        item.put("after", json(before.copy(notes = "Yeni")))
        root.put("proposals", org.json.JSONArray().put(item).put(item))
        assertThrows(IllegalArgumentException::class.java) { BridgeCodec.decode(root.toString()) }
    }
}
