package com.qylent.manhwatakip

import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

class PortableExportUiTest {
    private val app get() = ApplicationProvider.getApplicationContext<LibraryApplication>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val idle = ScreenIdle()
    @org.junit.Before fun before() {
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE == "ranchu" || android.os.Build.FINGERPRINT.contains("generic"))
        androidx.test.espresso.IdlingRegistry.getInstance().register(idle)
    }
    @org.junit.After fun after() { androidx.test.espresso.IdlingRegistry.getInstance().unregister(idle); idle.close() }
    private fun full() = FullBackupStore(app.store, app.covers, app.chats, AppPreferences(app), AiSecretStore(app), BridgePreferences(app))

    /** Only the isolated smoke test uses a real resolver, never payload tests. */
    private fun shareAndDismissReadyChooser(title: String, activity: Class<out android.app.Activity>): File {
        val directory = File(app.cacheDir, "shared-exports")
        val before = directory.listFiles()?.map { it.name }?.toSet().orEmpty()
        onView(withText(title)).perform(scrollTo(), click())
        val ready = By.res(Pattern.compile("(android|com.android.intentresolver):id/(chooser_grid|resolver_list)"))
        assertTrue("Actual chooser content must be ready before Back", device.wait(Until.hasObject(ready), 10_000))
        device.waitForIdle()
        device.pressBack()
        AiShareTestSupport.awaitResumed(activity)
        onView(withText(title)).check(matches(isEnabled()))
        // The completion callback opens the chooser only AFTER writeBytes returns.
        val created = directory.listFiles().orEmpty().filter { it.name !in before }
        assertEquals(1, created.size); assertTrue(created.single().length() > 0)
        return created.single()
    }

    private fun saveThroughSaf(label: String, name: String, activity: Class<out android.app.Activity>, success: String): ByteArray {
        onView(withText(label)).perform(scrollTo(), click())
        val roots = device.findObject(UiSelector().description("Show roots"))
        assertTrue(roots.waitForExists(10_000)); device.waitForIdle(); roots.click(); device.waitForIdle()
        val rootsList = By.res(Pattern.compile(".*:id/roots_list"))
        val drawer = device.wait(Until.findObject(rootsList), 10_000)!!
        drawer.findObject(By.text("Downloads"))!!.click()
        assertTrue(device.wait(Until.gone(rootsList), 10_000)); device.waitForIdle()
        val filename = device.findObject(UiSelector().className("android.widget.EditText"))
        assertTrue(filename.waitForExists(10_000)); filename.setText(name)
        device.waitForIdle()
        val save = device.findObject(UiSelector().textMatches("(?i)save|kaydet"))
        assertTrue(save.waitForExists(10_000)); save.click()
        AiShareTestSupport.awaitResumed(activity)
        // Both paths wait for I/O via ScreenIdle. Backup reports completion with a Toast,
        // not an in-layout status; its complete saved bytes are validated by previewPortable.
        if (activity == PortableExportActivity::class.java) onView(withText(label)).check(matches(isEnabled()))
        else onView(withText(success)).perform(scrollTo()).check(matches(isDisplayed()))
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat /sdcard/Download/$name")).use { PortableFiles.read(it) }
    }
    private fun removeDownloads(names: List<String>) {
        names.forEach { name -> android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("rm -f /sdcard/Download/$name")).use { it.readBytes() } }
    }

    @Test fun fullBackupJsonAndZipSaveThroughRealSafWithoutAiOptions() {
        val original = app.store.all(); val names = mutableListOf<String>()
        try {
            ActivityScenario.launch<PortableExportActivity>(Intent(app, PortableExportActivity::class.java).putExtra("backup", true)).use {
                for (zipped in listOf(false, true)) {
                    val name = "portable-backup-${System.nanoTime()}.${if (zipped) "zip" else "json"}"; names.add(name)
                    val bytes = saveThroughSaf(if (zipped) "ZIP dosyasına kaydet" else "JSON dosyasına kaydet", name, PortableExportActivity::class.java, "Dosya kaydedildi.")
                    full().previewPortable(bytes)
                    assertFalse(JSONObject(PortableFiles.text(PortableFiles.backup(bytes))).has("keys"))
                }
            }
            assertEquals(original, app.store.all())
        } finally { removeDownloads(names) }
    }

    @Test fun selectedUpdatedSnapshotMatchesBothRealSafFilesAfterRecreation() {
        val names = mutableListOf<String>()
        try {
            AiShareTestSupport.SelectionFixture(app).use { fixture ->
                ActivityScenario.launch(ChatGptActivity::class.java).use { scenario ->
                    fixture.configure(); AiShareTestSupport.output(scenario)
                    val changes = fixture.selected.joinToString(",") { """{"id":"${it.id}","lastChapter":"18"}""" }
                    AiShareTestSupport.approveImport("""{"series":[$changes]}""")
                    AiShareTestSupport.unavailable()
                    onView(withText("Önizlemeyi oluştur")).perform(scrollTo(), click())
                    val expected = AiShareTestSupport.output(scenario); fixture.assertProjection(expected, "18")
                    assertTrue(expected.length > 12_000)
                    scenario.recreate()
                    onView(withSpinnerText("Okunuyor")).perform(scrollTo()).check(matches(isDisplayed()))
                    scenario.onActivity { assertEquals(fixture.ids, ViewModelProvider(it)[ChatGptActivity.State::class.java].selectedIds) }
                    assertEquals(expected, AiShareTestSupport.output(scenario))
                    for (zipped in listOf(false, true)) {
                        val name = "selected-ai-${System.nanoTime()}.${if (zipped) "zip" else "json"}"; names.add(name)
                        val bytes = saveThroughSaf(if (zipped) "ZIP dosyasına kaydet" else "Çıktıyı dosyaya kaydet", name, ChatGptActivity::class.java,
                            if (zipped) "ZIP dosyası kaydedildi." else "ChatGPT JSON dosyası kaydedildi.")
                        if (zipped) AiShareTestSupport.assertArchive(bytes, expected, fixture.excluded)
                        else assertEquals(expected, bytes.toString(Charsets.UTF_8))
                        assertEquals("Unchanged picker return must retain this exact snapshot", expected, AiShareTestSupport.output(scenario))
                    }
                }
            }
        } finally { removeDownloads(names) }
    }

    @Test fun backupJsonAndZipStubbedSharesRemainReadableAndExcludeKeys() {
        androidx.test.espresso.intent.Intents.init()
        try {
            androidx.test.espresso.intent.Intents.intending(androidx.test.espresso.intent.matcher.IntentMatchers.hasAction(Intent.ACTION_CHOOSER))
                .respondWith(android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null))
            ActivityScenario.launch<PortableExportActivity>(Intent(app, PortableExportActivity::class.java).putExtra("backup", true)).use {
                val json = AiShareTestSupport.sharedBytes("JSON paylaş", "JSON paylaş")
                full().previewPortable(json); assertFalse(JSONObject(PortableFiles.text(json)).has("keys"))
                full().previewPortable(AiShareTestSupport.sharedBytes("ZIP paylaş", "JSON paylaş"))
            }
        } finally { androidx.test.espresso.intent.Intents.release() }
    }

    @Test fun realChooserSmokeWaitsForResolverThenResumesIntendedActivity() {
        ActivityScenario.launch<PortableExportActivity>(Intent(app, PortableExportActivity::class.java).putExtra("backup", true)).use {
            val file = shareAndDismissReadyChooser("JSON paylaş", PortableExportActivity::class.java)
            try { full().previewPortable(file.readBytes()) } finally { file.delete() }
        }
    }
}
