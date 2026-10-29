package com.qylent.manhwatakip

import android.content.ContentValues
import android.provider.MediaStore
import android.os.ParcelFileDescriptor
import androidx.test.filters.SdkSuppress
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.IdlingRegistry
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.UiScrollable
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.hamcrest.Matchers.allOf
import java.util.UUID
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class BackupFileFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val idle = ScreenIdle()
    private val suffix = UUID.randomUUID().toString().take(8)

    @Before fun setup() { IdlingRegistry.getInstance().register(idle) }
    @After fun cleanup() { IdlingRegistry.getInstance().unregister(idle); idle.close() }

    @Test fun fullBackupUsesMaskedPasswordRealFilePickerAndRestorePreview() {
        val entry = Series(title = "Tam yedek $suffix", favorite = true, alternativeTitles = listOf("Diğer başlık $suffix"))
        LibraryStore(context).use { it.save(entry) }
        val before = LibraryStore(context).use { it.all() }
        val file = "manhwa-full-$suffix.mhtbackup"
        val password = "fixture-pass123"
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withContentDescription("Gezinme: Ayarlar")).perform(click())
            onView(withText("Yedekler")).perform(scrollTo(), click())
            onView(withText("Şifreli tam yedek (anahtarlar dahil)")).perform(click())
            onView(withText("Parolalı yedek oluştur")).perform(scrollTo(), click())
            onView(withHint("Yedek parolası")).perform(replaceText(password), closeSoftKeyboard())
            onView(withHint("Parolayı tekrar gir")).perform(replaceText(password), closeSoftKeyboard())
            onView(withText("Devam")).perform(click())
            downloads()
            device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000)!!.text = file
            device.wait(Until.findObject(By.text(Pattern.compile("(?i)save|kaydet"))), 10_000)!!.click()
            assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)), 10_000))
            onView(allOf(withText("Ayarlar"), isDisplayed())).check(matches(isDisplayed()))
        }
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat /sdcard/Download/$file")).use(FullBackupStore::read)
        assertTrue(FullBackupCodec.isEncrypted(bytes))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(entry.title))
        ActivityScenario.launch(BackupsActivity::class.java).use {
            onView(withText("Yedeği geri yükle")).perform(scrollTo(), click())
            downloads()
            UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(file))
            device.wait(Until.findObject(By.text(file)), 10_000)!!.click()
            assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)), 10_000))
            onView(withHint("Yedek parolası")).perform(replaceText(password), closeSoftKeyboard())
            onView(withText("Devam")).perform(click())
            onView(withText("Tam yedeği geri yükle")).inRoot(isDialog()).check(matches(isDisplayed()))
            assertEquals(before, LibraryStore(context).use { it.all() })
            onView(withText("İptal")).inRoot(isDialog()).perform(click())
            assertEquals(before, LibraryStore(context).use { it.all() })
        }
    }

    @Test fun exportWritesAllFieldsThroughRealAndroidFilePicker() {
        val entry = Series(title = "Yedek $suffix", lastChapter = "48.5", status = ReadingStatus.PAUSED,
            opinion = Opinion.DISLIKED, url = "https://example.com/$suffix", notes = "İki satır\nİkinci satır 🐉",
            likedReason = "Dünya", dislikedReason = "Tempo", dropReason = "Tekrar")
        val before = LibraryStore(context).use { store -> store.save(entry); store.all() }
        val file = "manhwa-export-$suffix.json"
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withContentDescription("Gezinme: Ayarlar")).perform(click())
            onView(withText("Yedekler")).perform(scrollTo(), click())
            onView(withText("Dosyaya yedekle")).inRoot(isDialog()).perform(click())
            onView(withText("Dosya seç")).inRoot(isDialog()).perform(click())
            downloads()
            val name = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000)
            assertNotNull("Android dosya adı alanı açılmalı", name)
            name!!.text = file
            val save = device.wait(Until.findObject(By.text(Pattern.compile("(?i)save|kaydet"))), 10_000)
            assertNotNull("Android kaydet düğmesi bulunmalı", save)
            save!!.click()
            assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)), 10_000))
            onView(allOf(withText("Ayarlar"), isDisplayed())).check(matches(isDisplayed()))
            // Read raw bytes: UiDevice's text helper can split a UTF-8 character between chunks.
            val actual = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat /sdcard/Download/$file"))
                .use(BackupCodec::read)
            assertEquals(before, actual)
        }
    }

    @Test fun importNeedsConfirmationAndPreservesExistingNotes() {
        val current = Series(title = "Kalsın $suffix", notes = "Yeni not", lastChapter = "90")
        val missing = Series(title = "Geri gelen $suffix", notes = "İçe aktarıldı 🐉", lastChapter = "12.5")
        LibraryStore(context).use { it.save(current) }
        val file = "manhwa-import-$suffix.json"
        writeFixture(file, BackupCodec.encode(listOf(current.copy(notes = "Eski not", lastChapter = "10"), missing)))
        ActivityScenario.launch(MainActivity::class.java).use {
            chooseImport(file)
            onView(withText("Yedeği geri yükle")).inRoot(isDialog()).check(matches(isDisplayed()))
            LibraryStore(context).use { assertNull(it.get(missing.id)); assertEquals(current, it.get(current.id)) }
            onView(withText("Geri yükle")).inRoot(isDialog()).perform(click())
            onView(allOf(withText("Ayarlar"), isDisplayed())).check(matches(isDisplayed()))
            LibraryStore(context).use { assertEquals(missing, it.get(missing.id)); assertEquals(current, it.get(current.id)) }
        }
    }

    @Test fun malformedFileDoesNotChangeTheLibrary() {
        val before = LibraryStore(context).use { it.all() }
        val file = "manhwa-broken-$suffix.json"
        writeFixture(file, "{\"format\":\"manhwa-takip\",\"version\":1,\"records\":[{\"title\":\"Bozuk\"}]}")
        ActivityScenario.launch(MainActivity::class.java).use {
            chooseImport(file)
            onView(allOf(withText("Ayarlar"), isDisplayed())).check(matches(isDisplayed()))
            assertEquals(before, LibraryStore(context).use { it.all() })
        }
    }

    private fun chooseImport(file: String) {
        onView(withContentDescription("Gezinme: Ayarlar")).perform(click())
        onView(withText("Yedekler")).perform(scrollTo(), click())
        onView(withText("Yedekten geri yükle")).inRoot(isDialog()).perform(click())
        downloads()
        // The fixture may be outside the viewport when prior test downloads remain.
        UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(file))
        val selected = device.wait(Until.findObject(By.text(file)), 10_000)
        assertNotNull("Test yedeği dosya seçicisinde görünmeli", selected)
        selected!!.click()
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)), 10_000))
    }

    private fun downloads() {
        val roots = device.findObject(UiSelector().description("Show roots"))
        assertTrue("Android dosya seçicisi açılmalı", roots.waitForExists(10_000))
        device.waitForIdle()
        roots.click()
        device.waitForIdle()
        // Scope to the drawer: an already-open Downloads page has a second label behind it.
        val drawer = device.wait(Until.findObject(By.res(Pattern.compile(".*:id/roots_list"))), 10_000)
        assertNotNull("Dosya seçicisinin konum listesi açılmalı", drawer)
        val download = drawer!!.findObject(By.text("Downloads"))
        assertNotNull("Test cihazındaki Downloads konumu bulunmalı", download)
        download!!.click()
        assertTrue(device.wait(Until.gone(By.res(Pattern.compile(".*:id/roots_list"))), 10_000))
        device.waitForIdle()
    }

    private fun writeFixture(file: String, text: String) {
        // Register the synthetic download with Android, as a downloaded file would be.
        // Shell-created files are not indexed by the Downloads document provider.
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
    }
}
