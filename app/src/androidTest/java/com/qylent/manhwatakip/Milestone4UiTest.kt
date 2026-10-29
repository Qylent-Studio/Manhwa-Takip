package com.qylent.manhwatakip

import android.content.Context
import android.widget.ListView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.Espresso.onIdle
import androidx.test.espresso.IdlingRegistry
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import java.util.concurrent.FutureTask

/** Safe on the physical phone: only disposable fixtures are changed; settings are read-only. */
class Milestone4UiTest {
    private val idle = ScreenIdle()
    private lateinit var chatFixture: ChatFixture
    private val app get() = ApplicationProvider.getApplicationContext<Context>() as LibraryApplication
    @Before fun setup() { TestDeviceSetup.prepare(); chatFixture = ChatFixture(); IdlingRegistry.getInstance().register(idle) }
    @After fun cleanup() { IdlingRegistry.getInstance().unregister(idle); idle.close(); chatFixture.close() }

    @Test fun categoriesCombineWithSearchAndSurviveNavigationAndRecreation() {
        val before = app.store.all()
        val prefix = "M4 IŞIK ${System.nanoTime()}"
        val fixtures = ReadingStatus.entries.map { app.store.save(Series(title = "$prefix ${it.name}", status = it)) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                onView(withHint("Seri ara")).perform(replaceText(prefix.lowercase(java.util.Locale.forLanguageTag("tr-TR"))), closeSoftKeyboard())
                val labels = listOf("Okunacak", "Okunan", "Ara verilen", "Bırakılan", "Tamamlandı")
                labels.forEachIndexed { index, label ->
                    openLibraryFilters()
                    onView(withText(label)).perform(scrollTo(), click())
                    onView(withText("Tamam")).perform(click())
                    onView(withText(fixtures[index].title)).check(matches(isDisplayed()))
                    scenario.onActivity { activity ->
                        fun list(view: android.view.View): ListView? = if (view is ListView) view else
                            (view as? android.view.ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { list(group.getChildAt(it)) } }
                        assertEquals(1, list(activity.window.decorView)!!.adapter.count)
                    }
                }
                scenario.recreate()
                onView(withText(fixtures.last().title)).check(matches(isDisplayed()))
                onView(withContentDescription("Gezinme: Ayarlar")).perform(click())
                onView(withText("Gelişmiş · AI ve bağlantılar")).perform(scrollTo(), click())
                onView(withText("AI ayarları")).perform(scrollTo(), click())
                onView(withHint("Model adı")).check(matches(isDisplayed()))
                onView(withHint("Model adı")).perform(closeSoftKeyboard())
                pressBack()
                onView(withText("Bağlantılar")).perform(scrollTo(), click())
                onView(withText("ChatGPT / MCP")).perform(scrollTo(), click())
                onView(withText("Bağlantıyı aç / eşitle")).check(matches(isDisplayed()))
                pressBack(); pressBack(); pressBack()
                onView(withText(fixtures.last().title)).check(matches(isDisplayed()))
                openLibraryFilters()
                onView(withText("Hepsi")).perform(scrollTo(), click())
                onView(withText("Tamam")).perform(click())
                scenario.onActivity { activity ->
                    // Count comes from the real filtered list, including rows outside the viewport.
                    fun count(view: android.view.View): Int? = if (view is ListView) view.adapter.count else
                        (view as? android.view.ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { count(group.getChildAt(it)) } }
                    assertEquals(5, count(activity.window.decorView))
                }
            }
        } finally { fixtures.forEach { app.store.get(it.id)?.let(app.store::delete) } }
        assertTrue("Mevcut kütüphane korunmalı", before == app.store.all())
    }

    @Test fun conversationKeepsMessagesAndDraftOnFailureAndAllowsProposalApproval() {
        val before = app.store.all()
        val original = app.store.save(Series(title = "M4 Sohbet ${System.nanoTime()}", notes = "Uydurma test kaydı"))
        try {
            ActivityScenario.launch(AiActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val state = ViewModelProvider(activity)[AiActivity.ChatState::class.java]
                    state.history.add("user" to "Önceki mesaj"); state.history.add("assistant" to "Önceki yanıt")
                    state.sent = "Bölümü 25 yap"
                    state.pending = FutureTask<AiReply> { AiReply("Bölüm önerisi hazır", listOf(AiProposal(original, original.copy(lastChapter = "25")))) }.apply { run() }
                }
                scenario.recreate()
                onView(withText("AI\nBölüm önerisi hazır")).perform(scrollTo()).check(matches(isDisplayed()))
                assertEquals(original, app.store.get(original.id))
                onView(withText("Seç ve düzenle: ${original.title}")).perform(scrollTo(), click())
                onView(withText("Değişiklikleri onayla")).perform(scrollTo(), click())
                onView(withText("Onayla ve kaydet")).perform(click())
                onIdle()
                assertEquals("25", app.store.get(original.id)!!.lastChapter)
                pressBack()
                onView(withHint("Mesaj, seri adı veya bağlantısı")).perform(replaceText("Yeniden denenecek taslak"), closeSoftKeyboard())
                scenario.onActivity { activity ->
                    val state = ViewModelProvider(activity)[AiActivity.ChatState::class.java]
                    state.sent = "Yeniden denenecek taslak"
                    state.pending = FutureTask<AiReply> { throw IllegalArgumentException("Test bağlantı hatası") }.apply { run() }
                }
                scenario.recreate()
                onView(withText("Test bağlantı hatası")).perform(scrollTo()).check(matches(isDisplayed()))
                onView(withText("AI\nÖnceki yanıt")).perform(scrollTo()).check(matches(isDisplayed()))
                onView(withHint("Mesaj, seri adı veya bağlantısı")).check(matches(withText("Yeniden denenecek taslak")))
                onView(withText("Gönder")).check(matches(isEnabled()))
                scenario.onActivity { activity -> assertNull(ViewModelProvider(activity)[AiActivity.ChatState::class.java].pending) }
                val approved = app.store.get(original.id)!!
                scenario.onActivity { activity ->
                    val state = ViewModelProvider(activity)[AiActivity.ChatState::class.java]
                    state.sent = "30 önerisini hazırla"
                    state.pending = FutureTask<AiReply> { AiReply("Reddedilecek öneri", listOf(AiProposal(approved, approved.copy(lastChapter = "30")))) }.apply { run() }
                }
                scenario.recreate()
                onView(withText("Reddet: ${original.title}")).perform(scrollTo(), click())
                assertEquals(approved, app.store.get(original.id))
                scenario.onActivity { activity -> assertTrue(ViewModelProvider(activity)[AiActivity.ChatState::class.java].tokens.isEmpty()) }
            }
        } finally { app.store.get(original.id)?.let(app.store::delete) }
        assertTrue("Mevcut kütüphane korunmalı", before == app.store.all())
    }

    @Test fun actualSendRequiresConsentAndNetworkFailureKeepsEditableDraft() {
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE == "ranchu" || android.os.Build.FINGERPRINT.contains("generic"))
        val secrets = AiSecretStore(app)
        val before = app.store.all()
        // Reserved non-existent domain: exercises the real Android network/error path without a paid request.
        secrets.save(AiSettings("https://fixture.invalid/v1", "fixture"), "disposable-test-key")
        try {
            ActivityScenario.launch(AiActivity::class.java).use { scenario ->
                onView(withHint("Mesaj, seri adı veya bağlantısı")).perform(replaceText("Uydurma bağlantı testi"), closeSoftKeyboard())
                onView(withText("Gönder")).perform(click())
                onView(withText("İptal")).perform(click())
                assertFalse(secrets.consented())
                onView(withHint("Mesaj, seri adı veya bağlantısı")).check(matches(withText("Uydurma bağlantı testi")))
                scenario.onActivity { activity -> assertNull(ViewModelProvider(activity)[AiActivity.ChatState::class.java].pending) }
                onView(withText("Gönder")).perform(click())
                onView(withText("Paylaş ve gönder")).perform(click())
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                assertTrue("Gerçek ağ hatası kullanıcıya gösterilmeli", device.wait(Until.hasObject(By.text("AI bağlantısı kurulamadı. Ağını ve servis adresini kontrol et.")), 20_000))
                onView(withHint("Mesaj, seri adı veya bağlantısı")).check(matches(withText("Uydurma bağlantı testi")))
                onView(withText("Gönder")).check(matches(isEnabled()))
                onView(withHint("Mesaj, seri adı veya bağlantısı")).perform(replaceText("Düzenlenen taslak"))
                scenario.recreate()
                onView(withHint("Mesaj, seri adı veya bağlantısı")).check(matches(withText("Düzenlenen taslak")))
                assertTrue("Ağ hatası kayıt değiştirmemeli", before == app.store.all())
            }
        } finally { secrets.clear() }
    }

    @Test fun composerRemainsVisibleWithKeyboardInPortraitAndLandscape() {
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE == "ranchu" || android.os.Build.FINGERPRINT.contains("generic"))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val previousIme = device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        val previousHandwriting = device.executeShellCommand("settings get secure stylus_handwriting_enabled").trim()
        try {
            device.executeShellCommand("settings put secure stylus_handwriting_enabled 0")
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            device.setOrientationNatural()
            ActivityScenario.launch(AiActivity::class.java).use { scenario ->
                onView(withHint("Mesaj, seri adı veya bağlantısı")).perform(click(), replaceText("Ekran taslağı"))
                assertTrue("Dikey ekranda gerçek klavye açılmalı", device.wait(Until.hasObject(By.pkg("com.google.android.inputmethod.latin")), 10_000))
                onView(withText("Gönder")).check(matches(isCompletelyDisplayed()))
                device.pressBack()
                assertTrue(device.wait(Until.gone(By.pkg("com.google.android.inputmethod.latin")), 10_000))
                device.setOrientationLeft(); device.waitForIdle()
                // Rotation is asynchronous: UiDevice idle can precede Activity recreation.
                val rotationDeadline = System.nanoTime() + 10_000_000_000L
                var landscape = false
                while (!landscape && System.nanoTime() < rotationDeadline) {
                    scenario.onActivity { activity ->
                        landscape = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
                            activity.window.decorView.width > activity.window.decorView.height && activity.window.decorView.hasWindowFocus()
                    }
                    if (!landscape) Thread.sleep(50)
                }
                assertTrue("Yatay Activity ve yerleşim hazır olmalı", landscape)
                onView(withHint("Mesaj, seri adı veya bağlantısı")).check(matches(isEnabled()))
                // Read the rotated display again for the gesture that opens the real IME.
                val editor = device.findObject(androidx.test.uiautomator.UiSelector().className("android.widget.EditText")).bounds
                device.executeShellCommand("input touchscreen tap ${editor.centerX()} ${editor.centerY()}")
                val keyboardOpened = device.wait(Until.hasObject(By.pkg("com.google.android.inputmethod.latin")), 10_000)
                if (device.hasObject(By.text("Try out your stylus"))) {
                    device.pressBack()
                    assertTrue(device.wait(Until.gone(By.text("Try out your stylus")), 10_000))
                    device.executeShellCommand("input touchscreen tap ${editor.centerX()} ${editor.centerY()}")
                }
                device.takeScreenshot(java.io.File(app.getExternalFilesDir(null), "m4-landscape-keyboard.png"))
                assertTrue("Yatay ekranda gerçek klavye açılmalı", keyboardOpened)
                onView(withHint("Mesaj, seri adı veya bağlantısı")).check(matches(hasFocus()))
                assertFalse("Klavye sohbeti tam ekran metin editörüyle örtmemeli", device.hasObject(By.res("android:id/inputExtractEditText")))
                assertTrue("Gönder düğmesi klavye açıkken erişilebilir kalmalı", device.hasObject(By.text("Gönder")))
                onView(withText("Gönder")).check(matches(isCompletelyDisplayed()))
                onView(withHint("Mesaj, seri adı veya bağlantısı")).check(matches(withText("Ekran taslağı")))
            }
        } finally {
            device.setOrientationNatural(); device.unfreezeRotation()
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard $previousIme")
            device.executeShellCommand(if (previousHandwriting == "null") "settings delete secure stylus_handwriting_enabled" else "settings put secure stylus_handwriting_enabled $previousHandwriting")
        }
    }
}
