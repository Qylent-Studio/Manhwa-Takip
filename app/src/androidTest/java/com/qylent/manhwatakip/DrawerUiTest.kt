package com.qylent.manhwatakip

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.IdlingRegistry
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import org.junit.*

/** Regression for removing the drawer without losing any of its destinations. */
class DrawerUiTest {
    private val idle = ScreenIdle()
    @Before fun setup() { TestDeviceSetup.prepare(); IdlingRegistry.getInstance().register(idle) }
    @After fun cleanup() { IdlingRegistry.getInstance().unregister(idle); idle.close() }

    @Test fun noHamburgerAndRecreationKeepsSearchAndFilter() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withContentDescription("Menüyü aç")).check(doesNotExist())
            onView(withContentDescription("Gezinme menüsü")).check(doesNotExist())
            onView(withHint("Seri ara")).perform(replaceText("Korunan arama"), closeSoftKeyboard())
            openLibraryFilters()
            onView(withText("Ara verilen")).perform(scrollTo(), click())
            onView(withText("Tamam")).perform(click())
            onView(withContentDescription("Gezinme: Kütüphane")).perform(click())
            scenario.recreate()
            onView(withHint("Seri ara")).check(matches(withText("Korunan arama")))
            openLibraryFilters()
            onView(withText("Ara verilen")).check(matches(isChecked()))
            onView(withText("Tamam")).perform(click())
        }
    }

    @Test fun settingsRetainsStatisticsAndEveryBackupPath() {
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
            onView(withContentDescription("Gezinme: Ayarlar")).perform(click())
            onView(withText("İstatistikler")).perform(scrollTo(), click())
            onView(withText("İstatistikler")).check(matches(isDisplayed()))
            pressBack()
            onView(withText("Yedekler")).perform(scrollTo(), click())
            TestWindowReadiness.awaitFocusedDialog("Yedekler")
            onView(withText("Dosyaya yedekle")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
            TestWindowReadiness.awaitFocusedDialog("Yedek dosyası")
            onView(withText("Yedek dosyası")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check(matches(isDisplayed()))
            onView(withText("İptal")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
            onView(withText("Yedekler")).perform(scrollTo(), click())
            TestWindowReadiness.awaitFocusedDialog("Yedekler")
            onView(withText("Yedekten geri yükle")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check(matches(isDisplayed()))
            onView(withText("Şifreli tam yedek (anahtarlar dahil)")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
            onView(withText("Şifreli tam yedek")).check(matches(isDisplayed()))
            pressBack()
            onView(withText("Parolasız JSON / ZIP yedek")).perform(scrollTo(), click())
            onView(withText("JSON dosyasına kaydet")).perform(scrollTo()).check(matches(isDisplayed()))
            } catch (failure: Throwable) {
                try { TestWindowReadiness.capture("settings failure: ${failure.javaClass.simpleName}") }
                catch (diagnosticFailure: Throwable) { failure.addSuppressed(diagnosticFailure) }
                throw failure
            }
        }
    }

    @Test fun libraryLoadingLocksAddAndSelection() {
        val app = ApplicationProvider.getApplicationContext<Context>() as LibraryApplication
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        app.executor.execute { started.countDown(); release.await(30, java.util.concurrent.TimeUnit.SECONDS) }
        org.junit.Assert.assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    scenario.onActivity { activity ->
                        val controls = arrayListOf<android.view.View>()
                        activity.window.decorView.findViewsWithText(controls, "Seri ekle", android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                        org.junit.Assert.assertFalse(controls.single().isEnabled)
                        controls.clear()
                        activity.window.decorView.findViewsWithText(controls, "Seç", android.view.View.FIND_VIEWS_WITH_TEXT)
                        org.junit.Assert.assertFalse(controls.single().isEnabled)
                    }
                } finally { release.countDown() }
                onView(withContentDescription("Seri ekle")).check(matches(isEnabled()))
            }
        } finally { release.countDown() }
    }

    @Test fun largeTextNavigationAndSettingsRemainReachableInBothOrientations() {
        Assume.assumeTrue(android.os.Build.HARDWARE == "ranchu" || android.os.Build.FINGERPRINT.contains("generic"))
        val app = ApplicationProvider.getApplicationContext<Context>()
        val device = androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
        val scale = device.executeShellCommand("settings get system font_scale").trim()
        val prefs = AppPreferences(app); val theme = prefs.theme
        try {
            device.executeShellCommand("settings put system font_scale 1.3")
            for (mode in listOf("dark", "light")) {
                prefs.theme = mode
                for (landscape in listOf(false, true)) {
                    if (landscape) device.setOrientationLeft() else device.setOrientationNatural()
                    device.waitForIdle()
                    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                        TestWindowReadiness.awaitConfiguration(scenario, 1.3f,
                            if (landscape) android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT)
                        onView(withContentDescription("Seri ekle")).check(matches(isCompletelyDisplayed()))
                        onView(withContentDescription("Gezinme: AI sohbeti")).check(matches(isCompletelyDisplayed()))
                        onView(withContentDescription("Gezinme: Ayarlar")).check(matches(isCompletelyDisplayed())).perform(click())
                        onView(withText("Yedekler")).perform(scrollTo()).check(matches(isCompletelyDisplayed()))
                        onView(withText("İstatistikler")).perform(scrollTo()).check(matches(isCompletelyDisplayed()))
                        pressBack()
                    }
                }
            }
        } finally {
            device.setOrientationNatural(); device.unfreezeRotation()
            device.executeShellCommand(if (scale == "null") "settings delete system font_scale" else "settings put system font_scale $scale")
            prefs.theme = theme
        }
    }
}
