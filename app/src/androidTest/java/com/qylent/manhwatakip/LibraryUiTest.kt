package com.qylent.manhwatakip

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.net.Uri
import android.widget.ListView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.IdlingRegistry
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.equalTo
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LibraryUiTest {
    private val idle = ScreenIdle()
    @Before fun setup() { IdlingRegistry.getInstance().register(idle) }
    @After fun cleanup() { IdlingRegistry.getInstance().unregister(idle); idle.close() }
    private fun uniqueTitle() = "IŞIK VE İNCİ ${UUID.randomUUID().toString().take(8)}"

    @Test fun addEditAndReopenKeepsChapterStatusAndReasons() {
        val title = uniqueTitle()
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withContentDescription("Seri ekle")).perform(click())
            onView(withHint("Seri adı")).perform(replaceText(title), closeSoftKeyboard())
            onView(withHint("Son okunan bölüm")).perform(replaceText("48,5"), closeSoftKeyboard())
            onView(withHint("Son okunan bölüm")).check(matches(withText("48,5")))
            onView(withContentDescription("Okuma durumu")).perform(scrollTo(), click())
            onData(equalTo("Ara verildi")).perform(click())
            onView(withText("Diğer bilgiler")).perform(scrollTo(), click())
            onView(withContentDescription("Beğeni")).perform(scrollTo(), click())
            onData(equalTo("Sevdim")).perform(click())
            onView(withHint("Neden sevdim?")).perform(scrollTo(), replaceText("Karakterler"), closeSoftKeyboard())
            onView(withText("Kaydet")).perform(scrollTo(), click())
            onView(withText(title)).check(matches(isDisplayed()))
            onView(withText("48.5")).check(matches(isDisplayed()))
            onView(withText("Düzenle")).perform(click())
            onView(withHint("Son okunan bölüm")).perform(replaceText("51"), closeSoftKeyboard())
            onView(withText("Kaydet")).perform(scrollTo(), click())
            pressBack()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withHint("Seri ara")).perform(replaceText(title), closeSoftKeyboard())
            onView(allOf(withText(title), isDescendantOfA(isAssignableFrom(ListView::class.java)))).perform(click())
            onView(withText("51")).check(matches(isDisplayed()))
            onView(withText("Ara verildi")).check(matches(isDisplayed()))
            onView(withText("Karakterler")).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }

    @Test fun deletionNeedsConfirmationAndPreservesOtherSeries() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val selected = Series(title = uniqueTitle(), notes = "Silinecek test notu")
        val other = Series(title = uniqueTitle(), notes = "Korunacak test notu")
        LibraryStore(context).use { it.save(selected); it.save(other) }
        val intent = Intent(context, DetailActivity::class.java).putExtra("seriesId", selected.id)
        ActivityScenario.launch<DetailActivity>(intent).use {
            onView(withText("Seriyi sil")).perform(scrollTo(), click())
            onView(withText("Seri silinsin mi?")).inRoot(isDialog()).check(matches(isDisplayed()))
            assertEquals(selected, LibraryStore(context).use { it.get(selected.id) })
            onView(withText("Vazgeç")).inRoot(isDialog()).perform(click())
            assertEquals(selected, LibraryStore(context).use { it.get(selected.id) })
            onView(withText("Seriyi sil")).perform(scrollTo(), click())
            onView(withText("Sil")).inRoot(isDialog()).perform(click())
            androidx.test.espresso.Espresso.onIdle()
        }
        LibraryStore(context).use {
            assertNull(it.get(selected.id))
            assertEquals(other, it.get(other.id))
        }
    }

    @Test fun draftSurvivesActivityRecreationAndBackRequiresDiscardConfirmation() {
        ActivityScenario.launch(EditActivity::class.java).use { scenario ->
            onView(withHint("Seri adı")).perform(replaceText("Taslak adı"), closeSoftKeyboard())
            scenario.recreate()
            onView(withHint("Seri adı")).check(matches(withText("Taslak adı")))
            pressBack()
            onView(withText("Değişikliklerden vazgeçilsin mi?")).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText("Düzenlemeye devam et")).inRoot(isDialog()).perform(click())
            onView(withHint("Seri adı")).check(matches(withText("Taslak adı")))
        }
    }

    @Test fun invalidFormRemainsOpenAndDoesNotCreateRecord() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val before = LibraryStore(context).use { it.all() }
        ActivityScenario.launch(EditActivity::class.java).use {
            onView(withText("Kaydet")).perform(scrollTo(), click())
            onView(withHint("Seri adı")).perform(scrollTo()).check(matches(isDisplayed()))
        }
        assertEquals(before, LibraryStore(context).use { it.all() })
    }

    @Test fun readingLinkUsesExternalBrowserIntent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = Series(title = uniqueTitle(), url = "https://example.com/read/48")
        LibraryStore(context).use { it.save(entry) }
        Intents.init()
        try {
            Intents.intending(hasAction(Intent.ACTION_VIEW)).respondWith(ActivityResult(Activity.RESULT_OK, null))
            val intent = Intent(context, DetailActivity::class.java).putExtra("seriesId", entry.id)
            ActivityScenario.launch<DetailActivity>(intent).use {
                onView(withText("Okumaya git")).perform(click())
                Intents.intended(allOf(hasAction(Intent.ACTION_VIEW), hasData("https://example.com/read/48")))
            }
        } finally { Intents.release() }
    }

    @Test fun turkishSearchAndStatusFilterSelectTheCorrectSeries() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val title = uniqueTitle()
        LibraryStore(context).use { it.save(Series(title = title, status = ReadingStatus.PAUSED)) }
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withHint("Seri ara")).perform(replaceText(title.lowercase(java.util.Locale.forLanguageTag("tr-TR"))), closeSoftKeyboard())
            onView(withText(title)).check(matches(isDisplayed()))
            openLibraryFilters()
            onView(withText("Ara verilen")).perform(scrollTo(), click())
            onView(withText("Tamam")).perform(click())
            onView(withText(title)).check(matches(isDisplayed()))
            openLibraryFilters()
            onView(withText("Tamamlandı")).perform(scrollTo(), click())
            onView(withText("Tamam")).perform(click())
            onView(withText("Sonuç bulunamadı")).check(matches(isDisplayed()))
        }
    }

    @Test fun cancelledImportDoesNotChangeTheLibrary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val before = LibraryStore(context).use { it.all() }
        Intents.init()
        try {
            Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_CANCELED, null))
            ActivityScenario.launch(MainActivity::class.java).use {
                onView(withContentDescription("Gezinme: Ayarlar")).perform(click())
                onView(withText("Yedekler")).perform(scrollTo(), click())
                onView(withText("Yedekten geri yükle")).perform(click())
            }
        } finally { Intents.release() }
        assertEquals(before, LibraryStore(context).use { it.all() })
    }

    @Test fun unavailableExportDestinationPreservesTheLibrary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val before = LibraryStore(context).use { it.all() }
        Intents.init()
        try {
            val unavailable = Intent().setData(Uri.parse("content://com.qylent.manhwatakip.unavailable/test.json"))
            Intents.intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_OK, unavailable))
            ActivityScenario.launch(MainActivity::class.java).use {
                onView(withContentDescription("Gezinme: Ayarlar")).perform(click())
                onView(withText("Yedekler")).perform(scrollTo(), click())
                onView(withText("Dosyaya yedekle")).inRoot(isDialog()).perform(click())
                onView(withText("Dosya seç")).inRoot(isDialog()).perform(click())
                onView(allOf(withText("Ayarlar"), isDisplayed())).check(matches(isDisplayed()))
                onView(withText("Yedekler")).check(matches(isEnabled()))
            }
        } finally { Intents.release() }
        assertEquals(before, LibraryStore(context).use { it.all() })
    }
}
