package com.qylent.manhwatakip

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertEquals
import org.junit.Test

class CoverEntryUiTest {
    @Test fun chatOpensCoverSearchAndAlternativeSearchKeepsRecordUntilSaved() {
        val app = ApplicationProvider.getApplicationContext<LibraryApplication>()
        val row = app.store.save(Series(title = "Kapak düğmesi ${System.nanoTime()}", coverUrl = "https://cdn.trmanga.com/old.jpg"))
        val idle = ScreenIdle(); androidx.test.espresso.IdlingRegistry.getInstance().register(idle)
        try {
            ActivityScenario.launch(AiActivity::class.java).use {
                onView(withContentDescription("Sohbet araçları")).perform(click())
                onView(withText("Kapak ara")).perform(click())
                onView(withText("Kapak aranacak seri")).check(matches(isDisplayed()))
                onView(withText(row.title)).perform(click())
                onView(allOf(withText("Türkçe kaynaklarda kapak ara"), isAssignableFrom(android.widget.TextView::class.java), isDisplayed())).check(matches(isDisplayed()))
                onView(withText("İptal")).perform(click())
                onView(withText("Farklı kaynaktan bul")).perform(scrollTo(), click())
                onView(withText(org.hamcrest.Matchers.containsString("Önceki kaynak ve görsel atlanacak."))).check(matches(isDisplayed()))
                onView(withText("İptal")).perform(click())
                assertEquals(row, app.store.get(row.id))
                androidx.test.espresso.Espresso.pressBack()
            }
        } finally {
            androidx.test.espresso.IdlingRegistry.getInstance().unregister(idle); idle.close()
            app.store.get(row.id)?.let(app.store::delete)
        }
    }
}
