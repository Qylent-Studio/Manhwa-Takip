package com.qylent.manhwatakip

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Test

class SelectedDataStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun schemaFiveAndMcpPreserveLocalFieldsAndOldRecords() {
        val dbName = "selected-migration-${System.nanoTime()}.db"
        val base = Series(title = "Korunan", notes = "Not", categories = listOf("Bir"))
        try {
            LibraryStore(context, dbName).use { store -> store.save(base); store.recordBridgeRejection("keep") }
            context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { db ->
                db.execSQL("ALTER TABLE series DROP COLUMN local_fields"); db.version = 5
            }
            LibraryStore(context, dbName).use { store ->
                assertEquals(base, store.get(base.id)); assertEquals("rejected", store.bridgeDecisions()["keep"])
                val local = store.save(base.copy(favorite = true, pinned = true, alternativeTitles = listOf("Other")))
                val changed = store.applyProposal(AiProposal(local.wireProjection(), local.wireProjection().copy(lastChapter = "4")), "mcp-test")
                assertTrue(changed.favorite); assertTrue(changed.pinned); assertEquals(listOf("Other"), changed.alternativeTitles)
                assertEquals("4", changed.lastChapter)
            }
        } finally { context.deleteDatabase(dbName) }
    }
    @Test fun progressUndoAndBulkConflictAreAtomic() {
        val dbName = "selected-progress-${System.nanoTime()}.db"
        try { LibraryStore(context, dbName).use { store ->
            val first = store.save(Series(title = "Bir", lastChapter = "48.5"))
            val second = store.save(Series(title = "İki"))
            assertTrue(store.history().isEmpty())
            val next = store.updateChapter(first, "49.5")
            assertEquals(1, store.history().size)
            val undone = store.undoProgress(next, store.history(first.id).first().id)
            assertEquals("48.5", undone.lastChapter); assertEquals(2, store.history().size)
            assertThrows(IllegalArgumentException::class.java) { store.applyBulk(listOf(first, second), ReadingStatus.PAUSED, emptyList(), emptyList()) }
            assertEquals(second, store.get(second.id))
            store.addCategory("Bir"); store.changeCategory("Bir", "Bir", "#E53935")
            assertEquals("#E53935", store.categoryDefinitions().first().colorId)
        } } finally { context.deleteDatabase(dbName) }
    }
}
