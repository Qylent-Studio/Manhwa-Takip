package com.qylent.manhwatakip

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.GridView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.onIdle
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.IdlingRegistry
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import org.junit.*
import org.junit.Assert.*

/** Emulator only: settings snapshot restored; never modifies the physical phone. */
class AppearanceSettingsTest {
    private val app get() = ApplicationProvider.getApplicationContext<Context>() as LibraryApplication
    private val prefs get() = app.getSharedPreferences("application", Context.MODE_PRIVATE)
    private lateinit var snapshot: Map<String, *>
    private val idle = ScreenIdle()
    @Before fun setup() {
        Assume.assumeTrue(android.os.Build.HARDWARE == "ranchu" || android.os.Build.FINGERPRINT.contains("generic"))
        TestDeviceSetup.prepare(); snapshot = prefs.all
        prefs.edit().putString("viewMode", "grid").putInt("gridColumns", 3).putString("textSize", "normal").commit()
        IdlingRegistry.getInstance().register(idle)
    }
    @After fun cleanup() {
        IdlingRegistry.getInstance().unregister(idle); idle.close()
        if (::snapshot.isInitialized) {
            val edit = prefs.edit().clear()
            snapshot.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value)
                is Int -> edit.putInt(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Float -> edit.putFloat(key, value)
                is Long -> edit.putLong(key, value)
            } }
            edit.commit()
        }
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun selectingTwoColumnsPersistsAndUpdatesExistingLibraryOnReturn() {
        ActivityScenario.launch(MainActivity::class.java).use { library ->
            onIdle()
            library.onActivity { assertEquals(3, descendants(it.window.decorView).filterIsInstance<GridView>().single().numColumns) }
            onView(withContentDescription("Gezinme: Ayarlar")).perform(click())
            onView(withText("Uygulama ayarları")).perform(scrollTo(), click())
            onView(withText("Satır başına kart")).perform(scrollTo(), click())
            onView(withText("2 kart")).perform(click())
            onIdle()
            assertEquals(2, prefs.getInt("gridColumns", 0))
            pressBack(); pressBack(); onIdle()
            library.onActivity { assertEquals(2, descendants(it.window.decorView).filterIsInstance<GridView>().single().numColumns) }
            library.recreate(); onIdle()
            library.onActivity { assertEquals(2, descendants(it.window.decorView).filterIsInstance<GridView>().single().numColumns) }
        }
    }

    @Test fun textChoiceEnlargesActualViewsAndSurvivesRecreation() {
        ActivityScenario.launch(AppSettingsActivity::class.java).use { settings ->
            var original = 0f
            settings.onActivity { original = descendants(it.window.decorView).filterIsInstance<TextView>().first { v -> v.text.toString() == "Tema seç" }.textSize }
            onView(withText("Yazı boyutu")).perform(scrollTo(), click())
            onView(withText("Çok büyük")).perform(click()); onIdle()
            assertEquals("extra_large", prefs.getString("textSize", ""))
            settings.recreate(); onIdle()
            settings.onActivity {
                val actual = descendants(it.window.decorView).filterIsInstance<TextView>().first { v -> v.text.toString() == "Tema seç" }.textSize
                assertTrue("Yazı gerçek görünümde büyümeli", actual > original * 1.2f)
            }
            onView(withText("Yazı boyutu")).perform(scrollTo(), click())
            onView(withText("Normal")).perform(click()); onIdle()
            settings.onActivity { assertEquals(original, descendants(it.window.decorView).filterIsInstance<TextView>().first { v -> v.text.toString() == "Tema seç" }.textSize, 0.5f) }
        }
    }

    @Test fun backupRetainsNewSettingsWithoutOverwritingExistingChoices() {
        prefs.edit().putInt("gridColumns", 2).putString("textSize", "large").commit()
        val backup = AppPreferences(app).backup()
        assertEquals(2, backup.getInt("gridColumns"))
        assertEquals("large", backup.getString("textSize"))
        val name = "appearance-${System.nanoTime()}"
        val target = AppPreferences(app, name)
        try {
            target.importMissing(backup)
            val imported = target.backup()
            assertEquals(2, imported.getInt("gridColumns"))
            assertEquals("large", imported.getString("textSize"))
            val oldBackup = org.json.JSONObject(backup.toString()).apply { remove("gridColumns"); remove("textSize") }
            AppPreferences.validateBackup(oldBackup)
            val legacyName = "$name-legacy"
            try {
                AppPreferences(app, legacyName).importMissing(oldBackup)
                assertEquals("normal", AppPreferences(app, legacyName).backup().getString("textSize"))
                assertFalse(AppPreferences(app, legacyName).backup().has("gridColumns"))
            } finally { app.getSharedPreferences(legacyName, Context.MODE_PRIVATE).edit().clear().commit() }
            target.importMissing(backup.put("gridColumns", 3).put("textSize", "extra_large"))
            assertEquals(2, target.backup().getInt("gridColumns"))
            assertEquals("large", target.backup().getString("textSize"))
        } finally { app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test fun textChangeAppliesOnReturnAndMultipliesSystemScaleWithoutCompounding() {
        val systemScale = app.resources.configuration.fontScale
        ActivityScenario.launch(MainActivity::class.java).use { library ->
            var originalInputSize = 0f
            library.onActivity { originalInputSize = descendants(it.window.decorView).filterIsInstance<android.widget.EditText>().first { v -> v.hint == "Seri ara" }.textSize }
            onView(withContentDescription("Gezinme: Ayarlar")).perform(click())
            onView(withText("Uygulama ayarları")).perform(scrollTo(), click())
            onView(withText("Yazı boyutu")).perform(scrollTo(), click())
            onView(withText("Çok büyük")).perform(click()); onIdle()
            pressBack(); pressBack(); onIdle()
            library.onActivity { activity ->
                assertEquals(systemScale * 1.3f, activity.resources.configuration.fontScale, 0.001f)
                val input = descendants(activity.window.decorView).filterIsInstance<android.widget.EditText>().first { it.hint == "Seri ara" }
                // Android 14+ applies nonlinear SP scaling; compare real views, not deprecated scaledDensity.
                assertTrue("Yazı sistem ölçeğine ek olarak büyümeli: actual=${input.textSize} original=$originalInputSize", input.textSize > originalInputSize)
            }
            library.recreate(); onIdle()
            library.onActivity { assertEquals(systemScale * 1.3f, it.resources.configuration.fontScale, 0.001f) }
        }
        ActivityScenario.launch(AiActivity::class.java).use { chat ->
            chat.onActivity { assertEquals(systemScale * 1.3f, it.resources.configuration.fontScale, 0.001f) }
        }
    }
}
