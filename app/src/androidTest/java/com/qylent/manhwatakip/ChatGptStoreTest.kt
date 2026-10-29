package com.qylent.manhwatakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test

class ChatGptStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun previewCancelAndConfirmedBatchPreserveDataAndRecordOnlyRealProgress() {
        val database = "chatgpt-${System.nanoTime()}.db"
        try { LibraryStore(context, database).use { store ->
            val current = store.save(Series(title = "Korunan", url = "https://example.com/current", lastChapter = "10",
                notes = "Telefon notu", favorite = true, categories = listOf("Özel"), alternativeTitles = listOf("Other")))
            val before = store.all()
            val preview = ChatGptTransfer.preview("""{"series":[{"id":"${current.id}","lastChapter":"12"},{"title":"Yeni","url":"https://example.com/new","lastChapter":"5"}]}""", before)
            assertEquals(before, store.all()); assertTrue(store.history().isEmpty())
            store.applyTransfer(preview.changes)
            assertEquals(2, store.all().size); assertEquals("12", store.get(current.id)!!.lastChapter)
            assertEquals(current.notes, store.get(current.id)!!.notes); assertEquals(current.categories, store.get(current.id)!!.categories)
            assertTrue(store.get(current.id)!!.favorite); assertEquals(current.alternativeTitles, store.get(current.id)!!.alternativeTitles)
            assertEquals(1, store.history().size); assertEquals(current.id, store.history().single().seriesId)
            val again = ChatGptTransfer.preview("""{"series":[{"id":"${current.id}","lastChapter":"12"},{"title":"Yeni","url":"https://example.com/new","lastChapter":"5"}]}""", store.all())
            assertTrue(again.changes.isEmpty()); assertEquals(2, again.unchanged)
            assertEquals(1, store.history().size)
        } } finally { context.deleteDatabase(database) }
    }
    @Test fun staleTailDeletedIdentityAndDuplicateTargetsRollBackEntireBatch() {
        val database = "chatgpt-conflict-${System.nanoTime()}.db"
        try { LibraryStore(context, database).use { store ->
            val first = store.save(Series(title = "Bir", lastChapter = "1"))
            val second = store.save(Series(title = "İki", lastChapter = "2"))
            val preview = ChatGptTransfer.preview("""{"series":[{"id":"${first.id}","lastChapter":"3"},{"id":"${second.id}","lastChapter":"4"}]}""", store.all())
            store.save(second.copy(notes = "Önizlemeden sonra değişti"))
            val before = store.all(); val history = store.history()
            assertThrows(IllegalArgumentException::class.java) { store.applyTransfer(preview.changes) }
            assertEquals(before, store.all()); assertEquals(history, store.history())
            val removed = store.save(Series(title = "Silinen")); store.delete(removed)
            val resurrection = listOf(AiProposal(first, first.copy(lastChapter = "3")), AiProposal(null, removed))
            assertThrows(IllegalArgumentException::class.java) { store.applyTransfer(resurrection) }
            assertEquals(before, store.all()); assertEquals(history, store.history())
            assertThrows(IllegalArgumentException::class.java) { store.applyTransfer(listOf(AiProposal(first, first.copy(lastChapter = "3")), AiProposal(first, first.copy(lastChapter = "4")))) }
            assertEquals(before, store.all())
        } } finally { context.deleteDatabase(database) }
    }
    @Test fun androidGrammarDuplicateFieldsAndNullTailAreRejected() {
        listOf("{title:'Yeni',lastChapter:010}", "{\"title\":\"Yeni\",\"lastChapter\":010}",
            "{\"title\":\"Yeni\",\"lastChapter\":\"1\",\"lastChapter\":\"2\"}",
            "{\"title\":\"Yeni\",\"ti\\u0074le\":\"Diğer\"}", "{\"title\":\"Yeni\"}\u0000bad",
            "{\"title\":\"Yeni\",}", "{\"title\":\"Yeni\"} // explanation")
            .forEach { assertThrows(IllegalArgumentException::class.java) { ChatGptTransfer.preview(it, emptyList()) } }
    }
    @Test fun newTitleAliasAndLinkCreatedAfterPreviewRejectTheWholeBatch() {
        val database = "chatgpt-identity-${System.nanoTime()}.db"
        try { LibraryStore(context, database).use { store ->
            val first = store.save(Series(title = "Bir", lastChapter = "1"))
            repeat(3) { variant ->
                val title = "Yeni $variant"
                val link = "https://example.com/new-$variant"
                val preview = ChatGptTransfer.preview("""{"series":[{"id":"${first.id}","lastChapter":"2"},{"title":"$title","url":"$link"}]}""", store.all())
                store.save(when (variant) {
                    0 -> Series(title = title, url = "https://example.com/other")
                    1 -> Series(title = "Diğer $variant", url = link)
                    else -> Series(title = "Diğer $variant", alternativeTitles = listOf(title))
                })
                val before = store.all(); val history = store.history()
                assertThrows(IllegalArgumentException::class.java) { store.applyTransfer(preview.changes) }
                assertEquals(before, store.all()); assertEquals(history, store.history())
            }
        } } finally { context.deleteDatabase(database) }
    }
}
