package com.qylent.manhwatakip

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.OnBackPressedCallback

class MainActivity : LibraryActivity() {
    private lateinit var search: EditText
    private lateinit var categories: RadioGroup
    private lateinit var filterButton: ImageButton
    private var selectedFilter = 0
    private var customFilter: String? = null
    private lateinit var count: TextView
    private lateinit var empty: TextView
    private lateinit var adapter: SeriesAdapter
    private var entries = emptyList<Series>()
    private lateinit var listFrame: FrameLayout
    private var libraryList: AbsListView? = null
    private val preferences by lazy { AppPreferences(this) }
    private var selecting = false
    private val selectedIds = linkedSetOf<String>()
    private lateinit var selectionControls: LinearLayout
    private lateinit var selectAllButton: Button
    private lateinit var clearSelectionButton: Button
    private lateinit var selectionHeader: LinearLayout
    private lateinit var selectionCount: TextView
    private lateinit var selectedEdit: Button
    private lateinit var selectModeButton: Button
    private var libraryHeaderViews = emptyList<View>()
    private val selectionBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { exitSelection() }
    }
    private val app get() = application as LibraryApplication
    private val coverHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var coverProgress: LinearLayout
    private lateinit var coverStatus: TextView
    private lateinit var coverStop: Button
    private var renderedCoverState: BulkCoverQueue.Progress? = null
    private var refreshedCovers = -1
    private val pollCovers = object : Runnable {
        override fun run() {
            renderCoverProgress()
            val state = app.bulkCovers.progress
            if (state != null && state.completed != refreshedCovers && !busy) {
                refreshedCovers = state.completed; reload()
            }
            if (state?.running == true) coverHandler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shell("Kütüphanem", back = false)
        val heading = page.getChildAt(0) as LinearLayout
        (heading.getChildAt(0) as TextView).apply { textSize = 22f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        heading.addView(Ui.button(this, "+ Ekle", true) {
            if (!busy) startActivity(Intent(this, EditActivity::class.java))
        }.apply { textSize = 12f; contentDescription = "Seri ekle"; actionButtons.add(this) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, with(Ui) { dp(48) }))
        search = Ui.input(this, "Seri ara", 200).apply { setText(savedInstanceState?.getString("search") ?: "") }
        val searchRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        searchRow.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        filterButton = Ui.iconButton(this, R.drawable.ic_filter, "Filtreler ve görünüm") { showFilters() }
        searchRow.addView(filterButton, LinearLayout.LayoutParams(with(Ui) { dp(48) }, with(Ui) { dp(48) }).apply { marginStart = with(Ui) { dp(8) } })
        page.addView(searchRow)
        Ui.gap(this, page, 8)
        selectedFilter = (savedInstanceState?.getInt("filter") ?: 0).coerceIn(0, ReadingStatus.entries.size)
        customFilter = savedInstanceState?.getString("customFilter")
        selecting = savedInstanceState?.getBoolean("selecting") ?: false
        selectedIds.addAll(savedInstanceState?.getStringArrayList("selectedIds") ?: emptyList())
        categories = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            contentDescription = "Okuma durumu kategorileri"
            listOf("Hepsi", "Okunacak", "Okunan", "Ara verilen", "Bırakılan", "Tamamlandı").forEachIndexed { index, label ->
                addView(RadioButton(this@MainActivity).apply {
                    id = View.generateViewId(); text = label; tag = index
                    minHeight = with(Ui) { dp(48) }; setTextColor(Ui.green)
                    if (index == selectedFilter) isChecked = true
                })
            }
        }
        count = Ui.text(this, "Kütüphane yükleniyor…", 13f).apply { setTextColor(Ui.muted) }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(count, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        toolbar.addView(Ui.iconButton(this, R.drawable.ic_library, "Liste / ızgara görünümü") {
            if (!busy) { preferences.viewMode = if (preferences.viewMode == "grid") "list" else "grid"; showList() }
        }, LinearLayout.LayoutParams(with(Ui) { dp(48) }, with(Ui) { dp(48) }))
        toolbar.addView(Ui.iconButton(this, R.drawable.ic_sort, "Sıralama") { if (!busy) showSort() }, LinearLayout.LayoutParams(with(Ui) { dp(48) }, with(Ui) { dp(48) }))
        selectModeButton = Ui.button(this, "Seç") { enterSelection() }.apply { setPadding(with(Ui) { dp(8) }, 0, with(Ui) { dp(8) }, 0) }
        toolbar.addView(selectModeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, with(Ui) { dp(48) }))
        page.addView(toolbar)
        libraryHeaderViews = (0 until page.childCount).map { page.getChildAt(it) }
        selectionHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = Ui.surface() }
        selectionCount = Ui.text(this, "0 seri seçildi", 14f, true).apply { setPadding(with(Ui) { dp(12) }, 0, 0, 0) }
        selectionHeader.addView(selectionCount, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        selectionHeader.addView(Ui.button(this, "Seçimden çık") { exitSelection() }.apply { textSize = 13f })
        page.addView(selectionHeader)
        selectionControls = LinearLayout(this).apply { visibility = View.GONE }
        selectAllButton = Ui.button(this, "Hepsini seç") {
            selectedIds.addAll(adapter.items.map { it.id })
            adapter.notifyDataSetChanged(); updateSelectionControls()
        }
        clearSelectionButton = Ui.button(this, "Seçimi kaldır") {
            selectedIds.clear(); adapter.notifyDataSetChanged(); updateSelectionControls()
        }.apply { contentDescription = "Tüm serilerin seçimini kaldır" }
        selectionControls.addView(selectAllButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        selectionControls.addView(clearSelectionButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = with(Ui) { dp(8) } })
        page.addView(selectionControls)
        coverProgress = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = Ui.surface(); visibility = View.GONE
            setPadding(with(Ui) { dp(12) }, with(Ui) { dp(8) }, with(Ui) { dp(12) }, with(Ui) { dp(8) })
        }
        coverStatus = Ui.text(this, "", 14f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        coverProgress.addView(coverStatus)
        coverStop = Ui.button(this, "Durdur") {
            val state = app.bulkCovers.progress ?: return@button
            if (state.running) { app.bulkCovers.cancel(); renderCoverProgress() }
            else if (state.failures.isNotEmpty()) AlertDialog.Builder(this).setTitle("Toplu kapak sonucu")
                .setMessage("${state.saved} kapak kaydedildi.\n\n" + state.failures.joinToString("\n\n") { "${it.first}: ${it.second}" })
                .setPositiveButton("Kapat") { _, _ -> app.bulkCovers.clear(); renderCoverProgress() }.show()
            else { app.bulkCovers.clear(); renderCoverProgress() }
        }
        coverProgress.addView(coverStop)
        page.addView(coverProgress)
        Ui.gap(this, page)
        listFrame = FrameLayout(this)
        adapter = SeriesAdapter()
        empty = Ui.text(this, "İlk serini ekle", 20f, true).apply { gravity = Gravity.CENTER }
        listFrame.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        showList()
        page.addView(listFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        selectedEdit = Ui.button(this, "Düzenle (${selectedIds.size})", true) { showBulk() }
        page.addView(selectedEdit)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filter() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        categories.setOnCheckedChangeListener { group, checkedId ->
            val tag = group.findViewById<View>(checkedId)?.tag
            selectedFilter = tag as? Int ?: 0
            customFilter = tag as? String
            filter()
        }
        page.addView(LibraryNavigation.create(this, LibraryNavigation.Destination.LIBRARY) { !busy })
        onBackPressedDispatcher.addCallback(this, selectionBack)
        updateSelectionControls()
    }

    override fun onResume() { super.onResume(); reload(); coverHandler.post(pollCovers) }
    override fun onPause() { coverHandler.removeCallbacksAndMessages(null); super.onPause() }
    override fun onDestroy() { coverHandler.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun renderCoverProgress() {
        val state = app.bulkCovers.progress
        coverProgress.visibility = if (state == null) View.GONE else View.VISIBLE
        if (state == null) { renderedCoverState = null; refreshedCovers = -1; return }
        if (state != renderedCoverState) {
            coverStatus.text = if (state.running) "Kapaklar · ${state.completed}/${state.total}\n${state.current}\n${state.saved} kaydedildi · ${state.skipped} kapağı var · ${state.failures.size} başarısız · Seriler arası 2 saniye"
                else "${if (state.cancelled) "İşlem durduruldu" else "Kapak işlemi tamamlandı"}\n${state.saved} kaydedildi · ${state.skipped} kapağı var · ${state.failures.size} başarısız" +
                    if (state.cancelled) " · ${state.total - state.completed} işlenmedi" else ""
            renderedCoverState = state
        }
        coverStop.isEnabled = !state.running || !app.bulkCovers.cancelRequested
        coverStop.text = if (state.running) if (app.bulkCovers.cancelRequested) "Durduruluyor…" else "Durdur"
            else if (state.failures.isEmpty()) "Kapat" else "Sonuçları gör"
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("search", search.text.toString()); outState.putInt("filter", selectedFilter)
        outState.putString("customFilter", customFilter)
        outState.putBoolean("selecting", selecting); outState.putStringArrayList("selectedIds", ArrayList(selectedIds))
        super.onSaveInstanceState(outState)
    }

    private fun reload() { io({ store.all() to store.categoryDefinitions() }) { (records, definitions) ->
        val names = definitions.map { it.name }
        entries = records
        if ((libraryList is GridView) != (preferences.viewMode == "grid")) showList()
        (libraryList as? GridView)?.numColumns = gridColumns()
        selectedIds.retainAll(records.map { it.id }.toSet())
        categories.setOnCheckedChangeListener(null)
        while (categories.childCount > 6) categories.removeViewAt(6)
        if (customFilter != null && names.none { CategoryNames.key(it) == CategoryNames.key(customFilter!!) }) customFilter = null
        definitions.forEach { definition ->
            val name = definition.name
            categories.addView(RadioButton(this).apply {
                id = View.generateViewId(); text = name; tag = name
                minHeight = with(Ui) { dp(48) }; setTextColor(Ui.ink)
                buttonTintList = android.content.res.ColorStateList.valueOf(Ui.categoryColor(definition.colorId))
            })
        }
        val chosen = if (customFilter == null) selectedFilter else 6 + names.indexOfFirst { CategoryNames.key(it) == CategoryNames.key(customFilter!!) }
        categories.check(categories.getChildAt(chosen).id)
        categories.setOnCheckedChangeListener { group, id ->
            val tag = group.findViewById<View>(id)?.tag
            selectedFilter = tag as? Int ?: 0; customFilter = tag as? String; filter()
        }
        filter()
    } }

    private fun filter() {
        if (!::adapter.isInitialized) return
        val selected = ReadingStatus.entries.getOrNull(selectedFilter - 1)
        adapter.items = LibraryOrder.sort(entries.filter { entry -> entry.matches(search.text.toString()) && (selected == null || entry.status == selected) &&
            (!preferences.favoritesOnly || entry.favorite) && (customFilter == null || entry.categories.any { CategoryNames.key(it) == CategoryNames.key(customFilter!!) }) }, preferences.sort, preferences.descending)
        adapter.notifyDataSetChanged()
        updateSelectionControls()
        val active = listOfNotNull(customFilter ?: selected?.label, "Favoriler".takeIf { preferences.favoritesOnly }, "Seçim açık".takeIf { selecting })
        count.text = getString(R.string.library_count, entries.size, adapter.items.size) + if (active.isEmpty()) "" else " · " + active.joinToString(" · ")
        filterButton.contentDescription = "Filtreler ve görünüm" + if (active.isEmpty()) "" else ", " + active.joinToString(", ")
        filterButton.isSelected = selected != null || customFilter != null || preferences.favoritesOnly
        filterButton.imageTintList = android.content.res.ColorStateList.valueOf(if (filterButton.isSelected) Ui.ink else Ui.green)
        empty.text = if (entries.isEmpty() && search.text.isBlank() && selected == null && customFilter == null) "İlk serini ekle\nOkuma günlüğün burada başlayacak." else "Sonuç bulunamadı"
    }

    private fun updateSelectionControls() {
        if (!::selectedEdit.isInitialized) return
        val compactSelection = selecting && resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        libraryHeaderViews.forEach { it.visibility = if (compactSelection) View.GONE else View.VISIBLE }
        page.findViewWithTag<View>("libraryNavigation")?.apply {
            setTag(R.id.navigation_suppressed, compactSelection)
            visibility = if (compactSelection) View.GONE else View.VISIBLE
        }
        selectionHeader.visibility = if (selecting) View.VISIBLE else View.GONE
        selectedEdit.visibility = if (selecting) View.VISIBLE else View.GONE
        selectModeButton.visibility = if (selecting) View.GONE else View.VISIBLE
        selectModeButton.isEnabled = !busy && app.bulkCovers.progress?.running != true
        selectionCount.text = "${selectedIds.size} seri seçildi"
        selectionBack.isEnabled = selecting
        selectedEdit.text = "Düzenle (${selectedIds.size})"
        selectedEdit.isEnabled = selecting && selectedIds.isNotEmpty() && !busy && app.bulkCovers.progress?.running != true
        if (!::selectionControls.isInitialized || !::adapter.isInitialized) return
        selectionControls.visibility = if (selecting) View.VISIBLE else View.GONE
        val enabled = !busy && app.bulkCovers.progress?.running != true
        selectAllButton.isEnabled = enabled && adapter.items.any { it.id !in selectedIds }
        selectAllButton.contentDescription = "Görünen ${adapter.items.size} serinin hepsini seç"
        clearSelectionButton.isEnabled = enabled && selectedIds.isNotEmpty()
    }

    private fun showList() {
        libraryList?.let(listFrame::removeView)
        val list: AbsListView = if (preferences.viewMode == "grid") GridView(this).apply {
            numColumns = gridColumns()
            horizontalSpacing = with(Ui) { dp(8) }; verticalSpacing = with(Ui) { dp(10) }
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
        } else ListView(this).apply { divider = null; dividerHeight = with(Ui) { dp(10) } }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> openSeries(adapter.items[position]) }
        libraryList = list
        listFrame.addView(list, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        list.emptyView = empty
    }
    private fun openSeries(entry: Series) { startActivity(Intent(this, DetailActivity::class.java).putExtra("seriesId", entry.id)) }
    private fun gridColumns() = preferences.gridColumns ?: if (resources.configuration.screenWidthDp >= 600 ||
        resources.configuration.screenWidthDp >= 360 && resources.configuration.fontScale <= 1.15f) 3 else 2
    private fun enterSelection() {
        if (busy || app.bulkCovers.progress?.running == true) return
        getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
        selecting = true; adapter.notifyDataSetChanged(); filter()
    }
    private fun exitSelection() {
        selecting = false; selectedIds.clear(); filter()
    }
    private fun showSort() {
        AlertDialog.Builder(this).setTitle("Sıralama").setItems(arrayOf("Ad A→Z", "Ad Z→A", "Yeni eklenenler", "Eski eklenenler", "Son güncellenenler", "İlk güncellenenler", "Bölüm yüksek→düşük", "Bölüm düşük→yüksek")) { _, index ->
            preferences.sort = listOf("title", "created", "updated", "chapter")[index / 2]
            preferences.descending = if (index < 2) index == 1 else index % 2 == 0
            filter()
        }.show()
    }
    private fun showFilters() {
        if (busy) return
        (categories.parent as? ViewGroup)?.removeView(categories)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(with(Ui) { dp(16) }, 0, with(Ui) { dp(16) }, 0)
            addView(categories)
            Ui.gap(this@MainActivity, this)
            addView(Ui.button(this@MainActivity, "Görünüm / sıralama") { showOptions() })
        }
        AlertDialog.Builder(this).setTitle("Filtreler ve görünüm")
            .setView(ScrollView(this).apply { addView(body) })
            .setNeutralButton("Sıfırla") { _, _ ->
                selectedFilter = 0; customFilter = null; preferences.favoritesOnly = false
                categories.check(categories.getChildAt(0).id); filter()
            }.setPositiveButton("Tamam", null).show()
    }
    override fun onBusyChanged(active: Boolean) {
        if (::filterButton.isInitialized) filterButton.isEnabled = !active
        updateSelectionControls()
    }
    private fun showOptions() {
        AlertDialog.Builder(this).setTitle("Kütüphane seçenekleri").setItems(arrayOf("Liste görünümü", "Izgara görünümü", "Sıralama", if (preferences.favoritesOnly) "Tüm serileri göster" else "Yalnızca favoriler")) { _, which ->
            when (which) {
                0, 1 -> { preferences.viewMode = if (which == 0) "list" else "grid"; showList() }
                2 -> showSort()
                3 -> { preferences.favoritesOnly = !preferences.favoritesOnly; filter() }
            }
        }.show()
    }
    private fun change(entry: Series, after: Series) { if (!busy) io({ store.applyProposal(AiProposal(entry, after)) }) { reload() } }
    private fun enterChapter(entry: Series) {
        val input = Ui.input(this, "Son okunan bölüm", 20).apply { setText(entry.lastChapter); keyListener = android.text.method.DigitsKeyListener.getInstance("0123456789.,") }
        AlertDialog.Builder(this).setTitle(entry.title).setView(input).setNegativeButton("Vazgeç", null)
            .setPositiveButton("Kaydet") { _, _ -> change(entry, entry.copy(lastChapter = input.text.toString())) }.show()
    }
    private fun showBulk() {
        if (app.bulkCovers.progress?.running == true) { notice("Kapak işlemi zaten sürüyor."); return }
        val selected = entries.filter { it.id in selectedIds }
        if (selected.isEmpty()) { notice("En az bir seri seç."); return }
        fun confirm(status: ReadingStatus?, add: List<String>, remove: List<String>, summary: String) {
            AlertDialog.Builder(this).setTitle("${selected.size} seri değişecek").setMessage("$summary\n\n${selected.joinToString("\n") { it.title }}")
                .setNegativeButton("Vazgeç", null).setPositiveButton("Değişiklikleri uygula") { _, _ ->
                    io({ store.applyBulk(selected, status, add, remove) }) { selecting = false; selectedIds.clear(); reload() }
                }.show()
        }
        AlertDialog.Builder(this).setTitle("Toplu düzenleme").setItems(arrayOf("Okuma durumunu değiştir", "Kategori ekle", "Kategori kaldır", "Türkçe kaynaklarda kapak bul")) { _, which ->
            if (which == 3) { confirmBulkCovers(selected); return@setItems }
            if (which == 0) AlertDialog.Builder(this).setTitle("Yeni okuma durumu").setItems(ReadingStatus.entries.map { it.label }.toTypedArray()) { _, position -> confirm(ReadingStatus.entries[position], emptyList(), emptyList(), "Durum: ${ReadingStatus.entries[position].label}") }.show()
            else io({ store.categoryDefinitions() }) { definitions -> CategoryPicker.show(this, definitions, emptyList()) { names ->
                if (names.isNotEmpty()) confirm(null, if (which == 1) names else emptyList(), if (which == 2) names else emptyList(), "${if (which == 1) "Eklenecek" else "Kaldırılacak"} kategoriler: ${names.joinToString()}")
            } }
        }.show()
    }

    private fun confirmBulkCovers(selected: List<Series>) {
        io({
            val missing = selected.count { !app.covers.contains(it.id) }
            missing to if (missing > 0) AiSecretStore(applicationContext).settings() else null
        }) { (missing, settings) ->
            if (missing == 0) {
                AlertDialog.Builder(this).setTitle("Eksik kapak yok")
                    .setMessage("Seçilen ${selected.size} serinin kapağı zaten var. Arama veya AI isteği gönderilmedi.")
                    .setPositiveButton("Tamam", null).show()
                return@io
            }
            AlertDialog.Builder(this).setTitle("$missing seri için kapak bul")
                .setMessage("Seri adları ve alternatif adları TRManga, Elder Manga, Uzay Manga ve OkuToon'da aranacak. Okuma bağlantısı gerekmez; varsa önce kullanılır.\n\n" +
                    "${selected.size - missing} serinin kapağı zaten var; arama/AI isteği yapılmadan atlanacak. Yalnızca eksik kapaklar otomatik kaydedilir. Aranan seriler arasında 2 saniye beklenir. Bulunamayan veya işlem sırasında değişen serilerin kapakları korunur.\n\n" +
                    (settings?.let { "Gerekirse seri adı ve aday başlık/bağlantıları ${it.model} modeline (${it.baseUrl}) gönderilir. ${if (it.isLocalHttp) "Yerel HTTP şifrelenmez. " else ""}\n\n" } ?: "") +
                    "İlerlemeyi kütüphaneden izleyebilir ve durdurabilirsin. Uygulamayı tamamen kapatırsan kalan sıra devam etmez.")
                .setNegativeButton("Vazgeç", null).setPositiveButton("Başlat ve otomatik kaydet") { _, _ ->
                    val application = app
                    val secrets = AiSecretStore(application)
                    val started = application.bulkCovers.start(selected, application.networkExecutor, { entry ->
                        val connection = if (settings == null) null else secrets.connection()
                        require(secrets.settings() == settings && connection?.settings == settings) { "AI ayarları değişti; işlemi yeniden başlat." }
                        val fetcher = CoverFetcher()
                        val complete: ((org.json.JSONArray) -> String)? = connection?.let { current ->
                            { messages -> AiClient().complete(current.settings, current.key, messages) }
                        }
                        AiCoverFinder(complete, { source -> fetcher.fetch(source).also { CoverStore.decode(it.bytes).recycle() } }, fetcher::search)
                            .find(entry.title, entry.url, entry.alternativeTitles.take(20))
                    }, { entry, cover ->
                        try { application.executor.submit<Series> { application.store.applyCover(entry, cover, application.covers) }.get() }
                        catch (error: java.util.concurrent.ExecutionException) { throw (error.cause as? Exception ?: error) }
                    }, hasCover = { entry -> application.covers.contains(entry.id) })
                    if (started) {
                        selecting = false; selectedIds.clear(); adapter.notifyDataSetChanged(); filter()
                        refreshedCovers = -1; coverHandler.removeCallbacks(pollCovers); coverHandler.post(pollCovers)
                    } else notice("Kapak işlemi zaten sürüyor.")
                }.show()
        }
    }

    private inner class SeriesAdapter : BaseAdapter() {
        var items = emptyList<Series>()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val entry = items[position]
            val grid = preferences.viewMode == "grid"
            val card = LinearLayout(this@MainActivity).apply {
                orientation = if (grid) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                if (!grid) background = Ui.surface()
                val pad = if (grid) 0 else with(Ui) { dp(8) }
                setPadding(pad, pad, pad, pad)
            }
            val art = FrameLayout(this@MainActivity).apply {
                background = Ui.surface(Ui.userBubble)
                clipToOutline = true
            }
            art.addView(Ui.text(this@MainActivity, entry.title.take(1).uppercase(java.util.Locale.forLanguageTag("tr-TR")), 36f, true).apply {
                gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val cover = ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = "${entry.title} kapağı"
            }
            art.addView(cover, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (entry.coverUrl.isNotEmpty()) (application as LibraryApplication).covers.bind(this@MainActivity, cover, entry.id)
            val availableWidth = (resources.configuration.screenWidthDp - 40 - (if (grid) (gridColumns() - 1) * 8 else 0)) / (if (grid) gridColumns() else 1)
            card.addView(art, LinearLayout.LayoutParams(if (grid) ViewGroup.LayoutParams.MATCH_PARENT else with(Ui) { dp(64) }, with(Ui) { dp(if (grid) availableWidth * 4 / 3 else 92) }))
            val body = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(if (grid) 0 else with(Ui) { dp(12) }, with(Ui) { dp(8) }, 0, 0)
            }
            card.addView(body, if (grid) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            body.addView(Ui.text(this@MainActivity, entry.title, if (grid) 12f else 16f, true).apply {
                maxLines = 2; minLines = if (grid) 2 else 1; ellipsize = TextUtils.TruncateAt.END
            })
            body.addView(Ui.text(this@MainActivity, entry.status.label, if (grid) 10f else 12f, true).apply {
                setTextColor(Ui.accent); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = with(Ui) { dp(4) } })
            val chapter = if (entry.lastChapter.isBlank()) getString(R.string.chapter_missing) else getString(R.string.chapter_number, entry.lastChapter)
            body.addView(Ui.text(this@MainActivity, chapter, if (grid) 10f else 12f).apply { setTextColor(Ui.muted); maxLines = 2; setPadding(0, with(Ui) { dp(4) }, 0, 0) })
            val markers = listOfNotNull("Favori".takeIf { entry.favorite }, "Sabit".takeIf { entry.pinned }, entry.opinion.label.takeUnless { entry.opinion == Opinion.UNSET })
            if (markers.isNotEmpty()) body.addView(Ui.text(this@MainActivity, markers.joinToString(" · "), 12f).apply { setTextColor(Ui.muted) })
            card.setOnClickListener {
                if (selecting) {
                    if (entry.id in selectedIds) selectedIds.remove(entry.id) else selectedIds.add(entry.id)
                    notifyDataSetChanged(); updateSelectionControls()
                } else openSeries(entry)
            }
            if (selecting) body.addView(CheckBox(this@MainActivity).apply {
                text = "Seç"; setTextColor(Ui.ink); isChecked = entry.id in selectedIds; minHeight = with(Ui) { dp(48) }
                contentDescription = "${entry.title}: seç"
                setOnCheckedChangeListener { _, checked -> if (checked) selectedIds.add(entry.id) else selectedIds.remove(entry.id); updateSelectionControls() }
            }) else {
                val actions = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, with(Ui) { dp(8) }, 0, 0) }
                actions.addView(Ui.button(this@MainActivity, "+1") { change(entry, entry.copy(lastChapter = ReadingHistory.nextChapter(entry.lastChapter))) }.apply {
                    contentDescription = "${entry.title}: bir bölüm ilerle"; textSize = 12f; setPadding(0, 0, 0, 0)
                }, LinearLayout.LayoutParams(0, with(Ui) { dp(48) }, 1f))
                actions.addView(Ui.iconButton(this@MainActivity, R.drawable.ic_more, "${entry.title}: seri işlemleri") {
                    AlertDialog.Builder(this@MainActivity).setTitle(entry.title).setItems(arrayOf("Bölüm gir", if (entry.favorite) "Favorilerden çıkar" else "Favorilere ekle", if (entry.pinned) "Sabitlemeyi kaldır" else "Sabitle", "Seriyi aç")) { _, which ->
                        when (which) { 0 -> enterChapter(entry); 1 -> change(entry, entry.copy(favorite = !entry.favorite)); 2 -> change(entry, entry.copy(pinned = !entry.pinned)); 3 -> openSeries(entry) }
                    }.show()
                }, LinearLayout.LayoutParams(with(Ui) { dp(48) }, with(Ui) { dp(48) }).apply { marginStart = with(Ui) { dp(if (grid) 0 else 8) } })
                body.addView(actions)
            }
            return card
        }
    }
}
