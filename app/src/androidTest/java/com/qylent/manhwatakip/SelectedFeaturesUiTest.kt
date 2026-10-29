package com.qylent.manhwatakip

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import org.junit.Test
import org.hamcrest.Matchers.allOf

class SelectedFeaturesUiTest {
    private val idle = ScreenIdle()
    @org.junit.Before fun registerIdle() { androidx.test.espresso.IdlingRegistry.getInstance().register(idle) }
    @org.junit.After fun unregisterIdle() { androidx.test.espresso.IdlingRegistry.getInstance().unregister(idle); idle.close() }
    @Test fun sharedLinkOpensUnsavedFormAndExistingSeriesWithoutWriting() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as LibraryApplication
        val entry = app.store.save(Series(title = "Paylaş ${System.nanoTime()}", url = "https://example.com/${System.nanoTime()}"))
        fun share(text: String) = Intent(context, ShareActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        try {
            val before = app.store.all()
            ActivityScenario.launch<ShareActivity>(share("https://example.com/new-shared-link")).use {
                onView(withHint("Okuma bağlantısı (isteğe bağlı)")).check(matches(withText("https://example.com/new-shared-link")))
                org.junit.Assert.assertEquals(before, app.store.all())
            }
            ActivityScenario.launch<ShareActivity>(share(entry.url)).use {
                onView(withText(entry.title)).check(matches(isDisplayed()))
                org.junit.Assert.assertEquals(before, app.store.all())
            }
            ActivityScenario.launch<ShareActivity>(share("file:///secret")).use {
                androidx.test.espresso.Espresso.onIdle(); org.junit.Assert.assertEquals(before, app.store.all())
            }
        } finally { app.store.get(entry.id)?.let(app.store::delete) }
    }
    @Test fun chapterUndoFavoritesAndGridKeepTheSameSearchResult() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as LibraryApplication
        val entry = app.store.save(Series(title = "İlerleme ${System.nanoTime()}", lastChapter = "48.5", alternativeTitles = listOf("Alias-${System.nanoTime()}")))
        val prefs = AppPreferences(context); val mode = prefs.viewMode; val favoriteFilter = prefs.favoritesOnly
        try {
            ActivityScenario.launch<DetailActivity>(Intent(context, DetailActivity::class.java).putExtra("seriesId", entry.id)).use {
                onView(withText("Bölüm gir")).perform(scrollTo(), click())
                onView(withHint("Son okunan bölüm")).perform(replaceText("48,5"), closeSoftKeyboard())
                onView(withText("Kaydet")).perform(click())
                onView(withContentDescription("${entry.title}: bir bölüm ilerle")).perform(click())
                onView(withText("Favorilere ekle")).perform(scrollTo(), click())
                onView(withText("Sabitle")).perform(scrollTo(), click())
                onView(withText("Okuma geçmişi")).perform(scrollTo(), click())
                onView(withText("Bu bölüm değişikliğini geri al")).perform(scrollTo(), click())
                onView(withText("Geri al")).perform(click())
                androidx.test.espresso.Espresso.onIdle()
                org.junit.Assert.assertEquals("48.5", app.store.get(entry.id)!!.lastChapter)
                org.junit.Assert.assertEquals(2, app.store.history(entry.id).size)
            }
            prefs.viewMode = "list"; prefs.favoritesOnly = false
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                onView(withHint("Seri ara")).perform(replaceText(entry.alternativeTitles.single()), closeSoftKeyboard())
                onView(withText(entry.title)).check(matches(isDisplayed()))
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                prefs.viewMode = "grid" // As after importing a missing view preference from a full backup.
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                onView(isAssignableFrom(android.widget.GridView::class.java)).check(matches(isDisplayed()))
                openLibraryFilters()
                onView(withText("Görünüm / sıralama")).perform(click())
                onView(withText("Izgara görünümü")).perform(click())
                onView(withText("Tamam")).perform(click())
                onView(withText(entry.title)).check(matches(isDisplayed()))
                scenario.recreate()
                onView(withText(entry.title)).check(matches(isDisplayed()))
                onView(isAssignableFrom(android.widget.GridView::class.java)).check(matches(isDisplayed()))
            }
            ActivityScenario.launch(StatsActivity::class.java).use {
                onView(withText("Takip döneminde net bölüm ilerlemesi")).perform(scrollTo()).check(matches(isDisplayed()))
            }
        } finally { prefs.viewMode = mode; prefs.favoritesOnly = favoriteFilter; app.store.get(entry.id)?.let(app.store::delete) }
    }
    @Test fun colorCodeSearchCreatesTheRequestedCustomColor() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as LibraryApplication
        val name = "Renk-${System.nanoTime()}"
        try {
            ActivityScenario.launch(CategoriesActivity::class.java).use {
                onView(withText("Kategori oluştur")).perform(click())
                onView(withHint("Kategori adı")).perform(replaceText(name), closeSoftKeyboard())
                onView(withHint("Renk ara (ad veya kod)")).perform(replaceText("#AB1234"), closeSoftKeyboard())
                onView(withText("Kaydet")).perform(click())
                androidx.test.espresso.Espresso.onIdle()
                org.junit.Assert.assertEquals("#AB1234", app.store.categoryDefinitions().single { it.name == name }.colorId)
            }
        } finally { if (name in app.store.categories()) app.store.changeCategory(name, null) }
    }
    @Test fun bulkPreviewCanBeCancelledAndSelectionSurvivesRotation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as LibraryApplication
        val entry = app.store.save(Series(title = "Toplu-${System.nanoTime()}"))
        val prefs = AppPreferences(context); val mode = prefs.viewMode; val fav = prefs.favoritesOnly
        prefs.viewMode = "list"; prefs.favoritesOnly = false
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                onView(withHint("Seri ara")).perform(replaceText(entry.title), closeSoftKeyboard())
                onView(withText("Seç")).perform(click())
                onView(allOf(withText("Seç"), isAssignableFrom(android.widget.CheckBox::class.java))).perform(click())
                scenario.recreate()
                onView(allOf(withText("Seç"), isAssignableFrom(android.widget.CheckBox::class.java))).check(matches(isChecked()))
                onView(withText("Düzenle (1)")).perform(click())
                onView(withText("Okuma durumunu değiştir")).perform(click())
                onView(withText("Tamamlandı")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
                onView(withText("Vazgeç")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
                org.junit.Assert.assertEquals(entry, app.store.get(entry.id))
                onView(withText("Düzenle (1)")).perform(click())
                onView(withText("Okuma durumunu değiştir")).perform(click())
                onView(withText("Tamamlandı")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
                onView(withText("Değişiklikleri uygula")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
                androidx.test.espresso.Espresso.onIdle()
                org.junit.Assert.assertEquals(ReadingStatus.COMPLETED, app.store.get(entry.id)!!.status)
            }
        } finally { prefs.viewMode = mode; prefs.favoritesOnly = fav; app.store.get(entry.id)?.let(app.store::delete) }
    }
    @Test fun categorySearchPreservesSelectionAndAlternativeNamesStayOptional() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val category = "Picker-${System.nanoTime()}"
        val otherCategories = (1..20).map { "$category-$it" }
        LibraryStore(context).use { store -> store.addCategory(category, "#E53935"); otherCategories.forEach { store.addCategory(it, "blue") } }
        try {
            ActivityScenario.launch<EditActivity>(Intent(context, EditActivity::class.java)).use {
                onView(withText("Kategori seç")).perform(scrollTo(), click())
                onView(withHint("Kategori ara")).perform(replaceText(category), closeSoftKeyboard())
                onView(allOf(withText(category), isAssignableFrom(android.widget.CheckBox::class.java))).perform(click())
                onView(withHint("Kategori ara")).perform(replaceText("bulunmayan"), closeSoftKeyboard())
                onView(withHint("Kategori ara")).perform(replaceText(category), closeSoftKeyboard())
                onView(allOf(withText(category), isAssignableFrom(android.widget.CheckBox::class.java))).check(matches(isChecked()))
                onView(withContentDescription("Kategori renk filtresi")).perform(click())
                androidx.test.espresso.Espresso.onData(org.hamcrest.Matchers.equalTo("Mavi")).inRoot(androidx.test.espresso.matcher.RootMatchers.isPlatformPopup()).perform(click())
                onView(withContentDescription("Kategori renk filtresi")).perform(click())
                androidx.test.espresso.Espresso.onData(org.hamcrest.Matchers.equalTo("#E53935")).inRoot(androidx.test.espresso.matcher.RootMatchers.isPlatformPopup()).perform(click())
                onView(allOf(withText(category), isAssignableFrom(android.widget.CheckBox::class.java))).check(matches(isChecked()))
                onView(withText("Seçimi uygula")).perform(click())
                onView(withText("Alternatif adlar")).perform(scrollTo(), click())
                onView(withHint("Alternatif adlar (her satır bir ad)")).perform(scrollTo(), replaceText("English Title"), closeSoftKeyboard())
            }
        } finally { LibraryStore(context).use { store -> (otherCategories + category).forEach { store.changeCategory(it, null) } } }
    }
}
