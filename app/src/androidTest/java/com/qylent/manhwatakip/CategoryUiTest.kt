package com.qylent.manhwatakip

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.RootMatchers.isDialog
import org.hamcrest.CoreMatchers.equalTo
import androidx.test.espresso.IdlingRegistry
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class CategoryUiTest {
    private val idle = ScreenIdle()
    private val app get() = ApplicationProvider.getApplicationContext<Context>() as LibraryApplication
    @Before fun setup() { TestDeviceSetup.prepare(); IdlingRegistry.getInstance().register(idle) }
    @After fun cleanup() { IdlingRegistry.getInstance().unregister(idle); idle.close() }
    @Test fun longCategoryNameWrapsWithLargeTextAndKeepsActionsAccessible() {
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE == "ranchu" || android.os.Build.FINGERPRINT.contains("generic"))
        val device = androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
        val scale = device.executeShellCommand("settings get system font_scale").trim()
        val theme = AppPreferences(app).theme
        val name = "Uzun kategori " + "A".repeat(66)
        app.store.addCategory(name, "blue")
        try {
            device.executeShellCommand("settings put system font_scale 1.3")
            for (mode in listOf("dark", "light")) {
                AppPreferences(app).theme = mode
                ActivityScenario.launch(CategoriesActivity::class.java).use { scenario ->
                    onView(withText(name)).perform(scrollTo()).check { view, missing ->
                        if (missing != null) throw missing
                        assertTrue("80 karakterlik ad satırlara sığmalı", (view as android.widget.TextView).lineCount > 1)
                    }
                    onView(withContentDescription("Kategoriyi yönet: $name")).perform(scrollTo()).check(matches(isCompletelyDisplayed()))
                    scenario.onActivity { assertEquals(1.3f, it.resources.configuration.fontScale, 0.01f) }
                    assertTrue(device.takeScreenshot(java.io.File(app.getExternalFilesDir(null), "categories-$mode-large-text.png")))
                }
            }
        } finally {
            device.executeShellCommand(if (scale == "null") "settings delete system font_scale" else "settings put system font_scale $scale")
            AppPreferences(app).theme = theme
            app.store.changeCategory(name, null)
        }
    }
    @Test fun separateMenuColorOrderingRenameAndRemovalRequireConfirmation() {
        val prefix = "Kategori ${System.nanoTime()}"
        val first = "$prefix A"; val second = "$prefix B"; val renamed = "$prefix Yeni"
        val before = app.store.all()
        val theme = AppPreferences(app).theme
        val record = app.store.save(Series(title = "$prefix Seri", categories = listOf(first), notes = "Korunan not"))
        app.store.addCategory(second)
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use {
                onView(withText("Kategoriler")).perform(scrollTo(), click())
                onView(withText("Kategori oluştur")).check(matches(isDisplayed()))
                androidx.test.espresso.Espresso.pressBack()
            }
            ActivityScenario.launch(CategoriesActivity::class.java).use { scenario ->
                onView(withContentDescription("Kategoriyi yönet: $second")).perform(scrollTo(), click())
                onView(withText("Yukarı taşı")).perform(click())
                onView(withContentDescription("Kategoriyi yönet: $first")).perform(scrollTo(), click())
                onView(withText("Ad ve renk")).perform(click())
                onView(withHint("Kategori adı")).inRoot(isDialog()).perform(replaceText(renamed), closeSoftKeyboard())
                onView(withContentDescription("Kategori rengi")).inRoot(isDialog()).perform(click())
                onData(equalTo("Mavi")).inRoot(isPlatformPopup()).perform(click())
                onView(withText("Kaydet")).inRoot(isDialog()).perform(click())
                scenario.recreate()
                onView(withContentDescription("Kategoriyi yönet: $renamed")).perform(scrollTo()).check(matches(isDisplayed()))
                val definitions = app.store.categoryDefinitions()
                assertTrue(definitions.indexOfFirst { it.name == second } < definitions.indexOfFirst { it.name == renamed })
                assertEquals("blue", definitions.first { it.name == renamed }.colorId)
                onView(withContentDescription("Kategoriyi yönet: $renamed")).perform(click())
                onView(withText("Kategoriyi kaldır")).perform(click())
                onView(withText("Vazgeç")).perform(click())
                assertTrue(app.store.categories().contains(renamed))
                onView(withContentDescription("Kategoriyi yönet: $renamed")).perform(scrollTo(), click())
                onView(withText("Kategoriyi kaldır")).perform(click())
                onView(withText("Kaldır")).perform(click())
                assertFalse(app.store.categories().contains(renamed))
                assertEquals("Korunan not", app.store.get(record.id)!!.notes)
            }
            for (mode in listOf("light", "dark")) {
                AppPreferences(app).theme = mode
                ActivityScenario.launch(CategoriesActivity::class.java).use { scenario ->
                    onView(withContentDescription("Kategoriyi yönet: $second")).perform(scrollTo()).check(matches(isDisplayed()))
                    scenario.recreate()
                    onView(withContentDescription("Kategoriyi yönet: $second")).perform(scrollTo()).check(matches(isDisplayed()))
                }
            }
        } finally {
            AppPreferences(app).theme = theme
            app.store.get(record.id)?.let(app.store::delete)
            listOf(first, second, renamed).forEach { if (it in app.store.categories()) app.store.changeCategory(it, null) }
        }
        assertEquals(before, app.store.all())
    }
}
