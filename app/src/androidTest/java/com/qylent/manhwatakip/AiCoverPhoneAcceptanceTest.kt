package com.qylent.manhwatakip

import android.content.Context
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
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
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in: public title only, no Save or settings changes. */
class AiCoverPhoneAcceptanceTest {
    private fun report(value: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "$value\n") })

    @Test fun diagnoseResponseShapeWithoutLoggingCredentials() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveCoverDiagnostic") == "true")
        val app = ApplicationProvider.getApplicationContext<Context>() as LibraryApplication
        val connection = AiSecretStore(app).connection()
        require(connection == null || connection.settings.isLocalHttp)
        val fetcher = CoverFetcher()
        var calls = 0
        val complete: ((org.json.JSONArray) -> String)? = connection?.let { current ->
            { messages -> calls++; AiClient().complete(current.settings, current.key, messages) }
        }
        val found = AiCoverFinder(complete, fetcher::fetch, fetcher::search).find("Sword or Blood", "", emptyList())
        CoverStore.decode(found.bytes).recycle()
        report("LIVE_COVER_SOURCE_PREVIEW title_only=Sword or Blood; source=${found.seriesUrl}; model_calls=$calls; no_save")
        assertEquals("https://trmanga.com/webtoon/sword-or-blood", found.seriesUrl)
        assertEquals(connection?.settings, AiSecretStore(app).settings())
    }

    @Test fun currentPhoneModelSearchesPublicExampleWithoutSaving() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveCoverAcceptance") == "true")
        val app = ApplicationProvider.getApplicationContext<Context>() as LibraryApplication
        val settings = AiSecretStore(app).settings()
        require(settings == null || settings.isLocalHttp)
        val title = "Sword or Blood"
        val idle = ScreenIdle(); IdlingRegistry.getInstance().register(idle)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        try {
            ActivityScenario.launch(EditActivity::class.java).use { scenario ->
                onView(withHint("Seri adı")).perform(replaceText(title), closeSoftKeyboard())
                onView(withHint("Okuma bağlantısı (isteğe bağlı)")).perform(replaceText(""), closeSoftKeyboard())
                onView(withText("Türkçe kaynaklarda kapak ara")).perform(scrollTo(), click())
                onView(withText("Ara")).perform(click())
                report("LIVE_COVER_SEARCH_STARTED title_only=$title; reading_url_empty; no_save")
                assertTrue("Canlı kapak araması zamanında tamamlanmalı", device.wait(Until.hasObject(By.text("Türkçe kaynaklarda kapak ara")), 180_000))
                scenario.onActivity { activity ->
                    val state = ViewModelProvider(activity)[EditActivity.SaveState::class.java]
                    assertNull(state.aiCoverPending); assertNull(state.pending)
                    assertEquals(title, state.coverTitle)
                    assertNotNull("Yalnızca seri adıyla gerçek kaynak kapağı bulunmalı", state.coverResult)
                    assertEquals("https://trmanga.com/webtoon/sword-or-blood", state.coverResult!!.seriesUrl)
                    assertNotNull(state.coverBitmap)
                    assertTrue(state.coverBitmap!!.width > 100)
                    report("LIVE_COVER_SOURCE_PREVIEW source=${state.coverResult!!.seriesUrl}; no_save")
                }
                onView(withText(org.hamcrest.Matchers.startsWith("Türkçe kaynaktaki seri kapağı bulundu"))).perform(scrollTo())
                device.takeScreenshot(java.io.File(app.cacheDir, "acceptance-title-only.png"))
                assertEquals(settings, AiSecretStore(app).settings())
                report("LIVE_COVER_NO_SAVE_SETTINGS_UNCHANGED")
            }
        } finally { IdlingRegistry.getInstance().unregister(idle); idle.close() }
    }
}
