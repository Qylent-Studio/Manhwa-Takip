package com.qylent.manhwatakip

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.*
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.IdlingRegistry
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import org.junit.*
import org.junit.Assert.*

/** Navigation cases are read-only; preference changes are emulator-only and restored. */
class SimpleSettingsUiTest {
    private val idle = ScreenIdle()
    @Before fun setup() { TestDeviceSetup.prepare(); IdlingRegistry.getInstance().register(idle) }
    @After fun cleanup() { IdlingRegistry.getInstance().unregister(idle); idle.close() }

    @Test fun dailySettingsAreVisibleAndTechnicalOptionsStartCollapsed() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withText("Uygulama teması")).perform(scrollTo()).check(matches(isDisplayed()))
            listOf("Görünüm", "Kütüphane", "Yedekleme").forEach { text ->
                onView(withContentDescription("Ayar: $text")).perform(scrollTo()).check(matches(isDisplayed()))
            }
            onView(withText("AI ayarları")).check(doesNotExist())
            onView(withText("ChatGPT / MCP")).check(doesNotExist())
            onView(withText("Gelişmiş · AI ve bağlantılar")).perform(scrollTo(), click())
            onView(withText("AI ayarları")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Bağlantılar")).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }

    @Test fun expandedSectionSurvivesRecreationAndCanBeCollapsed() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            onView(withText("Gelişmiş · AI ve bağlantılar")).perform(scrollTo(), click())
            scenario.recreate()
            onView(withText("AI ayarları")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Gelişmiş · AI ve bağlantılar")).perform(scrollTo(), click())
            onView(withText("AI ayarları")).check(doesNotExist())
        }
    }

    @Test fun libraryAndBackupGroupsRetainDestinationsAndBackReturnsToSettings() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            onView(withContentDescription("Ayar: Kütüphane")).perform(scrollTo(), click())
            onView(withText("Kategoriler")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("İstatistikler")).perform(scrollTo(), click())
            onView(withText("İstatistikler")).check(matches(isDisplayed()))
            pressBack(); pressBack()
            onView(withText("Yedekleme")).perform(scrollTo(), click())
            listOf("Yedekler", "Şifreli tam yedek", "Parolasız JSON / ZIP yedek").forEach { text ->
                onView(withText(text)).perform(scrollTo()).check(matches(isDisplayed()))
            }
            scenario.recreate()
            onView(withText("Yedekler")).perform(scrollTo(), click())
            onView(withText("Yedekten geri yükle")).check(matches(isDisplayed()))
            pressBack(); pressBack()
            onView(withText("Uygulama teması")).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }

    @Test fun connectionHelpAndMcpRemainBehindAdvancedWithoutSharing() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withText("Gelişmiş · AI ve bağlantılar")).perform(scrollTo(), click())
            onView(withText("Bağlantılar")).perform(scrollTo(), click())
            onView(withText("Bilgisayar / USB")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("ChatGPT / MCP")).perform(scrollTo(), click())
            onView(withText("Bağlantıyı aç / eşitle")).perform(scrollTo()).check(matches(isDisplayed()))
            pressBack(); pressBack()
            onView(withText("AI ayarları")).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }

    @Test fun inlineThemeSelectionPersistsAndKeepsAdvancedExpansion() {
        Assume.assumeTrue(android.os.Build.HARDWARE == "ranchu" || android.os.Build.FINGERPRINT.contains("generic"))
        val prefs = AppPreferences(ApplicationProvider.getApplicationContext<Context>())
        val original = prefs.theme
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                onView(withText("Gelişmiş · AI ve bağlantılar")).perform(scrollTo(), click())
                onView(withText("Açık")).perform(scrollTo(), click()); onIdle()
                assertEquals("light", prefs.theme); assertFalse(Ui.dark)
                onView(withText("AI ayarları")).perform(scrollTo()).check(matches(isDisplayed()))
                onView(withText("Karanlık")).perform(scrollTo(), click()); onIdle()
                assertEquals("dark", prefs.theme); assertTrue(Ui.dark)
                scenario.recreate()
                onView(withText("Karanlık")).check(matches(isSelected()))
                onView(withText("Sistem")).perform(scrollTo(), click()); onIdle()
                assertEquals("system", prefs.theme)
            }
        } finally { prefs.theme = original }
    }
}
