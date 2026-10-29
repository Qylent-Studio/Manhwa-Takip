package com.qylent.manhwatakip

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.IdlingRegistry
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import org.hamcrest.Matchers.startsWith
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.FutureTask

class LibraryRefreshUiTest {
    private val app get() = ApplicationProvider.getApplicationContext<Context>() as LibraryApplication
    @Test fun gridActionsAreSquareAndFiltersOnlyAppearInPanel() {
        TestDeviceSetup.prepare()
        val idle = ScreenIdle(); IdlingRegistry.getInstance().register(idle)
        val prefs = AppPreferences(app); val mode = prefs.viewMode; val favorites = prefs.favoritesOnly
        val record = app.store.save(Series(title = "Kart ${System.nanoTime()}", status = ReadingStatus.PAUSED))
        prefs.viewMode = "grid"; prefs.favoritesOnly = false
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                onView(withHint("Seri ara")).perform(replaceText(record.title), closeSoftKeyboard())
                onView(withText("Hepsi")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
                onView(withContentDescription("${record.title}: seri işlemleri")).check { view, error ->
                    if (error != null) throw error
                    assertTrue(view is ImageButton); assertEquals(view.width, view.height)
                    assertTrue(view.width >= (48 * view.resources.displayMetrics.density).toInt())
                }
                openLibraryFilters()
                onView(withText("Ara verilen")).perform(scrollTo(), click())
                onView(withText("Tamam")).perform(click())
                scenario.recreate()
                openLibraryFilters()
                onView(withText("Ara verilen")).check(matches(isChecked()))
                onView(withText("Sıfırla")).perform(click())
                onView(withContentDescription("Filtreler ve görünüm")).check(matches(isDisplayed()))
                onView(withContentDescription("${record.title}: seri işlemleri")).perform(click())
                onView(withText("Seriyi aç")).perform(click())
                onView(withText("Türkçe kaynaklarda kapak ara")).perform(scrollTo()).check(matches(isDisplayed()))
            }
        } finally {
            IdlingRegistry.getInstance().unregister(idle); idle.close()
            prefs.viewMode = mode; prefs.favoritesOnly = favorites; app.store.get(record.id)?.let(app.store::delete)
        }
    }

    @Test fun aiCoverPreviewSurvivesRotationAndDoesNotWriteUntilSave() {
        TestDeviceSetup.prepare()
        val idle = ScreenIdle(); IdlingRegistry.getInstance().register(idle)
        val record = app.store.save(Series(title = "Türkçe Kapak ${System.nanoTime()}"))
        val bitmap = Bitmap.createBitmap(8, 12, Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        bitmap.recycle()
        val found = CoverResult("https://okutoon.com/test", "https://om-cdn.okutoon.com/covers/test.jpg", bytes)
        try {
            ActivityScenario.launch<EditActivity>(Intent(app, EditActivity::class.java).putExtra("seriesId", record.id)).use { scenario ->
                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[EditActivity.SaveState::class.java].apply {
                        aiCoverTitle = record.title; aiCoverUrl = record.url
                        aiCoverPending = FutureTask { found }.apply { run() }
                    }
                }
                scenario.recreate()
                onView(withText(startsWith("Türkçe kaynaktaki seri kapağı bulundu"))).perform(scrollTo()).check(matches(isDisplayed()))
                assertEquals(record, app.store.get(record.id)); assertNull(app.covers.bytes(record.id))
                scenario.recreate()
                onView(withText(startsWith("Türkçe kaynaktaki seri kapağı bulundu"))).perform(scrollTo()).check(matches(isDisplayed()))
                onView(withText("Kaydet")).perform(scrollTo(), click())
                assertEquals(found.imageUrl, app.store.get(record.id)!!.coverUrl)
                assertNotNull(app.covers.bytes(record.id))
            }
        } finally {
            IdlingRegistry.getInstance().unregister(idle); idle.close()
            app.store.get(record.id)?.let(app.store::delete); app.covers.remove(record.id)
        }
    }
    @Test fun failedCoverSearchKeepsRecordAndUnlocksFormAfterRotation() {
        TestDeviceSetup.prepare()
        val idle = ScreenIdle(); IdlingRegistry.getInstance().register(idle)
        val record = app.store.save(Series(title = "Korunan Kapak ${System.nanoTime()}"))
        val pending = FutureTask<CoverResult> { throw IllegalArgumentException("Bu aramada Türkçe kapak bulunamadı.") }
        try {
            ActivityScenario.launch<EditActivity>(Intent(app, EditActivity::class.java).putExtra("seriesId", record.id)).use { scenario ->
                scenario.onActivity { activity -> ViewModelProvider(activity)[EditActivity.SaveState::class.java].aiCoverPending = pending }
                scenario.recreate()
                onView(withText("Kaydet")).perform(scrollTo()).check(matches(org.hamcrest.Matchers.not(isEnabled())))
                pending.run()
                scenario.recreate()
                onView(withText("Bu aramada Türkçe kapak bulunamadı.")).perform(scrollTo()).check(matches(isDisplayed()))
                onView(withText("Kaydet")).perform(scrollTo()).check(matches(isEnabled()))
                assertEquals(record, app.store.get(record.id)); assertNull(app.covers.bytes(record.id))
            }
        } finally {
            IdlingRegistry.getInstance().unregister(idle); idle.close(); pending.cancel(true)
            app.store.get(record.id)?.let(app.store::delete)
        }
    }
}
