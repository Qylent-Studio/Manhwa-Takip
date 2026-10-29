package com.qylent.manhwatakip

import android.content.Context
import android.content.Intent
import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.speech.RecognizerIntent
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import androidx.test.espresso.IdlingRegistry

class AiUiTest {
    private val idle = ScreenIdle()
    private lateinit var chatFixture: ChatFixture
    @Before fun setup() { TestDeviceSetup.prepare(); chatFixture = ChatFixture(); IdlingRegistry.getInstance().register(idle) }
    @After fun cleanup() { IdlingRegistry.getInstance().unregister(idle); idle.close(); chatFixture.close() }
    @Test fun proposalPreviewAndRejectionNeverWriteAndApprovalSurvivesRotation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as LibraryApplication
        val original = app.store.save(Series(title = "AI UI ${System.nanoTime()}", notes = "Korunacak"))
        val token = "ui-${System.nanoTime()}"
        app.proposals[token] = AiProposal(original, original.copy(lastChapter = "25"))
        val intent = Intent(context, EditActivity::class.java).putExtra("proposalId", token)
        ActivityScenario.launch<EditActivity>(intent).use { scenario ->
            onView(withHint("Son okunan bölüm")).check(matches(withText("25")))
            assertEquals("", app.store.get(original.id)!!.lastChapter)
            app.proposals.remove(token) // Simulate the application cache disappearing on process recreation.
            scenario.recreate()
            onView(withHint("Son okunan bölüm")).check(matches(withText("25")))
            onView(withText("Değişiklikleri onayla")).perform(scrollTo(), click())
            onView(withText("Onayla ve kaydet")).perform(click())
            Thread.sleep(500)
            assertEquals("25", app.store.get(original.id)!!.lastChapter)
            assertEquals("Korunacak", app.store.get(original.id)!!.notes)
        }
        val approved = app.store.get(original.id)!!
        app.proposals[token] = AiProposal(approved, approved.copy(lastChapter = "30"))
        ActivityScenario.launch<EditActivity>(intent).use {
            onView(withText("Öneriyi reddet")).perform(scrollTo(), click())
            assertEquals("25", app.store.get(original.id)!!.lastChapter)
            assertFalse(app.proposals.containsKey(token))
        }
    }

    @Test fun chatDraftSurvivesRotation() {
        ActivityScenario.launch(AiActivity::class.java).use { scenario ->
            onView(withHint("Mesaj, seri adı veya bağlantısı")).perform(replaceText("Taslak mesaj"), closeSoftKeyboard())
            scenario.recreate()
            onView(withHint("Mesaj, seri adı veya bağlantısı")).check(matches(withText("Taslak mesaj")))
            onView(withText("Gönder")).check(matches(isDisplayed()))
        }
    }

    @Test fun speechResultIsEditableAndNeverAutomaticallySends() {
        Intents.init()
        try {
            val result = Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf("Yirminci bölümü okudum"))
            Intents.intending(hasAction(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)).respondWith(ActivityResult(Activity.RESULT_OK, result))
            ActivityScenario.launch(AiActivity::class.java).use { scenario ->
                onView(withContentDescription("Sesle yazdır")).perform(click())
                Intents.intended(hasAction(RecognizerIntent.ACTION_RECOGNIZE_SPEECH))
                onView(withHint("Mesaj, seri adı veya bağlantısı")).check(matches(withText("Yirminci bölümü okudum")))
                onView(withHint("Mesaj, seri adı veya bağlantısı")).perform(replaceText("21. bölümü okudum"), closeSoftKeyboard())
                scenario.onActivity { activity ->
                    val state = androidx.lifecycle.ViewModelProvider(activity)[AiActivity.ChatState::class.java]
                    assertNull(state.pending)
                    assertTrue(state.history.isEmpty())
                }
                scenario.recreate()
                onView(withHint("Mesaj, seri adı veya bağlantısı")).check(matches(withText("21. bölümü okudum")))
            }
        } finally { Intents.release() }
    }

    @Test fun cancelledSpeechKeepsDraftAndDoesNotSend() {
        Intents.init()
        try {
            Intents.intending(hasAction(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)).respondWith(ActivityResult(Activity.RESULT_CANCELED, null))
            ActivityScenario.launch(AiActivity::class.java).use { scenario ->
                onView(withHint("Mesaj, seri adı veya bağlantısı")).perform(replaceText("Korunacak taslak"), closeSoftKeyboard())
                onView(withContentDescription("Sesle yazdır")).perform(click())
                onView(withHint("Mesaj, seri adı veya bağlantısı")).check(matches(withText("Korunacak taslak")))
                scenario.onActivity { activity -> assertNull(androidx.lifecycle.ViewModelProvider(activity)[AiActivity.ChatState::class.java].pending) }
            }
        } finally { Intents.release() }
    }

    @Test fun localSettingsSaveWithoutReusingPreviousProvidersKey() {
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE == "ranchu" || android.os.Build.FINGERPRINT.contains("generic"))
        val app = ApplicationProvider.getApplicationContext<Context>()
        val secrets = AiSecretStore(app)
        val previous = AiSettings("https://fixture.invalid/v1", "fixture")
        secrets.save(previous, "previous-provider-test-key")
        secrets.consent(previous)
        try {
            ActivityScenario.launch(AiSettingsActivity::class.java).use {
                onView(withHint("Servis temel adresi")).perform(replaceText(AiSettings.LOCAL_BASE_URL), closeSoftKeyboard())
                onView(withHint("Model adı")).perform(replaceText("Privite"), closeSoftKeyboard())
                onView(withText("Ayarları kaydet")).perform(scrollTo(), click())
                androidx.test.espresso.Espresso.onIdle()
                assertEquals(AiSettings(AiSettings.LOCAL_BASE_URL, "Privite"), secrets.settings())
                assertEquals("", secrets.connection()!!.key)
                assertFalse(secrets.consented())
            }
        } finally { secrets.clear() }
    }

    @Test fun settingsRotationKeepsAddressAndModelButNeverTypedKey() {
        ActivityScenario.launch(AiSettingsActivity::class.java).use { scenario ->
            onView(withHint("Servis temel adresi")).perform(replaceText(AiSettings.LOCAL_BASE_URL), closeSoftKeyboard())
            onView(withHint("Model adı")).perform(replaceText("Privite"), closeSoftKeyboard())
            onView(withHint("API anahtarı (mevcut anahtarı korumak için boş)")).perform(scrollTo(), replaceText("transient-test-key"), closeSoftKeyboard())
            scenario.recreate()
            onView(withHint("Servis temel adresi")).check(matches(withText(AiSettings.LOCAL_BASE_URL)))
            onView(withHint("Model adı")).check(matches(withText("Privite")))
            onView(withHint("API anahtarı (mevcut anahtarı korumak için boş)")).check(matches(withText("")))
        }
    }
}
