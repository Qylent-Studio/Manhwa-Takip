package com.qylent.manhwatakip

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.IdlingRegistry
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import org.hamcrest.Matchers.*
import org.junit.*
import org.junit.Assert.*

/** Emulator-only fixtures; never write personal phone data or call paid models. */
class UiRefreshTest {
    private val app get() = ApplicationProvider.getApplicationContext<Context>() as LibraryApplication
    private val idle = ScreenIdle()
    private lateinit var chats: ChatFixture
    @Before fun setup() {
        Assume.assumeTrue(android.os.Build.HARDWARE == "ranchu" || android.os.Build.FINGERPRINT.contains("generic"))
        TestDeviceSetup.prepare(); chats = ChatFixture(); IdlingRegistry.getInstance().register(idle)
    }
    @After fun cleanup() { IdlingRegistry.getInstance().unregister(idle); idle.close(); if (::chats.isInitialized) chats.close() }

    @Test fun navigationKeepsCurrentScreenAndChatDraft() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withContentDescription("Gezinme: Kütüphane")).perform(click())
            onView(withHint("Seri ara")).check(matches(isDisplayed()))
            onView(withContentDescription("Gezinme: AI sohbeti")).perform(click())
            onView(allOf(withText("AI"), isDescendantOfA(withTagValue(equalTo("libraryNavigation" as Any))))).check(matches(isDisplayed()))
            onView(withHint("Mesaj, seri adı veya bağlantısı")).perform(replaceText("Korunan taslak"), closeSoftKeyboard())
            onView(withContentDescription("Gezinme: AI sohbeti")).perform(click())
            onView(withHint("Mesaj, seri adı veya bağlantısı")).check(matches(withText("Korunan taslak")))
            pressBack()
            onView(withHint("Seri ara")).check(matches(isDisplayed()))
        }
    }

    @Test fun bulkActionsAreVisibleAndExitPreservesSearch() {
        val before = app.store.all()
        val prefix = "UI yenileme ${System.nanoTime()}"
        val rows = (1..2).map { app.store.save(Series(title = "$prefix $it")) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                onView(withContentDescription("Seri ekle")).check(matches(isDisplayed()))
                onView(withHint("Seri ara")).perform(replaceText(prefix), closeSoftKeyboard())
                onView(withText("Seç")).perform(click())
                onView(withText("Düzenle (0)")).check(matches(not(isEnabled())))
                onView(withText("Hepsini seç")).perform(click())
                onView(withText("Düzenle (2)")).check(matches(isDisplayed()))
                scenario.recreate()
                onView(withText("Düzenle (2)")).perform(click())
                onView(withText("Okuma durumunu değiştir")).perform(click())
                onView(withText("Tamamlandı")).perform(click())
                onView(withText("Vazgeç")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
                onView(withText("Seçimden çık")).perform(click())
                onView(withHint("Seri ara")).check(matches(withText(prefix)))
                onView(withText("Hepsini seç")).check(matches(not(isDisplayed())))
                assertEquals(rows, rows.map { app.store.get(it.id) })
            }
        } finally { rows.forEach { app.store.get(it.id)?.let(app.store::delete) } }
        assertEquals(before, app.store.all())
    }

    @Test fun backExitsSelectionWithoutDrawerAndNeverWrites() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("Seç")).perform(click())
            onView(withContentDescription("Menüyü aç")).check(doesNotExist())
            onView(withText("Seçimden çık")).check(matches(isDisplayed()))
            pressBack()
            onView(withText("Seçimden çık")).check(matches(not(isDisplayed())))
            onView(withHint("Seri ara")).check(matches(isDisplayed()))
        }
    }

    @Test fun chatToolsAreCollapsedAndKeepExistingActions() {
        ActivityScenario.launch(AiActivity::class.java).use { scenario ->
            onView(withText("Kapak ara")).check(doesNotExist())
            onView(withContentDescription("Sohbet araçları")).perform(click())
            onView(withText("Seri bilgisi hazırla")).check(matches(isDisplayed()))
            onView(withText("Kapak ara")).check(matches(isDisplayed()))
            onView(withText("Kişisel öneri iste")).perform(click())
            onView(withHint("Mesaj, seri adı veya bağlantısı")).check(matches(withText(containsString("kişisel manhwa önerileri"))))
            scenario.recreate()
            onView(withHint("Mesaj, seri adı veya bağlantısı")).check(matches(withText(containsString("kişisel manhwa önerileri"))))
        }
    }

    @Test fun themesKeepViewPreferenceAndAccessibleControls() {
        val prefs = AppPreferences(app); val theme = prefs.theme; val mode = prefs.viewMode
        try {
            prefs.viewMode = "grid"
            listOf("light", "dark").forEach { selected ->
                prefs.theme = selected
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    onView(withContentDescription("Gezinme: Kütüphane")).check(matches(isDisplayed()))
                    onView(withContentDescription("Liste / ızgara görünümü")).perform(click())
                    assertEquals("list", prefs.viewMode)
                    scenario.recreate()
                    assertEquals("list", prefs.viewMode)
                    onView(withContentDescription("Liste / ızgara görünümü")).perform(click())
                    assertEquals("grid", prefs.viewMode)
                    scenario.onActivity { activity ->
                        fun visit(view: View) {
                            if (view.contentDescription == "Seri ekle") assertTrue(view.height >= (48 * activity.resources.displayMetrics.density).toInt())
                            if (view is ViewGroup) (0 until view.childCount).forEach { visit(view.getChildAt(it)) }
                        }
                        visit(activity.window.decorView)
                    }
                }
            }
        } finally { prefs.theme = theme; prefs.viewMode = mode }
    }

    @Test fun emptyResultsKeepSelectionActionsSafe() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withHint("Seri ara")).perform(replaceText("Hiç bulunmayan ${System.nanoTime()}"), closeSoftKeyboard())
            onView(withText("Seç")).perform(click())
            onView(withText("Hepsini seç")).check(matches(not(isEnabled())))
            onView(withText("Düzenle (0)")).check(matches(not(isEnabled())))
            onView(withText("Seçimi kaldır")).check(matches(not(isEnabled())))
            onView(withText("Seçimden çık")).perform(click())
            onView(withText("Seç")).check(matches(isDisplayed()))
        }
    }

    @Test fun chatComposerUsesCompactSendAndNoDuplicateIntro() {
        ActivityScenario.launch(AiActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val state = androidx.lifecycle.ViewModelProvider(activity)[AiActivity.ChatState::class.java]
                state.history.add("assistant" to "Gerçek mesaj akışı")
            }
            scenario.recreate()
            onView(withText("Gönder")).check { view, error ->
                if (error != null) throw error
                assertTrue("Gönder tüm yazma alanını kaplamamalı", view.width < (view.parent as View).width / 2)
            }
            onView(withText("Seri adını/bağlantısını yaz, okuma kaydını anlat veya kişisel öneri iste. AI bilgileri doğrulanmış kaynak değildir.")).check(matches(not(isDisplayed())))
        }
    }

    @Test fun shortLandscapeSelectionKeepsRowsAndActionsVisible() {
        val device = androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
        val oldSize = device.executeShellCommand("wm size").lineSequence().firstOrNull { it.startsWith("Override size:") }?.substringAfter(":")?.trim()
        val density = app.resources.displayMetrics.densityDpi
        val row = app.store.save(Series(title = "Yatay seçim ${System.nanoTime()}"))
        try {
            device.executeShellCommand("wm size ${360 * density / 160}x${640 * density / 160}")
            device.setOrientationLeft(); device.waitForIdle()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                // UiDevice idle does not imply asynchronous Activity rotation is complete.
                val deadline = System.nanoTime() + 10_000_000_000L
                var ready = false
                while (!ready && System.nanoTime() < deadline) {
                    scenario.onActivity { activity ->
                        ready = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
                            activity.window.decorView.width > activity.window.decorView.height && activity.window.decorView.hasWindowFocus()
                    }
                    if (!ready) Thread.sleep(50)
                }
                assertTrue("Yatay Activity ve ölçülmüş yerleşim hazır olmalı", ready)
                onView(withHint("Seri ara")).perform(replaceText(row.title), closeSoftKeyboard())
                onView(withText("Seç")).perform(click())
                scenario.onActivity { activity ->
                    assertEquals(android.content.res.Configuration.ORIENTATION_LANDSCAPE, activity.resources.configuration.orientation)
                    fun list(v: View): AbsListView? = if (v is AbsListView) v else (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { list(g.getChildAt(it)) } }
                    assertTrue("Seçim sırasında kütüphane sıfır yüksekliğe düşmemeli", list(activity.window.decorView)!!.height >= (48 * activity.resources.displayMetrics.density).toInt())
                }
                // A complete row can exceed the short viewport; reveal its checkbox first.
                onView(isAssignableFrom(AbsListView::class.java)).perform(swipeUp())
                onView(withContentDescription("${row.title}: seç")).perform(click())
                onView(withText("Düzenle (1)")).check(matches(isCompletelyDisplayed()))
                onView(withText("Seçimden çık")).check(matches(isCompletelyDisplayed())).perform(click())
                onView(withHint("Seri ara")).check(matches(withText(row.title)))
            }
        } finally {
            device.setOrientationNatural(); device.unfreezeRotation()
            device.executeShellCommand(if (oldSize == null) "wm size reset" else "wm size $oldSize")
            app.store.get(row.id)?.let(app.store::delete)
        }
    }

    @Test fun exitingSelectionRefreshesVisibleModeSummary() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("Seç")).perform(click())
            onView(withText(containsString("Seçim açık"))).check(matches(isDisplayed()))
            onView(withText("Seçimden çık")).perform(click())
            onView(withText(containsString("Seçim açık"))).check(doesNotExist())
        }
    }

    @Test fun exitingSelectionDoesNotCancelCoverJob() {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        val row = app.store.save(Series(title = "Geçici UI kapak ${System.nanoTime()}"))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                onView(withText("Seç")).perform(click())
                val worker = java.util.concurrent.Executor { job -> app.networkExecutor.execute { try { job.run() } finally { finished.countDown() } } }
                assertTrue(app.bulkCovers.start(listOf(row), worker, {
                    started.countDown(); check(release.await(30, java.util.concurrent.TimeUnit.SECONDS))
                    throw IllegalArgumentException("Kontrollü kapak sonucu")
                }, { _, _ -> error("Bu fixture kayıt yazmamalı") }))
                assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS))
                scenario.recreate()
                onView(withText("Düzenle (0)")).check(matches(not(isEnabled())))
                onView(withText("Seçimden çık")).perform(click())
                assertTrue(app.bulkCovers.progress!!.running)
                onView(withText("Durdur")).check(matches(isDisplayed())).perform(click())
                assertEquals(row, app.store.get(row.id))
            }
        } finally {
            app.bulkCovers.cancel(); release.countDown()
            assertTrue(finished.await(10, java.util.concurrent.TimeUnit.SECONDS))
            app.bulkCovers.clear(); app.store.get(row.id)?.let(app.store::delete)
        }
    }

    @Test fun captureBothThemesAndLargeTypeWithoutChangingRecords() {
        val prefs = AppPreferences(app); val theme = prefs.theme; val mode = prefs.viewMode; val fav = prefs.favoritesOnly
        val device = androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
        val font = device.executeShellCommand("settings get system font_scale").trim()
        val rows = listOf("Gece Avcısı", "Kılıcın Mirası", "Son Büyücü", "Yeşim Sarayı", "Kül ve Taç", "Kayıp Yolcu").mapIndexed { i, title ->
            app.store.save(Series(title = "$title · Arayüz örneği", lastChapter = "${12 + i * 14}", coverUrl = "https://fixture.invalid/cover.jpg"))
        }
        try {
            rows.forEachIndexed { i, row ->
                val bitmap = android.graphics.Bitmap.createBitmap(240, 320, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap); val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                canvas.drawColor(android.graphics.Color.rgb(22, 34, 52)); paint.color = listOf(0xFF4CC7DF.toInt(), 0xFFFF805A.toInt(), 0xFF96BDB3.toInt())[i % 3]
                canvas.drawCircle(165f, 85f, 56f, paint)
                paint.color = android.graphics.Color.rgb(10, 18, 28)
                canvas.drawPath(android.graphics.Path().apply { moveTo(25f, 260f); lineTo(130f, 70f); lineTo(210f, 260f); close() }, paint)
                paint.color = android.graphics.Color.rgb(238, 243, 248); paint.textSize = 23f; paint.isFakeBoldText = true
                canvas.drawText(row.title.substringBefore(" ·").take(18), 14f, 293f, paint)
                val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
                bitmap.recycle(); app.covers.save(row.id, bytes)
            }
            prefs.favoritesOnly = false; prefs.viewMode = "grid"
            listOf("light", "dark").forEach { selected ->
                prefs.theme = selected
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    onView(withHint("Seri ara")).perform(replaceText("Arayüz örneği"), closeSoftKeyboard())
                    device.waitForIdle()
                    assertTrue(device.takeScreenshot(java.io.File(app.getExternalFilesDir(null), "ui-refresh-$selected-library.png")))
                    onView(withText("Seç")).perform(click()); onView(withText("Hepsini seç")).perform(click())
                    onView(withText("Düzenle (6)")).check(matches(isDisplayed()))
                    device.waitForIdle()
                    assertTrue(device.takeScreenshot(java.io.File(app.getExternalFilesDir(null), "ui-refresh-$selected-selection.png")))
                    onView(withText("Seçimden çık")).perform(click())
                    device.executeShellCommand("settings put system font_scale 1.3")
                    scenario.recreate()
                    TestWindowReadiness.awaitConfiguration(scenario, fontScale = 1.3f)
                    onView(withText("Seç")).check(matches(isCompletelyDisplayed()))
                    scenario.onActivity { activity ->
                        fun grid(v: View): android.widget.GridView? = if (v is android.widget.GridView) v else (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { grid(g.getChildAt(it)) } }
                        assertEquals(2, grid(activity.window.decorView)!!.numColumns)
                    }
                    device.executeShellCommand(if (font == "null") "settings delete system font_scale" else "settings put system font_scale $font")
                }
                ActivityScenario.launch(AiActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        val state = androidx.lifecycle.ViewModelProvider(activity)[AiActivity.ChatState::class.java]
                        state.history.clear(); state.history.add("user" to "Güçlü ana karakterli bir seri önerir misin?")
                        state.history.add("assistant" to "Kütüphanendeki serilere birlikte bakabiliriz. Aksiyon mu, fantastik bir hikâye mi tercih edersin?\n\nKayıt değişikliği hazırlarsam, uygulamadan önce senin onayını isteyeceğim.")
                    }
                    scenario.recreate()
                    onView(withHint("Mesaj, seri adı veya bağlantısı")).perform(replaceText("Fantastik olsun."), closeSoftKeyboard())
                    device.waitForIdle()
                    assertTrue(device.takeScreenshot(java.io.File(app.getExternalFilesDir(null), "ui-refresh-$selected-chat.png")))
                }
            }
            assertEquals(rows, rows.map { app.store.get(it.id) })
        } finally {
            device.executeShellCommand(if (font == "null") "settings delete system font_scale" else "settings put system font_scale $font")
            prefs.theme = theme; prefs.viewMode = mode; prefs.favoritesOnly = fav
            rows.forEach { app.covers.remove(it.id); app.store.get(it.id)?.let(app.store::delete) }
        }
    }
}
