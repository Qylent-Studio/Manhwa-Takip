package com.qylent.manhwatakip

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class FullBackupStoreTest {
    @Test fun portableRestoreIsValidatedIdempotentAndKeepsExistingDataAndKeys() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        fun context(part: String) = object : ContextWrapper(base) {
            override fun getFilesDir() = File(base.cacheDir, "full-$suffix-$part").apply { mkdirs() }
        }
        val source = context("source"); val target = context("target")
        val names = listOf("source-$suffix", "target-$suffix")
        val ai = names.map { AiSecretStore(base, "$it-ai") }
        val bridge = names.map { BridgePreferences(base, "$it-bridge") }
        val prefs = names.map { AppPreferences(base, "$it-app") }
        val password = "fixture-pass123".toCharArray()
        try {
            LibraryStore(base, "${names[0]}.db").use { library -> ChatStore(base, "${names[0]}-chats.db").use { chats ->
                val original = library.save(Series(title = "Türkçe yedek", alternativeTitles = listOf("Diğer ad"), favorite = true, lastChapter = "8", coverUrl = "https://example.com/cover.jpg"))
                val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
                val image = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
                bitmap.recycle(); CoverStore(source).save(original.id, image)
                val record = library.updateChapter(original, "9")
                val chat = ChatRecord(draft = "Taslak korunur", history = listOf("user" to "Merhaba"))
                chats.save(chat)
                ai[0].save(AiSettings("https://example.com/v1", "test-model"), "synthetic-api-key")
                bridge[0].saveKey("A".repeat(40)); prefs[0].viewMode = "grid"
                val sourceStore = FullBackupStore(library, CoverStore(source), chats, prefs[0], ai[0], bridge[0])
                val export = sourceStore.export(BackupSelection(), password)
                val libraryOnly = sourceStore.export(BackupSelection(covers = false, history = false, chats = false, settings = false, keys = false), password)
                LibraryStore(base, "${names[1]}.db").use { destination -> ChatStore(base, "${names[1]}-chats.db").use { targetChats ->
                    val store = FullBackupStore(destination, CoverStore(target), targetChats, prefs[1], ai[1], bridge[1])
                    assertThrows(IllegalArgumentException::class.java) { store.preview(export, "yanlis-parola".toCharArray()) }
                    assertTrue(destination.all().isEmpty()); assertTrue(targetChats.list().isEmpty()); assertEquals("", ai[1].key())
                    val bad = FullBackupCodec.encrypt("{\"format\":\"manhwa-full\",\"version\":1,\"library\":{},\"chats\":[]}".toByteArray(), password)
                    assertThrows(IllegalArgumentException::class.java) { store.preview(bad, password) }
                    assertTrue(destination.all().isEmpty())
                    val brokenCover = org.json.JSONObject(String(FullBackupCodec.decrypt(export, password), Charsets.UTF_8)).apply {
                        getJSONArray("covers").getJSONObject(0).put("jpeg", "broken")
                    }
                    assertThrows(IllegalArgumentException::class.java) { store.preview(FullBackupCodec.encrypt(brokenCover.toString().toByteArray(), password), password) }
                    assertTrue(destination.all().isEmpty()); assertTrue(targetChats.list().isEmpty()); assertEquals("", bridge[1].key())
                    val preview = store.preview(export, password)
                    assertEquals(ImportResult(1, 0), preview.seriesPlan)
                    assertEquals("Eklenecek", preview.apiKeyAction)
                    assertEquals("Eklenecek", preview.bridgeKeyAction)
                    assertEquals(1, store.importMissing(store.preview(libraryOnly, password)).library.added)
                    assertTrue(destination.history().isEmpty())
                    assertEquals(0, store.importMissing(preview).library.added)
                    assertEquals(record, destination.get(record.id)); assertEquals(1, destination.history().size)
                    assertNotNull(CoverStore(target).load(record.id))
                    assertEquals(chat, targetChats.get(chat.id)); assertEquals("synthetic-api-key", ai[1].key()); assertFalse(ai[1].consented())
                    assertEquals("A".repeat(40), bridge[1].key()); assertTrue(bridge[1].pending().isEmpty()); assertEquals("grid", prefs[1].viewMode)
                    val current = destination.save(record.copy(notes = "Telefondaki yeni not"))
                    val active = ChatRecord(draft = "Aktif sohbet değişmez"); targetChats.save(active)
                    ai[1].save(AiSettings("https://example.com/v1", "test-model"), "existing-api-key")
                    val repeated = store.preview(export, password)
                    assertEquals(ImportResult(0, 1), repeated.seriesPlan)
                    assertEquals("Mevcut anahtar korunacak", repeated.apiKeyAction)
                    assertEquals("Mevcut anahtar korunacak", repeated.bridgeKeyAction)
                    assertEquals(0, store.importMissing(preview).library.added)
                    assertEquals(current, destination.get(record.id)); assertEquals(active, targetChats.active())
                    assertEquals("existing-api-key", ai[1].key()); assertEquals(2, targetChats.list().size); assertEquals(1, destination.history().size)
                } }
            } }
        } finally {
            names.forEach { base.deleteDatabase("$it.db"); base.deleteDatabase("$it-chats.db") }
            ai.forEach { it.clear() }; bridge.forEach { it.clear() }
            names.forEach { base.getSharedPreferences("$it-app", Context.MODE_PRIVATE).edit().clear().commit() }
            // Only this test's isolated cache directories.
            source.filesDir.deleteRecursively(); target.filesDir.deleteRecursively(); password.fill('\u0000')
        }
    }
}
