package com.qylent.manhwatakip

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CoverLiveAcceptanceTest {
    @Test fun liveSearchAndLibraryPreviewUseRealCoversInBothThemes() {
        TestDeviceSetup.prepare()
        val app = ApplicationProvider.getApplicationContext<Context>() as LibraryApplication
        val prefs = AppPreferences(app); val theme = prefs.theme; val mode = prefs.viewMode; val favorites = prefs.favoritesOnly
        val sort = prefs.sort; val descending = prefs.descending
        val records = mutableListOf<Series>()
        val idle = ScreenIdle(); androidx.test.espresso.IdlingRegistry.getInstance().register(idle)
        val prefix = "ÖNİZLEME ${System.nanoTime()}"
        try {
            val results = CoverFetcher().search("solo leveling")
            assertTrue("Gerçek arama seri adaylarını döndürmeli", results.any { it.title.equals("Solo Leveling", true) })
            listOf("Solo Leveling" to "solo-leveling", "Solo Login" to "solo-login", "Solo Farming in the Tower" to "solo-farming-in-the-tower").forEachIndexed { index, (title, slug) ->
                val cover = CoverFetcher().fetch("https://okutoon.com/$slug")
                val record = app.store.save(Series(title = "$title · Örnek", alternativeTitles = listOf(prefix), coverUrl = cover.imageUrl,
                    lastChapter = listOf("48", "12", "142")[index], status = ReadingStatus.entries[index + 1]))
                records.add(record); app.covers.save(record.id, cover.bytes)
            }
            prefs.viewMode = "grid"; prefs.favoritesOnly = false; prefs.sort = "title"; prefs.descending = false
            listOf("dark", "light").forEach { chosenTheme ->
                prefs.theme = chosenTheme
                androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use {
                    androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withHint("Seri ara"))
                        .perform(androidx.test.espresso.action.ViewActions.replaceText(prefix), androidx.test.espresso.action.ViewActions.closeSoftKeyboard())
                    androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(records.minBy { it.title }.title))
                        .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed()))
                    androidx.test.espresso.Espresso.onIdle()
                    val device = androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
                    assertTrue(device.takeScreenshot(java.io.File(app.externalCacheDir, "library-$chosenTheme.png")))
                }
            }
        } finally {
            records.forEach { app.store.get(it.id)?.let(app.store::delete); app.covers.remove(it.id) }
            prefs.theme = theme; prefs.viewMode = mode; prefs.favoritesOnly = favorites
            prefs.sort = sort; prefs.descending = descending
            androidx.test.espresso.IdlingRegistry.getInstance().unregister(idle); idle.close()
        }
    }
    @Test fun fourSourceSeriesAndChapterLinksUseSameRealCover() {
        val sources = listOf(
            "https://golgebahcesi.com/manga/golgelerdeki-hukumdar-olmak-istiyorum" to "/bolum/chapter-1",
            "https://eldermanga.com/manga/sonsuz-yukselis-engelleri-parcalayan-azim" to "/0-bolum-oku",
            "https://uzaymanga.com/manga/efsanevi-buyu-imparatoru" to "/2-bolum-oku",
            "https://okutoon.com/toilet-bound-hanako-kun" to "/bolum-1")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val covers = CoverStore(context)
        sources.forEach { (url, chapter) ->
            val result = CoverFetcher().fetch(url)
            assertEquals(result.imageUrl, CoverFetcher().fetch(url + chapter).imageUrl)
            val id = UUID.randomUUID().toString()
            try {
                if (result.imageUrl.contains(".avif")) {
                    val buffer = java.nio.ByteBuffer.allocateDirect(result.bytes.size).apply { put(result.bytes); position(0) }
                    val info = org.aomedia.avif.android.AvifDecoder.Info()
                    assertTrue(org.aomedia.avif.android.AvifDecoder.getInfo(buffer, result.bytes.size, info))
                    val native = android.graphics.Bitmap.createBitmap(info.width, info.height, android.graphics.Bitmap.Config.ARGB_8888)
                    try { assertTrue(org.aomedia.avif.android.AvifDecoder.decode(buffer, result.bytes.size, native, 1)) } finally { native.recycle() }
                }
                covers.save(id, result.bytes)
                assertTrue(covers.bytes(id)!!.isNotEmpty())
                val reopened = CoverStore(context).load(id)!!
                assertTrue(reopened.width > 0 && reopened.height > 0); reopened.recycle()
            } finally { java.io.File(context.filesDir, "covers/$id.jpg").delete() }
        }
    }
}
