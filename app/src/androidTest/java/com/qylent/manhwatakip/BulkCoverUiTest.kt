package com.qylent.manhwatakip

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.IdlingRegistry
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.hamcrest.Matchers.startsWith
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BulkCoverUiTest {
    private val app get() = ApplicationProvider.getApplicationContext<Context>() as LibraryApplication

    @Test fun selectedSeriesExposeBulkCoverConsentWithoutWriting() {
        assumeTrue(Build.HARDWARE == "ranchu" || Build.FINGERPRINT.contains("generic"))
        TestDeviceSetup.prepare()
        val idle = ScreenIdle(); IdlingRegistry.getInstance().register(idle)
        val record = app.store.save(Series(title = "Toplu seçim ${System.nanoTime()}"))
        val prefs = AppPreferences(app); val favorites = prefs.favoritesOnly; prefs.favoritesOnly = false
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                onView(withHint("Seri ara")).perform(replaceText(record.title), closeSoftKeyboard())
                onView(withText("Seç")).perform(click())
                onView(withContentDescription("${record.title}: seç")).perform(click())
                onView(withText("Düzenle (1)")).perform(click())
                onView(withText("Türkçe kaynaklarda kapak bul")).perform(click())
                onView(withText("Başlat ve otomatik kaydet")).check(matches(isDisplayed()))
                onView(withText("Vazgeç")).perform(click())
                assertNull(app.bulkCovers.progress); assertEquals(record, app.store.get(record.id))
            }
        } finally {
            IdlingRegistry.getInstance().unregister(idle); idle.close(); prefs.favoritesOnly = favorites
            app.store.get(record.id)?.let(app.store::delete)
        }
    }

    /** Only disposable rows in a separate DB; physical run is explicitly opted in. */
    @Test fun sequentialAutoSaveSurvivesRotationAndCanStopWithoutTouchingLibrary() {
        assumeTrue(Build.HARDWARE == "ranchu" || Build.FINGERPRINT.contains("generic") ||
            InstrumentationRegistry.getArguments().getString("bulkCoverAcceptance") == "true")
        TestDeviceSetup.prepare()
        val name = "bulk-ui-${System.nanoTime()}.db"
        val idle = ScreenIdle(); IdlingRegistry.getInstance().register(idle)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.wakeUp()
        val screenAwake = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
            if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.CREATED)
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
        lifecycle.addLifecycleCallback(screenAwake)
        val firstSaved = CountDownLatch(1); val thirdSearch = CountDownLatch(1); val release = CountDownLatch(1)
        val live = InstrumentationRegistry.getArguments().getString("bulkCoverLive") == "true"
        val bitmap = Bitmap.createBitmap(8, 12, Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it); bitmap.recycle() }.toByteArray()
        val cover = CoverResult("https://trmanga.com/webtoon/test", "https://cdn.trmanga.com/test.jpg", bytes)
        val baseline = app.executor.submit<List<Series>> { app.store.all() }.get()
        val settings = AiSecretStore(app).settings()
        var firstSavedAt = 0L; var secondStartedAt = 0L
        val entries = (1..3).map { Series(title = "Geçici toplu kapak $it", notes = "Korunan not", alternativeTitles = listOf("Sword or Blood")) }
        val savedUrls = mutableMapOf<String, String>()
        val isolated = LibraryStore(app, name)
        try {
            val selected = entries.map(isolated::save)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                assertTrue(app.bulkCovers.start(selected, app.networkExecutor, { entry ->
                    if (entry.id == selected[1].id) secondStartedAt = System.nanoTime()
                    if (entry.id == selected[2].id) { thirdSearch.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
                    if (live && entry.id != selected[2].id) {
                        val fetcher = CoverFetcher()
                        AiCoverFinder(null, fetcher::fetch, fetcher::search).find("Sword or Blood", "", emptyList())
                    } else cover
                }, { entry, result ->
                    app.executor.submit<Series> { isolated.applyCover(entry, result, app.covers) }.get()
                    savedUrls[entry.id] = result.imageUrl
                    if (entry.id == selected[0].id) { firstSavedAt = System.nanoTime(); firstSaved.countDown() }
                }))
                scenario.recreate()
                assertTrue(firstSaved.await(45, TimeUnit.SECONDS))
                assertNotNull(app.covers.bytes(selected[0].id))
                assertTrue(thirdSearch.await(60, TimeUnit.SECONDS))
                assertTrue(TimeUnit.NANOSECONDS.toMillis(secondStartedAt - firstSavedAt) >= 1950)
                scenario.recreate()
                assertTrue(device.wait(Until.hasObject(By.textContains("2/3")), 5_000))
                onView(withText("Durdur")).perform(click())
                release.countDown()
                assertTrue(device.wait(Until.hasObject(By.textStartsWith("İşlem durduruldu")), 10_000))
                onView(withText(startsWith("İşlem durduruldu"))).check(matches(isDisplayed()))
                assertEquals(2, app.bulkCovers.progress!!.saved); assertTrue(app.bulkCovers.progress!!.cancelled)
                assertEquals(savedUrls[selected[0].id], isolated.get(selected[0].id)!!.coverUrl)
                assertEquals(savedUrls[selected[1].id], isolated.get(selected[1].id)!!.coverUrl)
                assertEquals(selected[2], isolated.get(selected[2].id)); assertNull(app.covers.bytes(selected[2].id))
                onView(withText("Kapat")).perform(click())
                assertEquals(baseline, app.executor.submit<List<Series>> { app.store.all() }.get())
                assertEquals(settings, AiSecretStore(app).settings())
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                    putString("stream", "Bulk cover: 2 saved, gap=${TimeUnit.NANOSECONDS.toMillis(secondStartedAt - firstSavedAt)}ms, live=$live, rotation/cancel passed, personal library and AI settings unchanged.\n")
                })
            }
        } finally {
            app.bulkCovers.cancel(); release.countDown()
            val deadline = android.os.SystemClock.uptimeMillis() + 20_000
            while (app.bulkCovers.progress?.running == true && android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(25)
            isolated.close()
            app.bulkCovers.clear(); entries.forEach { app.covers.remove(it.id) }; app.deleteDatabase(name)
            lifecycle.removeLifecycleCallback(screenAwake)
            IdlingRegistry.getInstance().unregister(idle); idle.close()
        }
    }
}
