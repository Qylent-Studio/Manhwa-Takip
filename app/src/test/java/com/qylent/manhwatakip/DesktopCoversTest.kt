package com.qylent.manhwatakip

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DesktopCoversTest {
    private val entry = Series(title = "Kalsın", notes = "Not korunur", lastChapter = "8", updatedAt = 4, createdAt = 1)
    private fun change(expected: Long = 4, url: String = "https://uploads.mangadex.org/covers/a/b.jpg") = JSONObject()
        .put("id", entry.id).put("expectedUpdatedAt", expected).put("imageUrl", url).put("jpeg", "AA==")
    @Test fun manualCoverIsLocalAndCannotSupplyRemoteFields() {
        val manual = JSONObject().put("id", entry.id).put("expectedUpdatedAt", 4).put("jpeg", "AA==")
        val cover = DesktopCovers.preview(JSONArray().put(manual), listOf(entry), manual = true).single()
        assertTrue(cover.replaceExisting)
        assertTrue(cover.imageUrl.startsWith("https://manhwa-takip.invalid/local-cover/"))
        assertEquals(entry.copy(coverUrl = cover.imageUrl), cover.proposal.after)
        assertThrows(IllegalArgumentException::class.java) { DesktopCovers.preview(JSONArray().put(manual), listOf(entry)) }
        assertThrows(IllegalArgumentException::class.java) { DesktopCovers.preview(JSONArray().put(manual.put("imageUrl", "https://okutoon.com/a.jpg")), listOf(entry), manual = true) }
    }
    @Test fun onlyCoverChangesAndConflictsRejectWholeBatch() {
        val covers = DesktopCovers.preview(JSONArray().put(change()), listOf(entry))
        assertEquals(entry.copy(coverUrl = covers.single().imageUrl), covers.single().proposal.after)
        assertThrows(IllegalArgumentException::class.java) { DesktopCovers.preview(JSONArray().put(change(5)), listOf(entry)) }
        assertThrows(IllegalArgumentException::class.java) { DesktopCovers.preview(JSONArray().put(change()).put(change()), listOf(entry)) }
        assertThrows(IllegalArgumentException::class.java) { DesktopCovers.preview(JSONArray().put(change()), emptyList()) }
    }
    @Test fun trustBoundaryRejectsExtraFieldsAndUnsafeCoverSource() {
        for (url in listOf("file:///a", "http://uploads.mangadex.org/a", "https://localhost/a", "https://uploads.mangadex.org:8765/a")) {
            assertThrows(IllegalArgumentException::class.java) { DesktopCovers.preview(JSONArray().put(change(url = url)), listOf(entry)) }
        }
        assertThrows(IllegalArgumentException::class.java) { DesktopCovers.preview(JSONArray().put(change().put("notes", "Overwrite")), listOf(entry)) }
        assertThrows(IllegalArgumentException::class.java) { DesktopCovers.preview(JSONArray().put(change().put("replaceExisting", "true")), listOf(entry)) }
        assertTrue(DesktopCovers.preview(JSONArray().put(change().put("replaceExisting", true)), listOf(entry)).single().replaceExisting)
    }
}
