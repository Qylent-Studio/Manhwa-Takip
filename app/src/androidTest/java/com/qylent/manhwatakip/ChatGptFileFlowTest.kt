package com.qylent.manhwatakip

import android.content.ContentValues
import android.content.Context
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.regex.Pattern

@androidx.test.filters.SdkSuppress(minSdkVersion = 29)
class ChatGptFileFlowTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val idle = ScreenIdle()
    @org.junit.Before fun registerIdle() { androidx.test.espresso.IdlingRegistry.getInstance().register(idle) }
    @org.junit.After fun unregisterIdle() { androidx.test.espresso.IdlingRegistry.getInstance().unregister(idle); idle.close() }
    @Test fun realJsonExportAndFileImportPreviewRequireApprovalAndKeepExistingNotes() {
        val app = context as LibraryApplication
        val suffix = System.nanoTime().toString()
        val current = app.store.save(Series(title = "Dosya $suffix", lastChapter = "5", notes = "Telefon notu", favorite = true))
        val importedTitle = "Yeni dosya $suffix"
        try {
            ActivityScenario.launch(ChatGptActivity::class.java).use {
                onView(withText("Yeni öneri için dışa aktar")).perform(scrollTo(), click())
                onView(withText("Çıktıyı dosyaya kaydet")).perform(scrollTo(), click())
                downloads()
                val filename = "chatgpt-export-$suffix.json"
                device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000)!!.text = filename
                device.wait(Until.findObject(By.text(Pattern.compile("(?i)save|kaydet"))), 10_000)!!.click()
                AiShareTestSupport.awaitResumed(ChatGptActivity::class.java)
                onView(withText("ChatGPT JSON dosyası kaydedildi.")).perform(scrollTo()).check(matches(isDisplayed()))
                val bytes = ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat /sdcard/Download/$filename")).use(ChatGptTransfer::read)
                val root = JSONObject(bytes)
                assertTrue(root.getString("prompt").contains("ücretsiz"))
                assertTrue((0 until root.getJSONArray("series").length()).any { root.getJSONArray("series").getJSONObject(it).getString("title") == current.title })
                assertFalse(root.has("apiKey"))
                val importName = "chatgpt-import-$suffix.json"
                val incoming = """{"series":[{"id":"${current.id}","lastChapter":"8"},{"title":"$importedTitle","url":"https://example.com/$suffix","lastChapter":"12"}]}"""
                writeFixture(importName, incoming)
                val before = app.store.all()
                onView(withText("Dosya seç")).perform(scrollTo(), click())
                downloads()
                UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(importName))
                device.wait(Until.findObject(By.text(importName)), 10_000)!!.click()
                AiShareTestSupport.awaitResumed(ChatGptActivity::class.java)
                onView(withText("ChatGPT içe aktarma önizlemesi")).inRoot(isDialog()).check(matches(isDisplayed()))
                assertEquals(before, app.store.all())
                onView(withText("Onayla ve içe aktar")).inRoot(isDialog()).perform(click())
                onView(withText("2 seri değişikliği uygulandı.")).perform(scrollTo()).check(matches(isDisplayed()))
                assertEquals("8", app.store.get(current.id)!!.lastChapter); assertEquals(current.notes, app.store.get(current.id)!!.notes)
                assertTrue(app.store.get(current.id)!!.favorite); assertNotNull(app.store.all().singleOrNull { it.title == importedTitle })
            }
        } finally {
            app.store.get(current.id)?.let(app.store::delete)
            app.store.all().singleOrNull { it.title == importedTitle }?.let(app.store::delete)
        }
    }
    @Test fun largeDraftSurvivesRecreationAndLargeExportUsesTheFilePath() {
        val app = context as LibraryApplication
        val suffix = System.nanoTime().toString()
        val fixtures = (1..50).map { app.store.save(Series(title = "Büyük $suffix/$it", lastChapter = "11", notes = "N".repeat(4000))) }
        try {
            ActivityScenario.launch(ChatGptActivity::class.java).use { scenario ->
                onView(withText("Notlar")).perform(scrollTo(), click())
                onView(withText("Serileri dışa aktar")).perform(scrollTo(), click())
                scenario.onActivity { it.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("test", "önceki pano")) }
                onView(withText("Çıktıyı kopyala")).perform(scrollTo(), click())
                onView(withText("Büyük çıktıyı dosyaya kaydet.")).perform(scrollTo()).check(matches(isDisplayed()))
                scenario.onActivity { assertEquals("önceki pano", it.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()) }
                onView(withText("Çıktıyı dosyaya kaydet")).perform(scrollTo(), click())
                downloads()
                val filename = "chatgpt-large-$suffix.json"
                device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000)!!.text = filename
                device.wait(Until.findObject(By.text(Pattern.compile("(?i)save|kaydet"))), 10_000)!!.click()
                AiShareTestSupport.awaitResumed(ChatGptActivity::class.java)
                onView(withText("ChatGPT JSON dosyası kaydedildi.")).perform(scrollTo()).check(matches(isDisplayed()))
                val text = ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat /sdcard/Download/$filename")).use(ChatGptTransfer::read)
                assertTrue(text.length > 100_000)
                val rows = JSONObject(text).getJSONArray("series")
                val exported = (0 until rows.length()).map { rows.getJSONObject(it).getString("title") }.toSet()
                assertEquals(app.store.all().map { it.title }.toSet(), exported)
                val draft = """{"id":"${fixtures.first().id}","lastChapter":"12","extra":"${"D".repeat(50_000)}"}"""
                onView(withHint("ChatGPT JSON'unu yapıştır")).perform(scrollTo(), replaceText(draft), closeSoftKeyboard())
                scenario.recreate()
                onView(withHint("ChatGPT JSON'unu yapıştır")).check(matches(withText(draft)))
                onView(withText("Yapıştırılan JSON'u önizle")).perform(scrollTo(), click())
                onView(withText("ChatGPT içe aktarma önizlemesi")).inRoot(isDialog()).check(matches(isDisplayed()))
                onView(withText("İptal")).inRoot(isDialog()).perform(click())
                assertEquals("11", app.store.get(fixtures.first().id)!!.lastChapter)
            }
        } finally { fixtures.forEach { app.store.get(it.id)?.let(app.store::delete) } }
    }
    private fun downloads() {
        val roots = device.findObject(UiSelector().description("Show roots"))
        assertTrue(roots.waitForExists(10_000)); device.waitForIdle(); roots.click(); device.waitForIdle()
        val drawer = device.wait(Until.findObject(By.res(Pattern.compile(".*:id/roots_list"))), 10_000)!!
        drawer.findObject(By.text("Downloads"))!!.click()
        assertTrue(device.wait(Until.gone(By.res(Pattern.compile(".*:id/roots_list"))), 10_000)); device.waitForIdle()
    }
    private fun writeFixture(filename: String, text: String) {
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename); put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download"); put(MediaStore.Downloads.IS_PENDING, 1)
        })!!
        resolver.openOutputStream(uri)!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
    }
}
