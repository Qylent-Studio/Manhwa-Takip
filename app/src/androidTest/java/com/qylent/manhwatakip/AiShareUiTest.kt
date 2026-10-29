package com.qylent.manhwatakip

import android.content.ClipboardManager
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import org.hamcrest.Matchers.not
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiShareUiTest {
    private val app get() = ApplicationProvider.getApplicationContext<LibraryApplication>()
    private val idle = ScreenIdle()
    @org.junit.Before fun before() {
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE == "ranchu" || android.os.Build.FINGERPRINT.contains("generic"))
        androidx.test.espresso.IdlingRegistry.getInstance().register(idle)
    }
    @org.junit.After fun after() { androidx.test.espresso.IdlingRegistry.getInstance().unregister(idle); idle.close() }
    private fun withShares(work: () -> Unit) {
        androidx.test.espresso.intent.Intents.init()
        try {
            androidx.test.espresso.intent.Intents.intending(androidx.test.espresso.intent.matcher.IntentMatchers.hasAction(Intent.ACTION_CHOOSER))
                .respondWith(android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null))
            work()
        } finally { androidx.test.espresso.intent.Intents.release() }
    }

    @Test fun selectedOptionsInvalidateOldOutputAndSurviveNonDefaultRecreation() {
        AiShareTestSupport.SelectionFixture(app).use { fixture ->
            ActivityScenario.launch(ChatGptActivity::class.java).use { scenario ->
                fixture.configure()
                val expected = AiShareTestSupport.output(scenario); fixture.assertProjection(expected)
                assertTrue("Fixture must exercise truncated preview", expected.length > 12000)
                scenario.recreate()
                onView(withSpinnerText("Okunuyor")).perform(scrollTo()).check(matches(isDisplayed()))
                onView(withText("Nedenler")).perform(scrollTo()).check(matches(isChecked()))
                scenario.onActivity {
                    val state = ViewModelProvider(it)[ChatGptActivity.State::class.java]
                    assertEquals(fixture.ids, state.selectedIds); assertEquals(ChatGptMode.RECOMMEND, state.mode)
                }
                assertEquals(expected, AiShareTestSupport.output(scenario))
                onView(withText("Son bölüm")).perform(scrollTo(), click())
                AiShareTestSupport.unavailable()
                onView(withText("Önizlemeyi oluştur")).perform(scrollTo(), click())
                assertFalse(JSONObject(AiShareTestSupport.output(scenario)).getJSONArray("series").getJSONObject(0).has("lastChapter"))
                AiShareTestSupport.filter("Hepsi"); AiShareTestSupport.unavailable()
                onView(withText("Önizlemeyi oluştur")).perform(scrollTo(), click())
                assertEquals(3, JSONObject(AiShareTestSupport.output(scenario)).getJSONArray("series").length())
                onView(withText("Seri seç")).perform(scrollTo(), click())
                onView(withText("Hepsi")).perform(click())
                AiShareTestSupport.unavailable()
            }
        }
    }

    @Test fun selectedPreviewAndStubbedSharesUseFullIdenticalSnapshot() = withShares {
        AiShareTestSupport.SelectionFixture(app).use { fixture ->
            ActivityScenario.launch(ChatGptActivity::class.java).use { scenario ->
                fixture.configure()
                val expected = AiShareTestSupport.output(scenario); fixture.assertProjection(expected)
                assertEquals(expected, AiShareTestSupport.sharedBytes("Çıktıyı paylaş").toString(Charsets.UTF_8))
                assertEquals(expected, AiShareTestSupport.sharedBytes("JSON paylaş").toString(Charsets.UTF_8))
                AiShareTestSupport.assertArchive(AiShareTestSupport.sharedBytes("ZIP paylaş"), expected, fixture.excluded)
                assertEquals(expected, AiShareTestSupport.output(scenario))
            }
        }
    }

    @Test fun approvedImportInvalidatesSnapshotAndRegenerationUpdatesCopyAndShares() = withShares {
        val suffix = System.nanoTime().toString()
        val original = app.store.save(Series(title = "Import old $suffix", lastChapter = "1"))
        val addedTitle = "Import new $suffix"
        try {
            ActivityScenario.launch(ChatGptActivity::class.java).use { scenario ->
                onView(withText("Son bölüm")).perform(scrollTo(), click())
                onView(withText("Serileri dışa aktar")).perform(scrollTo(), click())
                val before = AiShareTestSupport.output(scenario)
                AiShareTestSupport.approveImport("""{"series":[{"id":"${original.id}","lastChapter":"19"},{"title":"$addedTitle"}]}""")
                AiShareTestSupport.unavailable()
                onView(withText("Önizlemeyi oluştur")).perform(scrollTo(), click())
                val expected = AiShareTestSupport.output(scenario)
                assertNotEquals(before, expected)
                val rows = JSONObject(expected).getJSONArray("series").let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
                assertEquals("19", rows.single { it.getString("title") == original.title }.getString("lastChapter"))
                assertTrue(rows.any { it.getString("title") == addedTitle })
                assertEquals(expected, AiShareTestSupport.sharedBytes("Çıktıyı paylaş").toString(Charsets.UTF_8))
                assertEquals(expected, AiShareTestSupport.sharedBytes("JSON paylaş").toString(Charsets.UTF_8))
                AiShareTestSupport.assertArchive(AiShareTestSupport.sharedBytes("ZIP paylaş"), expected, emptyList())
                onView(withText("Çıktıyı kopyala")).perform(scrollTo(), click())
                scenario.onActivity { assertEquals(expected, it.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()) }
            }
        } finally { app.store.get(original.id)?.let(app.store::delete); app.store.all().singleOrNull { it.title == addedTitle }?.let(app.store::delete) }
    }

    @Test fun unchangedResumeKeepsSnapshotButSourceChangeOnReturnClearsIt() {
        val row = app.store.save(Series(title = "Resume ${System.nanoTime()}", lastChapter = "1"))
        try {
            ActivityScenario.launch(ChatGptActivity::class.java).use { scenario ->
                onView(withText("Seri adı (zorunlu)")).check(matches(not(isEnabled())))
                onView(withText("Serileri dışa aktar")).perform(scrollTo(), click())
                val expected = AiShareTestSupport.output(scenario)
                val projected = JSONObject(expected).getJSONArray("series").let { array -> (0 until array.length()).map { array.getJSONObject(it) } }.single { it.getString("title") == row.title }
                assertEquals(setOf("title"), projected.keys().asSequence().toSet())
                scenario.moveToState(Lifecycle.State.CREATED); scenario.moveToState(Lifecycle.State.RESUMED)
                assertEquals(expected, AiShareTestSupport.output(scenario))
                scenario.moveToState(Lifecycle.State.CREATED)
                app.store.save(row.copy(title = "Changed ${row.title}"))
                scenario.moveToState(Lifecycle.State.RESUMED)
                onView(withText("Kütüphane değişti; önizlemeyi yeniden oluştur.")).perform(scrollTo()).check(matches(isDisplayed()))
                AiShareTestSupport.unavailable()
                onView(withText("Önizlemeyi oluştur")).perform(scrollTo(), click())
                assertTrue(AiShareTestSupport.output(scenario).contains("Changed ${row.title}"))
            }
        } finally { app.store.get(row.id)?.let(app.store::delete) }
    }
}
