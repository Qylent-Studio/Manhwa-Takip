package com.qylent.manhwatakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test

class UserDataStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun categoriesRenameAndRemovalPreserveRecordsAndBackupRestoresSelections() {
        val name = "categories-${System.nanoTime()}.db"
        try {
            LibraryStore(context, name).use { store ->
                val first = store.save(Series(title = "Bir", notes = "Korunacak", categories = listOf("Harem", "Macera")))
                val second = store.save(Series(title = "İki", status = ReadingStatus.PAUSED))
                store.changeCategory("Harem", "Fantastik")
                assertEquals(listOf("Fantastik", "Macera"), store.get(first.id)!!.categories)
                assertEquals("Korunacak", store.get(first.id)!!.notes)
                assertEquals(second, store.get(second.id))
                assertThrows(IllegalArgumentException::class.java) { store.changeCategory("Fantastik", "MACERA") }
                val saved = store.get(first.id)!!
                val backup = BackupCodec.decode(BackupCodec.encode(listOf(saved)))
                store.changeCategory("Macera", null)
                assertEquals(listOf("Fantastik"), store.get(first.id)!!.categories)
                store.delete(store.get(first.id)!!)
                store.importMissing(backup)
                assertEquals(saved, store.get(first.id))
            }
            LibraryStore(context, name).use { assertEquals(2, it.all().size) }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun realSchemaThreeUpgradeKeepsRecordsDecisionsAndDeletedIds() {
        val name = "schema-three-${System.nanoTime()}.db"
        val before = Series(title = "Eski sürüm", notes = "Veri korunur")
        val deleted = Series(title = "Silinmiş")
        try {
            LibraryStore(context, name).use { it.save(before); it.save(deleted); it.delete(deleted); it.recordBridgeRejection("keep-decision") }
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use {
                it.execSQL("ALTER TABLE series DROP COLUMN categories"); it.execSQL("DROP TABLE categories"); it.version = 3
            }
            LibraryStore(context, name).use {
                assertEquals(before, it.get(before.id)); assertEquals(mapOf("keep-decision" to "rejected"), it.bridgeDecisions())
                assertThrows(IllegalArgumentException::class.java) { it.save(deleted) }
                assertTrue(it.categories().isEmpty())
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun chatsPersistAllMessagesDraftAndProposalAndArchiveRemovalDoesNotSwitchChat() {
        val name = "chat-store-${System.nanoTime()}.db"
        val record = ChatRecord(history = (1..16).map { "assistant" to "Yanıt $it" }, draft = "Taslak",
            proposals = listOf(BridgePending("00000000-0000-0000-0000-000000000001", AiProposal(null, Series(title = "Aday")))))
        val next = ChatRecord()
        try {
            ChatStore(context, name).use { it.save(record); it.save(next) }
            ChatStore(context, name).use {
                assertEquals(next, it.active()); assertEquals(record, it.get(record.id)); assertEquals(2, it.list().size)
                it.removeProposal(record.proposals.single().token)
                assertEquals(next, it.active())
                assertEquals(record.copy(proposals = emptyList()), it.get(record.id))
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun longChatReopensAndFailedSaveRollsBackActiveChatAndHistory() {
        val name = "long-chat-${System.nanoTime()}.db"
        val chat = ChatRecord(history = (1..260).map { "assistant" to ("Yanıt $it " + "a".repeat(16000)) })
        try {
            ChatStore(context, name).use { it.save(chat) }
            ChatStore(context, name).use {
                assertEquals(chat, it.active()); assertEquals(1, it.list().size)
                context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
                    db.execSQL("CREATE TRIGGER fail_message BEFORE INSERT ON chat_messages BEGIN SELECT RAISE(ABORT,'fixture'); END")
                }
                assertThrows(android.database.sqlite.SQLiteException::class.java) { it.save(chat.copy(id = java.util.UUID.randomUUID().toString())) }
                assertEquals(chat, it.active()); assertEquals(1, it.list().size)
            }
        } finally { context.deleteDatabase(name) }
    }
}
