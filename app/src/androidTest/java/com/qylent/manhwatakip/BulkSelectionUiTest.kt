package com.qylent.manhwatakip

import android.content.Context
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.IdlingRegistry
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import org.hamcrest.Matchers.not
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class BulkSelectionUiTest {
    @Test fun selectAllRespectsSearchAndStatusAndSurvivesRotationWithoutSaving() {
        assumeTrue(Build.HARDWARE == "ranchu" || Build.FINGERPRINT.contains("generic"))
        TestDeviceSetup.prepare()
        val app = ApplicationProvider.getApplicationContext<Context>() as LibraryApplication
        val idle = ScreenIdle(); IdlingRegistry.getInstance().register(idle)
        val prefix = "Hepsi testi ${System.nanoTime()}"
        val records = listOf(Series(title = "$prefix Bir"), Series(title = "$prefix İki"),
            Series(title = "$prefix Ara", status = ReadingStatus.PAUSED), Series(title = "Dışarıda ${System.nanoTime()}"))
            .map(app.store::save)
        val prefs = AppPreferences(app); val favorites = prefs.favoritesOnly; prefs.favoritesOnly = false
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                onView(withText("Hepsini seç")).check(matches(not(isDisplayed())))
                onView(withHint("Seri ara")).perform(replaceText(prefix), closeSoftKeyboard())
                openLibraryFilters()
                onView(withText("Okunacak")).perform(scrollTo(), click())
                onView(withText("Tamam")).perform(click())
                onView(withText("Seç")).perform(click())
                onView(withText("Hepsini seç")).perform(click())
                records.take(2).forEach { onView(withContentDescription("${it.title}: seç")).check(matches(isChecked())) }
                onView(withText("Hepsini seç")).check(matches(not(isEnabled())))
                scenario.recreate()
                records.take(2).forEach { onView(withContentDescription("${it.title}: seç")).check(matches(isChecked())) }
                onView(withContentDescription("${records.first().title}: seç")).perform(click())
                onView(withText("Hepsini seç")).check(matches(isEnabled())).perform(click())
                onView(withText("Düzenle (2)")).perform(click())
                onView(withText("Türkçe kaynaklarda kapak bul")).perform(click())
                TestWindowReadiness.awaitFocusedDialog("2 seri için kapak bul")
                onView(withText("2 seri için kapak bul")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check(matches(isDisplayed()))
                onView(withText("Vazgeç")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
                openLibraryFilters()
                onView(withText("Sıfırla")).perform(click())
                onView(withContentDescription("${records[2].title}: seç")).check(matches(not(isChecked())))
                onView(withHint("Seri ara")).perform(replaceText("Hiç sonuç yok ${System.nanoTime()}"), closeSoftKeyboard())
                onView(withText("Hepsini seç")).check(matches(not(isEnabled())))
                onView(withText("Seçimi kaldır")).check(matches(isEnabled())).perform(click())
                onView(withText("Seçimi kaldır")).check(matches(not(isEnabled())))
                onView(withHint("Seri ara")).perform(replaceText(prefix), closeSoftKeyboard())
                records.take(3).forEach { onView(withContentDescription("${it.title}: seç")).check(matches(not(isChecked()))) }
                assertEquals(records, records.map { app.store.get(it.id) })
                TestWindowReadiness.capture("selection trailing cancellation/filter/clear/no-write assertions completed")
            }
        } catch (failure: Throwable) {
            try { TestWindowReadiness.capture("selection failure: ${failure.javaClass.simpleName}") }
            catch (diagnosticFailure: Throwable) { failure.addSuppressed(diagnosticFailure) }
            throw failure
        } finally {
            IdlingRegistry.getInstance().unregister(idle); idle.close(); prefs.favoritesOnly = favorites
            records.forEach { app.store.get(it.id)?.let(app.store::delete) }
        }
    }
}
