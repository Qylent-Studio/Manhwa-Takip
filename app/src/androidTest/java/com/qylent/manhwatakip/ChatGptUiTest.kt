package com.qylent.manhwatakip

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.matcher.RootMatchers.isDialog
import org.junit.Test
import org.junit.Assert.*
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject

class ChatGptUiTest {
    private val idle = ScreenIdle()
    @org.junit.Before fun registerIdle() { androidx.test.espresso.IdlingRegistry.getInstance().register(idle) }
    @org.junit.After fun unregisterIdle() { androidx.test.espresso.IdlingRegistry.getInstance().unregister(idle); idle.close() }
    @Test fun exportsCopyPurposeSpecificJsonAndKeepOutputAcrossRotation() {
        ActivityScenario.launch(ChatGptActivity::class.java).use { scenario ->
            onView(withText("Serileri dışa aktar")).perform(scrollTo(), click())
            onView(withText("Çıktıyı kopyala")).perform(scrollTo(), click())
            scenario.onActivity { activity ->
                val text = activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
                assertTrue(JSONObject(text).getString("prompt").contains("Türkçe"))
            }
            onView(withText("Yeni öneri için dışa aktar")).perform(scrollTo(), click())
            scenario.recreate()
            onView(withText("Çıktıyı kopyala")).perform(scrollTo(), click())
            scenario.onActivity { activity ->
                val text = activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
                assertTrue(JSONObject(text).getString("prompt").contains("ücretsiz"))
            }
            onView(withText("Özet için dışa aktar")).perform(scrollTo(), click())
            onView(withText("Çıktıyı kopyala")).perform(scrollTo(), click())
            scenario.onActivity { activity ->
                val text = activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
                assertTrue(JSONObject(text).getString("prompt").contains("spoiler"))
            }
        }
    }
    @Test fun pastePreviewCancelRecreationAndApprovalKeepOtherFields() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as LibraryApplication
        val current = app.store.save(Series(title = "ChatGPT UI ${System.nanoTime()}", lastChapter = "10", notes = "Telefon notu", favorite = true))
        val text = """{"series":[{"id":"${current.id}","lastChapter":"12"}]}"""
        try {
            val before = app.store.all()
            ActivityScenario.launch(ChatGptActivity::class.java).use { scenario ->
                onView(withHint("ChatGPT JSON'unu yapıştır")).perform(scrollTo(), replaceText(text), closeSoftKeyboard())
                onView(withText("Yapıştırılan JSON'u önizle")).perform(scrollTo(), click())
                onView(withText("ChatGPT içe aktarma önizlemesi")).inRoot(isDialog()).check(matches(isDisplayed()))
                assertEquals(before, app.store.all())
                onView(withText("İptal")).inRoot(isDialog()).perform(click())
                assertEquals(before, app.store.all())
                scenario.onActivity { it.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("test", text)) }
                onView(withText("Panodan içe aktar")).perform(scrollTo(), click())
                scenario.recreate()
                onView(withText("ChatGPT içe aktarma önizlemesi")).inRoot(isDialog()).check(matches(isDisplayed()))
                onView(withText("Güncellenecek: ${current.title}")).inRoot(isDialog()).perform(click())
                onView(withText(containsString("Son bölüm: 10 → 12"))).inRoot(isDialog()).check(matches(isDisplayed()))
                onView(withText("Kapat")).inRoot(isDialog()).perform(click())
                onView(withText("Onayla ve içe aktar")).inRoot(isDialog()).perform(click())
                onView(withText("1 seri değişikliği uygulandı.")).perform(scrollTo()).check(matches(isDisplayed()))
                assertEquals("12", app.store.get(current.id)!!.lastChapter); assertEquals(current.notes, app.store.get(current.id)!!.notes)
                assertTrue(app.store.get(current.id)!!.favorite)
            }
        } finally { app.store.get(current.id)?.let(app.store::delete) }
    }
    @Test fun shareCarriesTheFullJsonInTheAndroidChooser() {
        androidx.test.espresso.intent.Intents.init()
        try {
            androidx.test.espresso.intent.Intents.intending(androidx.test.espresso.intent.matcher.IntentMatchers.hasAction(android.content.Intent.ACTION_CHOOSER))
                .respondWith(android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null))
            ActivityScenario.launch(ChatGptActivity::class.java).use {
                onView(withText("Serileri dışa aktar")).perform(scrollTo(), click())
                onView(withText("Çıktıyı paylaş")).perform(scrollTo(), click())
                val chooser = androidx.test.espresso.intent.Intents.getIntents().last { it.action == android.content.Intent.ACTION_CHOOSER }
                @Suppress("DEPRECATION")
                val shared = chooser.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
                assertEquals("text/plain", shared.type)
                assertEquals("manhwa-chatgpt", JSONObject(shared.getStringExtra(android.content.Intent.EXTRA_TEXT)!!).getString("format"))
            }
        } finally { androidx.test.espresso.intent.Intents.release() }
    }
}
