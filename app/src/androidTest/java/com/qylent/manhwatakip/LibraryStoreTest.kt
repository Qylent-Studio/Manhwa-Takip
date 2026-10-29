package com.qylent.manhwatakip

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LibraryStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = "test-${UUID.randomUUID()}.db"
    private lateinit var store: LibraryStore

    @Before fun setup() { store = LibraryStore(context, database) }
    @After fun cleanup() { store.close(); context.deleteDatabase(database) }

    @Test fun deletionRemovesOnlyReviewedRecordAndPersistsAfterReopen() {
        val chosen = store.save(Series(title = "Silinecek test", notes = "Test notu"))
        val other = store.save(Series(title = "Korunacak test", notes = "Kalacak not"))
        store.recordBridgeRejection("keep-decision")
        store.delete(chosen)
        store.close(); store = LibraryStore(context, database)
        assertNull(store.get(chosen.id))
        assertEquals(listOf(other), store.all())
        assertEquals(mapOf("keep-decision" to "rejected"), store.bridgeDecisions())
    }

    @Test fun staleDeletionAndOldProposalCannotRemoveOrResurrectRecord() {
        val reviewed = store.save(Series(title = "Değişen test"))
        val current = store.save(reviewed.copy(lastChapter = "2", updatedAt = reviewed.updatedAt + 1))
        assertThrows(IllegalArgumentException::class.java) { store.delete(reviewed) }
        assertEquals(current, store.get(current.id))
        store.delete(current)
        assertThrows(IllegalArgumentException::class.java) {
            store.applyProposal(AiProposal(current, current.copy(lastChapter = "3")))
        }
        assertNull(store.get(current.id))
    }

    @Test fun replayedCreationAndStaleEditStayDeletedAfterReopen() {
        val proposal = AiProposal(null, Series(title = "Eski oluşturma testi"))
        val saved = store.applyProposal(proposal, "old-create-token")
        store.ackBridgeDecisions(store.bridgeDecisions())
        store.delete(saved)
        store.close(); store = LibraryStore(context, database)
        assertThrows(IllegalArgumentException::class.java) { store.applyProposal(proposal, "old-create-token") }
        assertThrows(IllegalArgumentException::class.java) { store.save(saved) }
        assertNull(store.get(saved.id))
        assertTrue(store.bridgeDecisions().isEmpty())
    }

    @Test fun explicitBackupRestoreCanRestoreDeletedIdAndRemainEditable() {
        val entry = store.save(Series(title = "Yedekten geri gelen test"))
        store.delete(entry)
        assertEquals(ImportResult(1, 0), store.importMissing(listOf(entry)))
        val edited = store.save(entry.copy(notes = "Geri yükleme sonrası not", updatedAt = entry.updatedAt + 1))
        assertEquals(edited, store.get(entry.id))
        store.delete(edited)
        assertNull(store.get(entry.id))
    }

    @Test fun deletedIdentityWriteFailureRollsBackRecordDeletion() {
        val entry = store.save(Series(title = "Silme hata testi", notes = "Korunacak"))
        context.openOrCreateDatabase(database, android.content.Context.MODE_PRIVATE, null).use {
            it.execSQL("CREATE TRIGGER fail_deleted_identity BEFORE INSERT ON deleted_series BEGIN SELECT RAISE(FAIL, 'fixture'); END")
        }
        assertThrows(android.database.sqlite.SQLiteException::class.java) { store.delete(entry) }
        assertEquals(entry, store.get(entry.id))
    }

    @Test fun persistsEveryFieldAfterDatabaseReopens() {
        val entry = Series(title = "Çılgın Okur", lastChapter = "48.5", url = "https://example.com/48",
            status = ReadingStatus.PAUSED, opinion = Opinion.LIKED, likedReason = "Karakterler",
            dislikedReason = "Tempo", dropReason = "Ara", notes = "Not 🐉")
        store.save(entry)
        store.close()
        store = LibraryStore(context, database)
        assertEquals(entry, store.get(entry.id))
        assertEquals(listOf(entry), store.all())
    }

    @Test fun updatesOnlyChosenRecordAndKeepsReasons() {
        val first = Series(title = "Bir", likedReason = "Dünya", dropReason = "Tekrar")
        val second = Series(title = "İki")
        store.save(first); store.save(second)
        val changed = first.copy(lastChapter = "51", status = ReadingStatus.READING, updatedAt = first.updatedAt + 1)
        val saved = store.save(changed)
        assertTrue(saved.updatedAt >= changed.updatedAt)
        assertEquals(changed, saved.copy(updatedAt = changed.updatedAt))
        assertEquals(saved, store.get(first.id))
        assertEquals(second, store.get(second.id))
        assertEquals(2, store.all().size)
    }

    @Test fun duplicateRecordIsRejectedWithoutReplacingExisting() {
        val original = Series(title = "IŞIK", notes = "Kalsın")
        store.save(original)
        assertThrows(IllegalArgumentException::class.java) { store.save(Series(title = " ışık ")) }
        assertEquals(listOf(original), store.all())
    }

    @Test fun importPreservesNewerNotesAndAddsMissingRecords() {
        val current = Series(title = "Bir", notes = "Yeni not", lastChapter = "90")
        store.save(current)
        val missing = Series(title = "İki", notes = "Geri gelen")
        val incoming = listOf(current.copy(notes = "Eski not", lastChapter = "10"), missing)
        assertEquals(ImportResult(1, 1), store.previewImport(incoming))
        assertEquals(listOf(current), store.all())
        assertEquals(ImportResult(1, 1), store.importMissing(incoming))
        assertEquals(current, store.get(current.id))
        assertEquals(missing, store.get(missing.id))
        assertEquals(ImportResult(0, 2), store.importMissing(incoming))
    }

    @Test fun invalidBatchDoesNotPartiallyImportAndEmptyBatchNeverClears() {
        val current = Series(title = "Kalsın")
        store.save(current)
        assertThrows(IllegalArgumentException::class.java) {
            store.importMissing(listOf(Series(title = "Geçerli"), Series(title = "")))
        }
        assertEquals(listOf(current), store.all())
        assertEquals(ImportResult(0, 0), store.importMissing(emptyList()))
        assertEquals(listOf(current), store.all())
    }
}
