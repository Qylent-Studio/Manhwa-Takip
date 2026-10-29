package com.qylent.manhwatakip

import android.content.Context
import android.graphics.Bitmap
import android.widget.EditText
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture

class SeriesMetadataUiTest {
    private fun field(activity: EditActivity, hint: String): EditText {
        fun find(view: android.view.View): EditText? {
            if (view is EditText && view.hint?.toString() == hint) return view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        return find(activity.window.decorView)!!
    }
    @Test fun delayedMetadataCannotOverwriteManualTitleOrChangedUrlEvenAfterRotation() {
        assertTrue(android.os.Build.HARDWARE == "ranchu" || android.os.Build.FINGERPRINT.contains("generic"))
        val app = ApplicationProvider.getApplicationContext<Context>() as LibraryApplication
        val before = app.store.all()
        for (changeUrl in listOf(false, true)) {
            val pending = CompletableFuture<SeriesMetadata>()
            ActivityScenario.launch(EditActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    field(activity, "Okuma bağlantısı (isteğe bağlı)").setText("https://public.example/books/a")
                    val state = ViewModelProvider(activity)[EditActivity.SaveState::class.java]
                    state.metadataPending = pending; state.metadataUrl = "https://public.example/books/a"
                    state.metadataTitle = ""; state.metadataRevision = state.editRevision
                }
                // Retained real pending work is resumed by render, not a production test hook.
                scenario.recreate()
                scenario.onActivity { activity ->
                    if (changeUrl) field(activity, "Okuma bağlantısı (isteğe bağlı)").setText("https://public.example/books/b")
                    else field(activity, "Seri adı").setText("Benim adım")
                }
                pending.complete(SeriesMetadata("Old title", "https://public.example/books/a", CoverResult("https://public.example/books/a", "https://cdn.example/a.jpg", byteArrayOf(1)), false))
                android.os.SystemClock.sleep(500)
                scenario.onActivity { activity ->
                    assertEquals(if (changeUrl) "" else "Benim adım", field(activity, "Seri adı").text.toString())
                    assertNull(ViewModelProvider(activity)[EditActivity.SaveState::class.java].coverResult)
                }
            }
        }
        assertEquals(before, app.store.all())
    }
    @Test fun currentResponseSuggestsTitleButExistingManualTitleIsNotReplaced() {
        assertTrue(android.os.Build.HARDWARE == "ranchu" || android.os.Build.FINGERPRINT.contains("generic"))
        for (manual in listOf("", "Elle girilen")) {
            ActivityScenario.launch(EditActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    field(activity, "Okuma bağlantısı (isteğe bağlı)").setText("https://public.example/books/a")
                    field(activity, "Seri adı").setText(manual)
                    val state = ViewModelProvider(activity)[EditActivity.SaveState::class.java]
                    state.metadataPending = CompletableFuture.completedFuture(SeriesMetadata("Önerilen isim", "https://public.example/books/a", null, true))
                    state.metadataUrl = "https://public.example/books/a"; state.metadataTitle = manual
                    state.metadataRevision = state.editRevision
                }
                scenario.recreate()
                android.os.SystemClock.sleep(500)
                scenario.onActivity { assertEquals(manual.ifEmpty { "Önerilen isim" }, field(it, "Seri adı").text.toString()) }
            }
        }
    }
    @Test fun actualDecoderRejectsBrokenAndExcessivePixelInputs() {
        assertThrows(IllegalArgumentException::class.java) { CoverStore.decode("broken".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { CoverStore.decode(ByteArray(5 * 1024 * 1024 + 1)) }
        // Valid PNG header dimensions are checked before allocating a decoded bitmap.
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val png = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        CoverStore.decode(png).recycle()
        val bytes = java.nio.ByteBuffer.wrap(png)
        bytes.putInt(16, 100000); bytes.putInt(20, 100000)
        assertThrows(IllegalArgumentException::class.java) { CoverStore.decode(png) }
    }
}
