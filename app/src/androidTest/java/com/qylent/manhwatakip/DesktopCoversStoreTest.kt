package com.qylent.manhwatakip

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.UUID

class DesktopCoversStoreTest {
    @Test fun missingCoversOnlyPreserveLibraryAndRejectInvalidBatchBeforeWriting() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "desktop-covers-${UUID.randomUUID()}"; val folder = File(base.cacheDir, name)
        val context = object : ContextWrapper(base) { override fun getFilesDir() = folder.apply { mkdirs() } }
        val bitmap = Bitmap.createBitmap(8, 12, Bitmap.Config.ARGB_8888)
        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray(); bitmap.recycle()
        try {
            LibraryStore(base, "$name.db").use { store ->
                val covers = CoverStore(context)
                val first = store.save(Series(title = "Bir", notes = "Kalsın", lastChapter = "8", favorite = true))
                val second = store.save(Series(title = "İki", notes = "Dokunma"))
                fun change(row: Series, bytes: ByteArray = jpeg) = JSONObject().put("id", row.id).put("expectedUpdatedAt", row.updatedAt)
                    .put("imageUrl", "https://uploads.mangadex.org/covers/a/b.jpg").put("jpeg", Base64.getEncoder().encodeToString(bytes))
                val invalid = DesktopCovers.preview(JSONArray().put(change(first)).put(change(second, byteArrayOf(0))), store.all())
                assertThrows(IllegalArgumentException::class.java) { DesktopCovers.apply(invalid, store, covers) }
                assertEquals(first, store.get(first.id)); assertNull(covers.bytes(first.id)); assertNull(covers.bytes(second.id))
                val pending = DesktopCovers.preview(JSONArray().put(change(first)), store.all())
                assertEquals(1, DesktopCovers.apply(pending, store, covers))
                val saved = store.get(first.id)!!
                assertEquals(first.copy(coverUrl = saved.coverUrl, updatedAt = saved.updatedAt), saved)
                assertEquals(second, store.get(second.id)); assertTrue(store.history().isEmpty()); assertNotNull(covers.load(first.id))
                val originalImage = covers.bytes(first.id)!!
                val replacement = DesktopCovers.preview(JSONArray().put(change(saved)), store.all())
                assertThrows(IllegalArgumentException::class.java) { DesktopCovers.apply(replacement, store, covers) }
                assertArrayEquals(originalImage, covers.bytes(first.id)); assertEquals(saved, store.get(first.id))
                val newBitmap = Bitmap.createBitmap(8, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
                val newJpeg = ByteArrayOutputStream().also { newBitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray(); newBitmap.recycle()
                val approvedReplacement = DesktopCovers.preview(JSONArray().put(change(saved, newJpeg).put("replaceExisting", true)), store.all())
                assertEquals(1, DesktopCovers.apply(approvedReplacement, store, covers))
                assertEquals(saved.copy(updatedAt = store.get(first.id)!!.updatedAt), store.get(first.id))
                assertNotNull(covers.load(first.id))
                assertFalse(originalImage.contentEquals(covers.bytes(first.id)!!))
                val beforeManual = store.get(first.id)!!
                val manualItem = JSONObject().put("id", beforeManual.id).put("expectedUpdatedAt", beforeManual.updatedAt)
                    .put("jpeg", Base64.getEncoder().encodeToString(jpeg))
                val manual = DesktopCovers.preview(JSONArray().put(manualItem), store.all(), manual = true)
                assertEquals(1, DesktopCovers.apply(manual, store, covers))
                val afterManual = store.get(first.id)!!
                assertTrue(afterManual.coverUrl.startsWith("https://manhwa-takip.invalid/local-cover/"))
                assertEquals(beforeManual.copy(coverUrl = afterManual.coverUrl, updatedAt = afterManual.updatedAt), afterManual)
                assertEquals(second, store.get(second.id)); assertTrue(store.history().isEmpty())
                assertThrows(IllegalArgumentException::class.java) { DesktopCovers.preview(JSONArray().put(change(first)), store.all()) }
            }
        } finally { base.deleteDatabase("$name.db"); folder.deleteRecursively() }
    }
}
