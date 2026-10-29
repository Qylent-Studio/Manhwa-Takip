package com.qylent.manhwatakip

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.Assert.*
import androidx.test.espresso.IdlingRegistry
import android.content.Intent

class UserSettingsUiTest {
    private lateinit var chats: ChatFixture
    private val idle = ScreenIdle()
    private val app get() = ApplicationProvider.getApplicationContext<Context>() as LibraryApplication
    @Before fun setup() { TestDeviceSetup.prepare(); chats = ChatFixture(); IdlingRegistry.getInstance().register(idle) }
    @After fun cleanup() { IdlingRegistry.getInstance().unregister(idle); idle.close(); chats.close() }
    @Test fun committedApprovalSucceedsWhenChatCleanupFails() {
        val original = app.store.save(Series(title = "Temizlik ${System.nanoTime()}", notes = "Korunacak"))
        val token = "cleanup-${System.nanoTime()}"
        val proposal = AiProposal(original, original.copy(lastChapter = "25"))
        app.proposals[token] = proposal
        app.chats.save(ChatRecord(proposals = listOf(BridgePending(token, proposal))))
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(app.getDatabasePath(chats.name).path, null, 0)
        db.execSQL("CREATE TRIGGER deny_cleanup BEFORE DELETE ON chat_proposals BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        try {
            ActivityScenario.launch<EditActivity>(Intent(app, EditActivity::class.java).putExtra("proposalId", token)).use {
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                onView(withText("Değişiklikleri onayla")).perform(scrollTo(), click())
                onView(withText("Onayla ve kaydet")).perform(click())
                androidx.test.espresso.Espresso.onIdle()
                assertEquals("25", app.store.get(original.id)!!.lastChapter)
                assertFalse("Başarılı kayıt öneriyi tüketmeli", app.proposals.containsKey(token))
                assertEquals("Korunacak", app.store.get(original.id)!!.notes)
            }
        } finally {
            db.execSQL("DROP TRIGGER deny_cleanup"); db.close()
            app.proposals.remove(token); app.store.get(original.id)?.let(app.store::delete)
        }
    }
    @Test fun leavingChatKeepsHistoryAndOnlyNewChatStartsEmpty() {
        ActivityScenario.launch(AiActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                ViewModelProvider(activity)[AiActivity.ChatState::class.java].history.add("assistant" to "Kalıcı sohbet testi")
            }
            scenario.recreate()
            onView(withText("AI\nKalıcı sohbet testi")).perform(scrollTo()).check(matches(isDisplayed()))
        }
        app.executor.submit {}.get()
        // Recreate application chat state as after process death; SQLite is the source.
        app.chatState = AiActivity.ChatState()
        ActivityScenario.launch(AiActivity::class.java).use {
            onView(withText("AI\nKalıcı sohbet testi")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Yeni sohbet")).perform(click())
            onView(withHint("Mesaj, seri adı veya bağlantısı")).check(matches(withText("")))
            onView(withText("Sohbetler")).perform(click())
            onView(withText("Kalıcı sohbet testi")).perform(click())
            onView(withText("AI\nKalıcı sohbet testi")).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }
    @Test fun themeChoiceSurvivesSettingsRecreation() {
        val original = AppPreferences(app).theme
        try {
            ActivityScenario.launch(AppSettingsActivity::class.java).use { scenario ->
                onView(withText("Tema seç")).perform(click())
                onView(withText("Açık")).perform(click())
                onView(withText("Tema seç")).check(matches(isDisplayed()))
                assertEquals("light", AppPreferences(app).theme); assertFalse(Ui.dark)
                scenario.recreate()
                assertFalse(Ui.dark)
                onView(withText("Tema seç")).perform(click())
                onView(withText("Karanlık")).perform(click())
                onView(withText("Tema seç")).check(matches(isDisplayed()))
                assertEquals("dark", AppPreferences(app).theme); assertTrue(Ui.dark)
            }
        } finally { AppPreferences(app).theme = original }
    }
    @Test fun createAndSelectMultipleCategoriesAndFilterAfterReopen() {
        val before = app.store.all()
        val prefix = "Özel ${System.nanoTime()}"
        val names = listOf("$prefix Harem", "$prefix Macera")
        val entry = app.store.save(Series(title = "$prefix Seri", status = ReadingStatus.READING, notes = "Korunacak"))
        try {
            ActivityScenario.launch(CategoriesActivity::class.java).use {
                names.forEach { name ->
                    onView(withText("Kategori oluştur")).perform(scrollTo(), click())
                    onView(withHint("Kategori adı")).perform(replaceText(name), closeSoftKeyboard())
                    onView(withText("Kaydet")).perform(click())
                    onView(withContentDescription("Kategoriyi yönet: $name")).perform(scrollTo()).check(matches(isDisplayed()))
                }
            }
            val intent = Intent(app, EditActivity::class.java).putExtra("seriesId", entry.id)
            ActivityScenario.launch<EditActivity>(intent).use { scenario ->
                onView(withText("Kategori seç")).perform(scrollTo(), click())
                onView(withHint("Kategori ara")).perform(replaceText(prefix), closeSoftKeyboard())
                names.forEach { onView(withText(it)).perform(click()) }
                onView(withText("Seçimi uygula")).perform(click())
                scenario.recreate()
                onView(withText("Kategori seç")).perform(scrollTo(), click())
                onView(withHint("Kategori ara")).perform(replaceText(prefix), closeSoftKeyboard())
                names.forEach { onView(withText(it)).check(matches(isChecked())) }
                onView(withText("Seçimi uygula")).perform(click())
                onView(withText("Kaydet")).perform(scrollTo(), click())
                androidx.test.espresso.Espresso.onIdle()
            }
            assertEquals(names, app.store.get(entry.id)!!.categories)
            assertEquals(ReadingStatus.READING, app.store.get(entry.id)!!.status)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                onView(withHint("Seri ara")).perform(replaceText(prefix), closeSoftKeyboard())
                openLibraryFilters()
                onView(withText(names[0])).perform(scrollTo(), click())
                onView(withText("Tamam")).perform(click())
                onView(withText(entry.title)).check(matches(isDisplayed()))
                scenario.recreate()
                openLibraryFilters()
                onView(withText(names[0])).check(matches(isChecked()))
                onView(withText("Tamam")).perform(click())
                onView(withText(entry.title)).check(matches(isDisplayed()))
            }
        } finally {
            app.store.get(entry.id)?.let(app.store::delete)
            names.forEach { if (it in app.store.categories()) app.store.changeCategory(it, null) }
        }
        assertTrue("Mevcut kütüphane korunmalı", before == app.store.all())
    }
}
