package com.qylent.manhwatakip

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future

class EditActivity : LibraryActivity() {
    class SaveState : ViewModel() {
        var pending: Future<Series>? = null
        var archiveCleanupFailed = false
        var coverResult: CoverResult? = null
        var coverReadingUrl = ""
        var coverBitmap: android.graphics.Bitmap? = null
        var aiCoverPending: Future<CoverResult>? = null
        var aiCoverTitle = ""
        var aiCoverUrl = ""
        var aiCoverRequested = false
        var coverTitle = ""
        var metadataPending: Future<SeriesMetadata>? = null
        var metadataUrl = ""
        var metadataTitle = ""
        var metadataRevision = 0
        var editRevision = 0
        val excludedCovers = mutableSetOf<String>()
    }
    private val saveState by lazy { ViewModelProvider(this)[SaveState::class.java] }
    private var baseline = Series(title = "")
    private val selectedCategories = linkedSetOf<String>()
    private val categoryChecks = mutableListOf<CheckBox>()
    private val fields = linkedMapOf<String, EditText>()
    private lateinit var status: Spinner
    private lateinit var opinion: Spinner
    private lateinit var error: TextView
    private val proposalToken get() = intent.getStringExtra("proposalId")
    private var proposal: AiProposal? = null
    private val coverHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var coverTask: Runnable? = null
    private var coverSequence = 0
    private fun coverCurrent() = saveState.coverReadingUrl == fields.getValue("url").text.toString().trim() &&
        (saveState.coverTitle.isEmpty() || saveState.coverTitle == fields.getValue("title").text.toString().trim())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        proposal = proposalToken?.let { (application as LibraryApplication).proposals[it] }
        if (proposal == null && proposalToken != null && !proposalToken!!.startsWith("mcp:") && savedInstanceState?.containsKey("proposal_after") == true) {
            proposal = AiProposal(savedInstanceState.getString("proposal_before")?.let { BackupCodec.decode(it).single() },
                BackupCodec.decode(savedInstanceState.getString("proposal_after")!!).single())
            (application as LibraryApplication).proposals[proposalToken!!] = proposal!!
        }
        shell(if (proposalToken != null) "Öneriyi incele" else if (intent.hasExtra("seriesId")) "Seriyi düzenle" else "Yeni seri")
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (busy) { notice("İşlem sürüyor; tamamlanmasını bekle."); return }
                if (fields.isNotEmpty() && draft() != baseline) {
                    AlertDialog.Builder(this@EditActivity).setTitle("Değişikliklerden vazgeçilsin mi?")
                        .setMessage("Kaydedilmemiş değişiklikler silinecek.")
                        .setNegativeButton("Düzenlemeye devam et", null)
                        .setPositiveButton("Vazgeç") { _, _ -> finish() }.show()
                } else finish()
            }
        })
        val token = proposalToken
        if (token?.startsWith("mcp:") == true) {
            io({
                val id = token.removePrefix("mcp:")
                if (id in store.bridgeDecisions()) null else BridgePreferences(this).pending().firstOrNull { it.token == id }?.proposal
            }) { loaded ->
                if (loaded == null && saveState.pending == null) { notice("Öneri artık beklemiyor; yeniden hazırla."); finish() }
                else { proposal = loaded ?: proposal; restoreDraft(savedInstanceState) }
            }
        } else if (token != null && proposal == null) { notice("Öneri artık bulunamıyor; yeniden hazırla."); finish() }
        else restoreDraft(savedInstanceState)
    }

    private fun restoreDraft(savedInstanceState: Bundle?) {
        if (savedInstanceState != null && savedInstanceState.containsKey("draft_title")) {
            baseline = fromBundle(savedInstanceState, "base_")
            render(fromBundle(savedInstanceState, "draft_"))
            saveState.pending?.let { awaitSave(it) }
        } else {
            val id = intent.getStringExtra("seriesId")
            if (proposal != null) { baseline = proposal!!.before ?: proposal!!.after.copy(title = ""); render(proposal!!.after) }
            else if (id == null) { baseline = baseline.copy(url = intent.getStringExtra("readingUrl") ?: ""); render(baseline) }
            else io({ store.get(id) }) { entry ->
                if (entry == null) { notice("Kayıt bulunamadı."); finish() }
                else { baseline = entry; render(entry) }
            }
        }
    }

    private fun render(entry: Series) {
        selectedCategories.clear(); selectedCategories.addAll(entry.categories)
        val column = Ui.scroll(this, page)
        column.addView(Ui.text(this, "Seri adıyla veya okuduğun bağlantıyla başla. Kaydetmeden önce adı doğrula.", 14f).apply { setTextColor(Ui.muted) })
        error = Ui.text(this, "", 14f).apply { setTextColor(if (Ui.dark) android.graphics.Color.rgb(255, 171, 162) else android.graphics.Color.rgb(154, 46, 40)); visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        column.addView(error)
        fun field(key: String, hint: String, value: String, limit: Int, multiline: Boolean = false, parent: LinearLayout = column): EditText {
            Ui.label(this, parent, hint)
            return Ui.input(this, hint, limit, multiline).apply {
                inputType = if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setText(value)
                fields[key] = this
                parent.addView(this)
            }
        }
        field("title", "Seri adı", entry.title, 200)
        val url = field("url", "Okuma bağlantısı (isteğe bağlı)", entry.url, 2048).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        val cover = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Seri kapağı"; visibility = View.GONE }
        column.addView(cover, LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, with(Ui) { dp(150) }))
        val coverInfo = Ui.text(this, "Kapak okuma bağlantısından hazırlanır.", 13f).apply { setTextColor(Ui.muted) }
        column.addView(coverInfo)
        val app = application as LibraryApplication
        if (entry.coverUrl.isNotBlank()) { cover.visibility = View.VISIBLE; app.covers.bind(this, cover, entry.id) }
        if (saveState.coverReadingUrl == entry.url && saveState.coverBitmap != null) { cover.tag = "preview"; cover.visibility = View.VISIBLE; cover.setImageBitmap(saveState.coverBitmap) }
        val pollMetadata = object : Runnable {
            override fun run() {
                val pending = saveState.metadataPending ?: return
                if (!pending.isDone) { coverHandler.postDelayed(this, 100); return }
                // Initial category/store loading also sets busy; don't lose an already completed response.
                if (busy && saveState.pending == null) { coverHandler.postDelayed(this, 100); return }
                saveState.metadataPending = null
                val current = !busy && saveState.pending == null &&
                    saveState.metadataRevision == saveState.editRevision &&
                    saveState.metadataUrl == url.text.toString().trim() &&
                    saveState.metadataTitle == fields.getValue("title").text.toString().trim()
                if (!current) return
                runCatching { pending.get() }.fold({ metadata ->
                    if (saveState.metadataTitle.isBlank() && !metadata.title.isNullOrBlank()) fields.getValue("title").setText(metadata.title)
                    val found = metadata.cover
                    if (found != null) {
                        val bitmap = runCatching { CoverStore.decode(found.bytes) }.getOrNull()
                        if (bitmap != null) {
                            saveState.coverReadingUrl = saveState.metadataUrl; saveState.coverResult = found
                            saveState.coverTitle = fields.getValue("title").text.toString().trim()
                            saveState.coverBitmap = bitmap; cover.tag = "preview"; cover.visibility = View.VISIBLE; cover.setImageBitmap(bitmap)
                        }
                    }
                    coverInfo.text = (metadata.title?.let { "${if (metadata.inferredTitle) "Tahmini ad (URL): " else "Kaynak adı: "}$it\n" } ?: "") +
                        (if (found != null) "Kapak hazır. Kontrol edip Kaydet'e dokun." else "Geçerli kapak bulunamadı; adla arayabilir veya elle düzenleyebilirsin.")
                }, { coverInfo.text = "Kaynak bilgileri getirilemedi; elle düzenleyebilir veya adla kapak arayabilirsin." })
            }
        }
        fun requestCover(force: Boolean = false) {
            if (saveState.aiCoverPending != null) return
            val reading = url.text.toString().trim()
            if (saveState.metadataPending != null && saveState.metadataUrl == reading && !force) return
            if (reading.isEmpty() || !force && reading == saveState.coverReadingUrl) return
            if (runCatching { SeriesMetadataFetcher.startUrl(reading) }.isFailure) { coverInfo.text = "Public HTTPS seri bağlantısını girince ad ve kapak hazırlanır."; return }
            coverInfo.text = "Kaynak adı ve kapağı getiriliyor…"
            saveState.metadataUrl = reading; saveState.metadataTitle = fields.getValue("title").text.toString().trim()
            saveState.metadataRevision = saveState.editRevision
            saveState.metadataPending = app.networkExecutor.submit<SeriesMetadata> { SeriesMetadataFetcher().fetch(reading) }
            coverHandler.removeCallbacks(pollMetadata); coverHandler.post(pollMetadata)
        }
        val fetch = Ui.button(this, "Kapağı getir / yenile") { requestCover(true) }
        column.addView(fetch); actionButtons.add(fetch)
        val aiCover = Ui.button(this, "Türkçe kaynaklarda kapak ara") {}
        column.addView(aiCover); actionButtons.add(aiCover)
        val otherCover = Ui.button(this, "Farklı kaynaktan bul") {}
        column.addView(otherCover); actionButtons.add(otherCover)
        fun lockCover() {
            val active = saveState.aiCoverPending != null
            actionButtons.forEach { it.isEnabled = !active && !busy }
            onBusyChanged(active || busy)
            aiCover.text = if (active) "Kapak aranıyor…" else "Türkçe kaynaklarda kapak ara"
        }
        val pollCover = object : Runnable {
            override fun run() {
                val pending = saveState.aiCoverPending ?: return
                if (!pending.isDone) { coverHandler.postDelayed(this, 200); return }
                saveState.aiCoverPending = null
                runCatching { pending.get() }.fold({ found ->
                    if (fields.getValue("title").text.toString().trim() == saveState.aiCoverTitle && url.text.toString().trim() == saveState.aiCoverUrl) {
                        saveState.coverResult = found; saveState.coverReadingUrl = saveState.aiCoverUrl; saveState.coverTitle = saveState.aiCoverTitle
                        val bitmap = CoverStore.decode(found.bytes)
                        saveState.coverBitmap = bitmap; cover.tag = "preview"; cover.visibility = View.VISIBLE; cover.setImageBitmap(bitmap)
                        coverInfo.text = "Türkçe kaynaktaki seri kapağı bulundu. Kontrol edip Kaydet'e dokun.\nKaynak: ${found.seriesUrl}"
                    }
                }, { error ->
                    val cause = (error as? ExecutionException)?.cause ?: error
                    coverInfo.text = if (cause is IllegalArgumentException) cause.message else "Kapak araması tamamlanamadı. Modelin görsel desteğini ve bağlantını kontrol et; mevcut kapak korunuyor."
                })
                lockCover()
            }
        }
        fun searchCover(alternative: Boolean) {
            if (busy || saveState.aiCoverPending != null) return
            val title = fields.getValue("title").text.toString().trim()
            val reading = url.text.toString().trim()
            val alternatives = fields.getValue("alternativeTitles").text.toString().lines().map(String::trim).filter(String::isNotBlank)
            if (title.isBlank()) { notice("Önce seri adını gir."); return }
            val excluded = if (alternative) {
                saveState.excludedCovers.addAll(listOfNotNull(saveState.coverResult?.seriesUrl, saveState.coverResult?.imageUrl,
                    entry.coverUrl.takeIf(String::isNotBlank), reading.takeIf(String::isNotBlank)))
                saveState.excludedCovers.toSet()
            } else emptySet()
            io({ AiSecretStore(this).settings() }) { settings ->
                AlertDialog.Builder(this).setTitle(if (alternative) "Farklı kaynaktan bul" else "Türkçe kaynaklarda kapak ara")
                    .setMessage("Seri adı ve alternatif adları TRManga, Elder Manga, Uzay Manga ve OkuToon'da aranacak. Okuma bağlantısı gerekmez; kapaktaki yazının dili önemli değildir. " +
                        (settings?.let { "Gerekirse seri adı ve bulunan adayların başlık/bağlantıları ${it.model} modeline (${it.baseUrl}) eşleştirme için gönderilecek. ${if (it.isLocalHttp) "Yerel HTTP bağlantısı şifrelenmez. " else ""}" } ?: "") +
                        (if (alternative) "Önceki kaynak ve görsel atlanacak. " else "") + "Bulunan kapak yalnızca Kaydet'e dokununca uygulanır.")
                    .setNegativeButton("İptal", null).setPositiveButton("Ara") { _, _ ->
                        if (busy || saveState.aiCoverPending != null) return@setPositiveButton
                        if (fields.getValue("title").text.toString().trim() != title || url.text.toString().trim() != reading) {
                            notice("Seri bilgileri değişti. Aramayı yeniden başlat."); return@setPositiveButton
                        }
                         coverSequence++; coverTask?.let(coverHandler::removeCallbacks)
                         saveState.editRevision++ // A metadata request cannot replace an explicitly requested AI cover.
                        saveState.aiCoverTitle = title; saveState.aiCoverUrl = reading
                        saveState.aiCoverPending = app.networkExecutor.submit<CoverResult> {
                            val connection = if (settings == null) null else AiSecretStore(this).connection()
                            require(connection?.settings == settings) { "AI ayarları değişti. Aramayı yeniden onayla." }
                            val fetcher = CoverFetcher()
                            val complete: ((org.json.JSONArray) -> String)? = connection?.let { current ->
                                { messages -> AiClient().complete(current.settings, current.key, messages) }
                            }
                            AiCoverFinder(complete, { source -> fetcher.fetch(source).also { CoverStore.decode(it.bytes).recycle() } }, fetcher::search)
                                .find(title, reading, alternatives, excluded)
                        }
                        coverInfo.text = "Seri adı Türkçe kaynaklarda aranıyor…"
                        lockCover(); coverHandler.post(pollCover)
                    }.show()
            }
        }
        aiCover.setOnClickListener { searchCover(false) }
        otherCover.setOnClickListener { searchCover(true) }
        Ui.watch(url) {
            coverSequence++
            saveState.editRevision++
            coverTask?.let(coverHandler::removeCallbacks)
            coverTask = Runnable { requestCover() }.also { coverHandler.postDelayed(it, 700) }
        }
        Ui.watch(fields.getValue("title")) { saveState.editRevision++ }
        if (saveState.metadataPending != null) coverHandler.post(pollMetadata)
        else if (entry.url.isNotEmpty() && entry.coverUrl.isEmpty() && saveState.coverResult == null) requestCover()
        field("lastChapter", "Son okunan bölüm", entry.lastChapter, 20).apply {
            keyListener = DigitsKeyListener.getInstance("0123456789.,")
            setRawInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        }
        Ui.label(this, column, "Okuma durumu")
        status = Spinner(this).apply {
            contentDescription = "Okuma durumu"
            adapter = ArrayAdapter(this@EditActivity, android.R.layout.simple_spinner_dropdown_item, ReadingStatus.entries.map { it.label })
            setSelection(entry.status.ordinal); minimumHeight = with(Ui) { dp(48) }
        }
        column.addView(status)
        Ui.label(this, column, "Özel kategoriler (isteğe bağlı, çoklu seçim)")
        val categorySummary = Ui.text(this, selectedCategories.joinToString(" · ").ifEmpty { "Kategori seçilmedi" }, 14f)
        column.addView(categorySummary)
        io({ store.categoryDefinitions() }) { definitions ->
            val all = definitions + entry.categories.filter { name -> definitions.none { CategoryNames.key(it.name) == CategoryNames.key(name) } }.map { CategoryDefinition(it) }
            val button = Ui.button(this, "Kategori seç") {
                CategoryPicker.show(this, all, selectedCategories.toList()) { names ->
                    selectedCategories.clear(); selectedCategories.addAll(names)
                    categorySummary.text = names.joinToString(" · ").ifEmpty { "Kategori seçilmedi" }
                }
            }
            column.addView(button, column.indexOfChild(categorySummary) + 1); actionButtons.add(button)
            if (all.isEmpty()) categorySummary.text = "Ayarlar → Kategoriler menüsünden kategori oluşturabilirsin."
        }
        fun section(name: String): LinearLayout {
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            val toggle = Ui.button(this, name) { body.visibility = if (body.visibility == View.GONE) View.VISIBLE else View.GONE }
            column.addView(toggle); actionButtons.add(toggle); column.addView(body)
            return body
        }
        val aliases = section("Alternatif adlar")
        field("alternativeTitles", "Alternatif adlar (her satır bir ad)", entry.alternativeTitles.joinToString("\n"), 20100, true, aliases)
        val details = section("Diğer bilgiler")
        Ui.label(this, details, "Beğeni")
        opinion = Spinner(this).apply {
            contentDescription = "Beğeni"
            adapter = ArrayAdapter(this@EditActivity, android.R.layout.simple_spinner_dropdown_item, Opinion.entries.map { it.label })
            setSelection(entry.opinion.ordinal); minimumHeight = with(Ui) { dp(48) }
        }
        details.addView(opinion)
        field("likedReason", "Neden sevdim?", entry.likedReason, 2000, true, details)
        field("dislikedReason", "Neden sevmedim?", entry.dislikedReason, 2000, true, details)
        field("dropReason", "Bırakma / ara verme nedeni", entry.dropReason, 2000, true, details)
        field("notes", "Kendi notlarım", entry.notes, 4000, true, details)
        Ui.gap(this, column, 20)
        val save = Ui.button(this, if (proposal == null) "Kaydet" else "Değişiklikleri onayla", true) { save() }
        actionButtons.add(save); column.addView(save)
        if (proposal != null) {
            Ui.gap(this, column)
            val reject = Ui.button(this, "Öneriyi reddet") {
                val token = proposalToken ?: return@button
                io({
                    if (token.startsWith("mcp:")) store.recordBridgeRejection(token.removePrefix("mcp:"))
                    else (application as LibraryApplication).chats.removeProposal(token)
                }) {
                    (application as LibraryApplication).proposals.remove(token); finish()
                }
            }
            actionButtons.add(reject); column.addView(reject)
        }
        if (saveState.coverResult != null && coverCurrent()) coverInfo.text = if (saveState.coverTitle.isNotEmpty())
            "Türkçe kaynaktaki seri kapağı bulundu. Kontrol edip Kaydet'e dokun.\nKaynak: ${saveState.coverResult!!.seriesUrl}"
            else "Kaynağın kapağı hazır; seriyle birlikte kaydedilecek. Görselin dili kaynak siteye bağlıdır."
        if (saveState.aiCoverPending != null) { lockCover(); coverHandler.post(pollCover) }
        if (intent.getBooleanExtra("findTurkishCover", false) && !saveState.aiCoverRequested) {
            saveState.aiCoverRequested = true; aiCover.post { aiCover.performClick() }
        }
        Ui.gap(this, column, 24)
    }

    private fun draft(): Series = baseline.copy(title = fields.getValue("title").text.toString(),
        url = fields.getValue("url").text.toString(), lastChapter = fields.getValue("lastChapter").text.toString(),
        status = ReadingStatus.entries[status.selectedItemPosition], opinion = Opinion.entries[opinion.selectedItemPosition],
        likedReason = fields.getValue("likedReason").text.toString(), dislikedReason = fields.getValue("dislikedReason").text.toString(),
        dropReason = fields.getValue("dropReason").text.toString(), notes = fields.getValue("notes").text.toString(), categories = selectedCategories.toList(),
        alternativeTitles = fields.getValue("alternativeTitles").text.toString().lines().filter { it.isNotBlank() },
        coverUrl = saveState.coverResult?.takeIf { coverCurrent() }?.imageUrl ?: baseline.coverUrl)

    private fun save() {
        if (busy || saveState.aiCoverPending != null) return
        val entry = try { draft().copy(updatedAt = maxOf(System.currentTimeMillis(), baseline.createdAt)).normalized() }
        catch (problem: IllegalArgumentException) { error.text = problem.message; error.visibility = View.VISIBLE; notice(problem.message ?: "Giriş geçersiz."); return }
        error.visibility = View.GONE
        if (proposal != null) {
            val reviewed = AiProposal(proposal!!.before, entry)
            val content = ScrollView(this).apply { addView(Ui.text(this@EditActivity, AiProtocol.preview(reviewed), 15f).apply { setPadding(24, 24, 24, 24) }) }
            AlertDialog.Builder(this).setTitle("Eski / yeni değerleri onayla").setView(content)
                .setNegativeButton("Düzenlemeye devam et", null).setPositiveButton("Onayla ve kaydet") { _, _ -> submit(entry) }.show()
        } else submit(entry)
    }

    private fun submit(entry: Series) {
        if (busy || saveState.pending != null) return
        coverSequence++
        saveState.editRevision++
        val coverToSave = saveState.coverResult?.takeIf { coverCurrent() }
        val pending = (application as LibraryApplication).executor.submit<Series> {
            val saved = if (proposal == null) store.save(entry) else store.applyProposal(AiProposal(proposal!!.before, entry), proposalToken?.takeIf { it.startsWith("mcp:") }?.removePrefix("mcp:"))
            coverToSave?.takeIf { saved.coverUrl == it.imageUrl }?.let { cover ->
                runCatching { (application as LibraryApplication).covers.save(saved.id, cover.bytes) }.onFailure { saveState.archiveCleanupFailed = true }
            }
            proposalToken?.takeUnless { it.startsWith("mcp:") }?.let { token ->
                runCatching { (application as LibraryApplication).chats.removeProposal(token) }
                    .onFailure { saveState.archiveCleanupFailed = true }
            }
            saved
        }
        saveState.pending = pending; awaitSave(pending)
    }

    private fun awaitSave(pending: Future<Series>) {
        // Retain the same save across rotation; the serial executor runs it before this wait.
        io({ runCatching { pending.get() }.recoverCatching { throw ((it as? ExecutionException)?.cause ?: it) } }) { result ->
            saveState.pending = null
            result.fold({ saved ->
                proposalToken?.let { (application as LibraryApplication).proposals.remove(it) }
                if (saveState.archiveCleanupFailed) notice("Seri kaydedildi; kapak veya arşiv işlemi tamamlanamadı. Yeniden deneyebilirsin.")
                if (!intent.hasExtra("seriesId")) startActivity(Intent(this, DetailActivity::class.java).putExtra("seriesId", saved.id))
                finish()
            }, { problem ->
                error.text = if (problem is IllegalArgumentException) problem.message else "Kayıt tamamlanamadı; tekrar deneyebilirsin."
                error.visibility = View.VISIBLE
                notice(error.text.toString())
            })
        }
    }

    override fun onBusyChanged(active: Boolean) {
        val locked = active || saveState.aiCoverPending != null
        fields.values.forEach { it.isEnabled = !locked }
        if (::status.isInitialized) status.isEnabled = !locked
        if (::opinion.isInitialized) opinion.isEnabled = !locked
        categoryChecks.forEach { it.isEnabled = !locked }
        if (locked) actionButtons.forEach { it.isEnabled = false }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        proposal?.let { original ->
            outState.putString("proposal_before", original.before?.let { BackupCodec.encodeLocal(listOf(it)) })
            outState.putString("proposal_after", BackupCodec.encodeLocal(listOf(original.after)))
        }
        if (fields.isNotEmpty()) { toBundle(outState, "base_", baseline); toBundle(outState, "draft_", draft()) }
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { coverHandler.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun toBundle(bundle: Bundle, prefix: String, entry: Series) {
        bundle.putString(prefix + "id", entry.id); bundle.putString(prefix + "title", entry.title)
        bundle.putString(prefix + "url", entry.url); bundle.putString(prefix + "lastChapter", entry.lastChapter)
        bundle.putString(prefix + "status", entry.status.name); bundle.putString(prefix + "opinion", entry.opinion.name)
        bundle.putString(prefix + "likedReason", entry.likedReason); bundle.putString(prefix + "dislikedReason", entry.dislikedReason)
        bundle.putString(prefix + "dropReason", entry.dropReason); bundle.putString(prefix + "notes", entry.notes)
        bundle.putLong(prefix + "createdAt", entry.createdAt); bundle.putLong(prefix + "updatedAt", entry.updatedAt)
        bundle.putStringArrayList(prefix + "categories", ArrayList(entry.categories))
        bundle.putString(prefix + "local", BackupCodec.localFields(entry).toString())
    }

    private fun fromBundle(bundle: Bundle, prefix: String) = Series(id = bundle.getString(prefix + "id")!!,
        title = bundle.getString(prefix + "title")!!, url = bundle.getString(prefix + "url")!!,
        lastChapter = bundle.getString(prefix + "lastChapter")!!, status = ReadingStatus.valueOf(bundle.getString(prefix + "status")!!),
        opinion = Opinion.valueOf(bundle.getString(prefix + "opinion")!!), likedReason = bundle.getString(prefix + "likedReason")!!,
        dislikedReason = bundle.getString(prefix + "dislikedReason")!!, dropReason = bundle.getString(prefix + "dropReason")!!,
        notes = bundle.getString(prefix + "notes")!!, createdAt = bundle.getLong(prefix + "createdAt"), updatedAt = bundle.getLong(prefix + "updatedAt"),
        categories = bundle.getStringArrayList(prefix + "categories") ?: emptyList()).let {
            BackupCodec.withLocalFields(it, org.json.JSONObject(bundle.getString(prefix + "local") ?: "{}"))
        }
}
