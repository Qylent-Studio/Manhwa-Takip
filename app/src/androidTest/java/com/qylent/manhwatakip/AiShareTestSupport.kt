package com.qylent.manhwatakip

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.junit.Assert.*
import org.json.JSONObject

/** Bounded, test-only synchronization and synthetic projection assertions. */
object AiShareTestSupport {
    val actions = listOf("Çıktıyı kopyala", "Çıktıyı paylaş", "Çıktıyı dosyaya kaydet", "ZIP dosyasına kaydet", "JSON paylaş", "ZIP paylaş")
    fun output(scenario: ActivityScenario<ChatGptActivity>): String {
        onView(withText(actions.first())).check(matches(isEnabled())) // ScreenIdle waits for generation.
        var text = ""
        scenario.onActivity { text = ViewModelProvider(it)[ChatGptActivity.State::class.java].output!! }
        return text // Full snapshot, never the 12,000-character display preview.
    }
    fun unavailable() {
        actions.forEach { onView(withText(it)).check(matches(not(isEnabled()))) }
        onView(withHint("ChatGPT JSON çıktısı")).check(matches(withText("")))
    }
    fun approveImport(text: String) {
        onView(withHint("ChatGPT JSON'unu yapıştır")).perform(scrollTo(), replaceText(text), closeSoftKeyboard())
        onView(withText("Yapıştırılan JSON'u önizle")).perform(scrollTo(), click())
        onView(withText("Onayla ve içe aktar")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
        onView(withText("Çıktıyı kopyala")).check(matches(not(isEnabled())))
    }
    fun filter(label: String) {
        onView(isAssignableFrom(android.widget.Spinner::class.java)).perform(scrollTo(), click())
        onData(equalTo(label)).perform(click())
    }
    fun select(app: LibraryApplication, ids: Set<String>) {
        val rows = app.store.all()
        check(rows.size <= 30) { "Use a disposable emulator with at most 30 fixture records." }
        onView(withText("Seri seç")).perform(scrollTo(), click())
        // Only used from the initial all-selected state.
        rows.filter { it.id !in ids }.forEach { onData(equalTo(it.title)).perform(click()) }
        onView(withText("Seçimi uygula")).perform(click())
    }
    fun awaitResumed(type: Class<out Activity>) {
        val deadline = System.nanoTime() + 10_000_000_000L
        do {
            var resumed = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { type.isInstance(it) }
            }
            if (resumed) return
            Thread.sleep(50)
        } while (System.nanoTime() < deadline)
        fail("Expected ${type.simpleName} to be RESUMED after system UI return")
    }
    fun sharedBytes(label: String, readinessLabel: String = "Çıktıyı kopyala"): ByteArray {
        val before = androidx.test.espresso.intent.Intents.getIntents().count { it.action == Intent.ACTION_CHOOSER }
        onView(withText(label)).perform(scrollTo(), click())
        onView(withText(readinessLabel)).check(matches(isEnabled()))
        val intents = androidx.test.espresso.intent.Intents.getIntents().filter { it.action == Intent.ACTION_CHOOSER }
        assertEquals("Exactly one new chooser for $label", before + 1, intents.size)
        @Suppress("DEPRECATION") val send = intents.last().getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        if (label == "Çıktıyı paylaş") return send.getStringExtra(Intent.EXTRA_TEXT)!!.toByteArray()
        @Suppress("DEPRECATION") val uri = send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<LibraryApplication>()
        return try { app.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
        finally { java.io.File(app.cacheDir, "shared-exports/${uri.lastPathSegment}").delete() }
    }
    fun assertArchive(bytes: ByteArray, expected: String, excluded: List<String>) {
        val members = mutableSetOf<String>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                assertTrue(members.add(entry.name))
                val text = zip.readBytes().toString(Charsets.UTF_8)
                excluded.forEach { assertFalse("$it leaked in ${entry.name}", text.contains(it)) }
                if (entry.name == "context.json") assertEquals(expected, text)
            }
        }
        assertEquals(setOf("context.json", "profile.md", "instructions.txt"), members)
    }

    class SelectionFixture(val app: LibraryApplication) : java.io.Closeable {
        val suffix = System.nanoTime().toString()
        private fun row(title: String, status: ReadingStatus, reason: String) = app.store.save(Series(title = "$title $suffix", status = status,
            lastChapter = "17", notes = "PRIVATE_NOTE_$suffix", likedReason = reason, dislikedReason = "D".repeat(2000), dropReason = "R".repeat(2000)))
        val selected = listOf(row("Selected A", ReadingStatus.READING, "A".repeat(2000)), row("Selected B", ReadingStatus.READING, "B".repeat(2000)))
        val filtered = row("Status excluded", ReadingStatus.PLANNED, "FILTERED_REASON_$suffix")
        val unselected = row("Manual excluded", ReadingStatus.READING, "UNSELECTED_REASON_$suffix")
        val ids = (selected + filtered).map { it.id }.toSet()
        val excluded = listOf(filtered.title, unselected.title, filtered.likedReason, unselected.likedReason, "PRIVATE_NOTE_$suffix", "Durum:", "Okunuyor")
        fun configure() {
            onView(withText("Son bölüm")).perform(scrollTo(), click())
            onView(withText("Nedenler")).perform(scrollTo(), click())
            select(app, ids); filter("Okunuyor")
            onView(withText("Yeni öneri için dışa aktar")).perform(scrollTo(), click())
        }
        fun assertProjection(text: String, chapter: String = "17") {
            val root = JSONObject(text); val rows = root.getJSONArray("series")
            assertEquals(selected.map { it.title }.toSet(), (0 until rows.length()).map { rows.getJSONObject(it).getString("title") }.toSet())
            assertFalse(root.has("profile")); assertTrue(root.has("responseFormat"))
            repeat(rows.length()) { assertEquals(chapter, rows.getJSONObject(it).getString("lastChapter")); assertFalse(rows.getJSONObject(it).has("notes")) }
            excluded.forEach { assertFalse(text.contains(it)) }
        }
        override fun close() { (selected + filtered + unselected).forEach { app.store.get(it.id)?.let(app.store::delete) } }
    }
}
