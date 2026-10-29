package com.qylent.manhwatakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DesktopStoreTest {
    @Test fun directEditsRecordHistoryAndStaleBatchRollsBack() {
        val context = ApplicationProvider.getApplicationContext<Context>(); val name = "desktop-${System.nanoTime()}.db"
        try { LibraryStore(context, name).use { store ->
            val first = store.save(Series(title = "Bir", lastChapter = "1", notes = "Korunan not", favorite = true))
            val second = store.save(Series(title = "İki", lastChapter = "2"))
            fun changes(a: Series, b: Series) = """{"changes":[{"id":"${a.id}","expectedUpdatedAt":${a.updatedAt},"fields":{"lastChapter":"3"}},{"id":"${b.id}","expectedUpdatedAt":${b.updatedAt},"fields":{"lastChapter":"4"}}]}"""
            val planned = DesktopTransfer.preview(changes(first, second), store.all())
            store.save(second.copy(notes = "Telefonda değişti"))
            val before = store.all()
            assertThrows(IllegalArgumentException::class.java) { store.applyTransfer(planned) }; assertEquals(before, store.all()); assertTrue(store.history().isEmpty())
            val currentSecond = store.get(second.id)!!
            store.applyTransfer(DesktopTransfer.preview(changes(first, currentSecond), store.all()))
            assertEquals(2, store.history().size); assertEquals("Korunan not", store.get(first.id)!!.notes); assertTrue(store.get(first.id)!!.favorite)
            assertThrows(IllegalArgumentException::class.java) { DesktopTransfer.preview(changes(first, currentSecond), store.all()) }
            assertEquals(2, store.history().size)
        } } finally { context.deleteDatabase(name) }
    }
    @Test fun portableFullBackupRejectsKeysAndPreservesExistingData() {
        val context = ApplicationProvider.getApplicationContext<Context>(); val prefix = "portable-${System.nanoTime()}"
        val prefs = AppPreferences(context, "$prefix-app"); val ai = AiSecretStore(context, "$prefix-ai"); val bridge = BridgePreferences(context, "$prefix-bridge")
        try { LibraryStore(context, "$prefix.db").use { library -> ChatStore(context, "$prefix-chat.db").use { chats ->
            val entry = library.save(Series(title = "Türkçe", lastChapter = "1")); library.updateChapter(entry, "2")
            chats.save(ChatRecord(draft = "Arşiv", history = listOf("user" to "Merhaba")))
            ai.save(AiSettings("https://example.com/v1", "model"), "synthetic-api-key"); bridge.saveKey("A".repeat(40))
            val full = FullBackupStore(library, CoverStore(context), chats, prefs, ai, bridge)
            val bytes = full.exportPortable(); val root = JSONObject(PortableFiles.text(bytes))
            assertFalse(root.has("keys")); assertFalse(String(bytes).contains("synthetic-api-key")); assertFalse(String(bytes).contains("A".repeat(40)))
            assertEquals(1, root.getJSONArray("history").length()); assertEquals(1, root.getJSONArray("chats").length())
            val existing = library.all(); full.importMissing(full.previewPortable(PortableFiles.zip(mapOf("backup.json" to bytes))))
            assertEquals(existing, library.all()); assertEquals("synthetic-api-key", ai.key())
            root.put("keys", JSONObject().put("bridge", "B".repeat(40)))
            assertThrows(IllegalArgumentException::class.java) { full.previewPortable(root.toString().toByteArray()) }
        } } } finally {
            context.deleteDatabase("$prefix.db"); context.deleteDatabase("$prefix-chat.db"); ai.clear(); bridge.clear()
            context.getSharedPreferences("$prefix-app", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
