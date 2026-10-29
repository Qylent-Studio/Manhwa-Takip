package com.qylent.manhwatakip

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.IdlingRegistry
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.Assert.*

class CoverUiAcceptanceTest {
    @Test fun pastedLinkPreparesCoverSurvivesRotationAndSavesWithoutGallery() {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>() as LibraryApplication
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val idle = ScreenIdle(); IdlingRegistry.getInstance().register(idle)
        val title = "Kapak UI ${System.nanoTime()}"
        try {
            ActivityScenario.launch(EditActivity::class.java).use { scenario ->
                onView(withHint("Seri adı")).perform(replaceText(title), closeSoftKeyboard())
                onView(withHint("Okuma bağlantısı (isteğe bağlı)")).perform(replaceText("https://golgebahcesi.com/manga/golgelerdeki-hukumdar-olmak-istiyorum/bolum/chapter-1"), closeSoftKeyboard())
                assertTrue("Bağlantı galeriyi açmadan kaynak kapağını hazırlamalı", device.wait(Until.hasObject(By.textStartsWith("Kaynağın kapağı hazır")), 45_000))
                scenario.recreate()
                onView(withText("Kaydet")).perform(scrollTo(), click())
                onView(withText(title)).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
                val record = app.store.all().single { it.title == title }
                assertTrue(record.coverUrl.startsWith("https://")); assertNotNull(app.covers.bytes(record.id))
                onView(withText("Kapağı getir / yenile")).perform(scrollTo(), click())
                assertTrue("Mevcut serinin kapağı yenilenmeli", device.wait(Until.hasObject(By.text("Kapağı getir / yenile")), 45_000))
                androidx.test.espresso.Espresso.onIdle()
                assertNotNull(CoverStore(app).load(record.id))
                assertEquals(1, app.store.all().count { it.title == title })
            }
        } finally {
            IdlingRegistry.getInstance().unregister(idle); idle.close()
            app.store.all().filter { it.title == title }.forEach { record -> app.store.delete(record); java.io.File(app.filesDir, "covers/${record.id}.jpg").delete() }
        }
    }
}
