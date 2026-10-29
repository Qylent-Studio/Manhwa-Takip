package com.qylent.manhwatakip

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test

class BulkCoverStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun image(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it); bitmap.recycle() }.toByteArray()
    }

    @Test fun successfulCoverPreservesAllOtherFieldsAndIsAvailableOffline() {
        val name = "bulk-cover-${System.nanoTime()}.db"; val covers = CoverStore(context)
        val entry = Series(title = "Toplu kapak testi", notes = "Korunan not", categories = listOf("Özel"),
            favorite = true, pinned = true, alternativeTitles = listOf("Başka ad"), lastChapter = "12")
        try { LibraryStore(context, name).use { store ->
            val before = store.save(entry)
            val found = CoverResult("https://trmanga.com/webtoon/test", "https://cdn.trmanga.com/test.jpg", image(android.graphics.Color.GREEN))
            val saved = store.applyCover(before, found, covers)
            assertEquals(before.copy(coverUrl = found.imageUrl, updatedAt = saved.updatedAt), saved)
            assertTrue(saved.updatedAt > before.updatedAt); assertTrue(store.history().isEmpty())
            val offline = CoverStore(context).load(entry.id)!!; assertEquals(8, offline.width)
            offline.recycle()
        } } finally { covers.remove(entry.id); context.deleteDatabase(name) }
    }

    @Test fun invalidImageAndStaleOrDeletedRecordKeepOldDataAndCover() {
        val name = "bulk-cover-failure-${System.nanoTime()}.db"; val covers = CoverStore(context)
        val entry = Series(title = "Eski kapak", coverUrl = "https://cdn.trmanga.com/old.jpg", notes = "Koru")
        try { LibraryStore(context, name).use { store ->
            val before = store.save(entry); covers.save(entry.id, image(android.graphics.Color.RED))
            val oldImage = covers.bytes(entry.id)!!
            val found = CoverResult("https://trmanga.com/webtoon/test", "https://cdn.trmanga.com/new.jpg", byteArrayOf(1, 2, 3))
            assertThrows(IllegalArgumentException::class.java) { store.applyCover(before, found, covers) }
            assertEquals(before, store.get(entry.id)); assertArrayEquals(oldImage, covers.bytes(entry.id))
            val changed = store.save(before.copy(notes = "Yeni not"))
            assertThrows(IllegalArgumentException::class.java) { store.applyCover(before, found.copy(bytes = image(android.graphics.Color.GREEN)), covers) }
            assertEquals(changed, store.get(entry.id)); assertArrayEquals(oldImage, covers.bytes(entry.id))
            store.delete(changed)
            assertThrows(IllegalArgumentException::class.java) { store.applyCover(changed, found, covers) }
            assertNull(store.get(entry.id)); assertArrayEquals(oldImage, covers.bytes(entry.id))
        } } finally { covers.remove(entry.id); context.deleteDatabase(name) }
    }
}
