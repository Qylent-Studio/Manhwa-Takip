package com.qylent.manhwatakip

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.AdapterView
import androidx.core.content.FileProvider
import java.io.File
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.time.LocalDate
import java.util.concurrent.Executor

class ChatGptActivity : LibraryActivity() {
    data class Source(val records: List<Series>, val categories: List<CategoryDefinition>, val history: List<ProgressChange>)
    class State : ViewModel() {
        var output: String? = null
        var pendingOutput: String? = null
        var files: Map<String, ByteArray>? = null
        var fields: Set<AiShareField> = emptySet()
        var selectedIds: Set<String>? = null
        var statusFilter = 0
        var mode = ChatGptMode.SERIES
        var pendingZip: ByteArray? = null
        private var outputSource: Source? = null
        var sourceCheckRequested = false
        fun acceptOutput(source: Source, files: Map<String, ByteArray>) {
            outputSource = source; this.files = files
            output = files.getValue("context.json").toString(Charsets.UTF_8)
        }
        fun clearOutput() {
            output = null; files = null; outputSource = null
            pendingOutput = null; pendingZip = null
            sourceCheckRequested = false
        }
        fun observeSource(source: Source): Boolean {
            if (outputSource == null || outputSource == source) return false
            clearOutput(); message = "Kütüphane değişti; önizlemeyi yeniden oluştur."
            return true
        }
        fun importApplied(count: Int) {
            clearOutput(); message = "$count seri değişikliği uygulandı."
        }
        var draft = ""
        var preview: ChatGptPreview? = null
        var searchNewCovers = false
        var recommendationCovers = RecommendationCovers()
        var busy = false
        var message = "Çıktıyı ChatGPT'ye gönder; dönen seri JSON'unu aşağıdan içe aktar."
        val revision = MutableLiveData(0)
        fun <T> run(executor: Executor, work: () -> T, completed: (T) -> Unit) {
            if (busy) return
            busy = true; revision.value = revision.value!! + 1
            executor.execute {
                val result = runCatching(work)
                Handler(Looper.getMainLooper()).post {
                    busy = false
                    result.fold(completed) { error ->
                        message = if (error is IllegalArgumentException) error.message ?: "JSON geçersiz." else "İşlem tamamlanamadı; kayıtların korunuyor. Tekrar deneyebilirsin."
                    }
                    revision.value = revision.value!! + 1
                }
            }
        }
    }
    private val state by lazy { ViewModelProvider(this)[State::class.java] }
    private val executor get() = (application as LibraryApplication).executor
    private lateinit var input: EditText
    private lateinit var output: EditText
    private lateinit var status: TextView
    private lateinit var selectionSummary: TextView
    private var previewDialog: AlertDialog? = null
    private var bindingInput = false
    private val outputButtons = mutableListOf<android.widget.Button>()
    private val optionViews = mutableListOf<View>()
    private val zipFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val bytes = state.pendingZip; state.pendingZip = null
        if (uri != null && bytes != null) state.run(executor, {
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Dosya açılamadı")
        }) { state.message = "ZIP dosyası kaydedildi." }
    }
    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val snapshot = state.pendingOutput
        state.pendingOutput = null
        if (uri != null && snapshot != null) state.run(executor, {
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(snapshot.toByteArray(Charsets.UTF_8)) }
                ?: error("Dosya açılamadı")
        }) { state.message = "ChatGPT JSON dosyası kaydedildi." }
        else if (uri != null) { state.message = "Çıktıyı yeniden oluşturup dosyaya kaydet."; render() }
    }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) state.run(executor, {
            val text = contentResolver.openInputStream(uri)?.use(ChatGptTransfer::read) ?: error("Dosya açılamadı")
            text to ChatGptTransfer.preview(text, store.all())
        }) { (text, preview) -> state.draft = text; state.preview = preview; state.searchNewCovers = false; state.recommendationCovers = RecommendationCovers(); state.message = "Dosya doğrulandı; değişiklikleri incele." }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shell("AI Paylaşımı")
        if (state.draft.isEmpty()) state.draft = savedInstanceState?.getString("draft") ?: ""
        val column = Ui.scroll(this, page)
        column.addView(Ui.text(this, "Dışa aktar", 20f, true))
        selectionSummary = Ui.text(this, "", 14f).also { column.addView(it) }
        fun action(label: String, block: () -> Unit) = Ui.button(this, label, action = block).also { column.addView(it); actionButtons.add(it) }
        column.addView(Ui.text(this, "Varsayılan yalnızca seri adı. Diğer alanları açıkça seç. Sohbet ve anahtarlar hiçbir çıktıya eklenmez.", 14f))
        column.addView(CheckBox(this).apply { text = "Seri adı (zorunlu)"; isChecked = true; isEnabled = false })
        val labels = listOf("Durum", "Son bölüm", "Beğeni", "Kategoriler", "Notlar", "Nedenler", "Bağlantı", "Geçmiş")
        AiShareField.entries.forEachIndexed { index, field ->
            column.addView(CheckBox(this).apply {
                text = labels[index]; isSaveEnabled = false; isChecked = field in state.fields
                setOnCheckedChangeListener { _, checked ->
                    state.fields = if (checked) state.fields + field else state.fields - field
                    invalidateOutput()
                }; optionViews.add(this)
            })
        }
        Ui.label(this, column, "Paylaşılacak okuma durumu")
        column.addView(Spinner(this).apply {
            isSaveEnabled = false
            adapter = ArrayAdapter(this@ChatGptActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Hepsi") + ReadingStatus.entries.map { it.label })
            setSelection(state.statusFilter)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (state.statusFilter != position) { state.statusFilter = position; invalidateOutput() }
                }
            }; optionViews.add(this)
        })
        action("Seri seç") {
            val rows = store.all()
            val ids = (state.selectedIds ?: rows.map { it.id }.toSet()).toMutableSet()
            AlertDialog.Builder(this).setTitle("Paylaşılacak seriler")
                .setMultiChoiceItems(rows.map { it.title }.toTypedArray(), rows.map { it.id in ids }.toBooleanArray()) { _, index, checked ->
                    if (checked) ids.add(rows[index].id) else ids.remove(rows[index].id)
                }.setNeutralButton("Hepsi") { _, _ -> state.selectedIds = null; invalidateOutput() }
                .setNegativeButton("İptal", null).setPositiveButton("Seçimi uygula") { _, _ -> state.selectedIds = ids; invalidateOutput() }.show()
        }
        action("Önizlemeyi oluştur") { createOutput(state.mode) }
        action("Serileri dışa aktar") { createOutput(ChatGptMode.SERIES) }
        action("Yeni öneri için dışa aktar") { createOutput(ChatGptMode.RECOMMEND) }
        action("Özet için dışa aktar") { createOutput(ChatGptMode.SUMMARIZE) }
        column.addView(Ui.text(this, "Liste / yeni öneri / eski özet amacı yukarıdan seçilir. Özet için bölüm paylaşılmadıysa spoiler içermeyen tanıtım istenir.", 14f))
        output = Ui.input(this, "ChatGPT JSON çıktısı", 12_000, multiline = true).apply {
            tag = "aiOutput"
            isSaveEnabled = false; isFocusable = false; isCursorVisible = false
            keyListener = null
            setTextIsSelectable(true); minLines = 3; maxLines = 8
        }
        column.addView(output)
        outputButtons.add(action("Çıktıyı kopyala") {
            val text = state.output ?: return@action
            if (text.length > 100_000) { state.message = "Büyük çıktıyı dosyaya kaydet."; render(); return@action }
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ChatGPT JSON", text))
            state.message = "JSON kopyalandı; ChatGPT'ye yapıştırabilirsin."; render()
        })
        outputButtons.add(action("Çıktıyı paylaş") {
            val text = state.output ?: return@action
            if (text.length > 100_000) { state.message = "Büyük çıktıyı dosyaya kaydet."; render(); return@action }
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "ChatGPT JSON çıktısını paylaş"))
        })
        outputButtons.add(action("Çıktıyı dosyaya kaydet") {
            state.pendingOutput = state.output
            exportFile.launch("manhwa-chatgpt-${LocalDate.now()}.json")
        })
        outputButtons.add(action("ZIP dosyasına kaydet") {
            state.pendingZip = PortableFiles.zip(state.files ?: return@action)
            zipFile.launch("manhwa-ai-${LocalDate.now()}.zip")
        })
        outputButtons.add(action("JSON paylaş") { sharePackage(false) })
        outputButtons.add(action("ZIP paylaş") { sharePackage(true) })
        status = Ui.text(this, state.message, 14f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        column.addView(status)
        Ui.gap(this, column, 24)
        column.addView(Ui.text(this, "İçe aktar", 20f, true))
        column.addView(Ui.text(this, "Seri adı, son bölüm ve okuma bağlantısı içeren JSON'u yapıştır. Önizlemede yeni serileri ve mevcut seri değişikliklerini görebilirsin; notların ve diğer bilgilerin korunur.", 14f))
        action("Panodan içe aktar") {
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            val text = if (clip?.itemCount == 1) clip.getItemAt(0).text?.toString() else null
            if (text == null) { state.message = "Panoda tek bir JSON metni olmalı."; render() } else previewText(text)
        }
        input = Ui.input(this, "ChatGPT JSON'unu yapıştır", ChatGptTransfer.MAX_BYTES, multiline = true).apply {
            isSaveEnabled = false; minLines = 4; maxLines = 12; setText(state.draft)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!bindingInput) { state.draft = s.toString(); state.preview = null }
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        column.addView(input)
        column.addView(Ui.text(this, "Örnek: {\"series\":[{\"title\":\"Seri adı\",\"lastChapter\":\"12\",\"url\":\"https://…\"}]}", 13f))
        action("Yapıştırılan JSON'u önizle") { previewText(input.text.toString()) }
        action("Dosya seç") { importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        state.revision.observe(this) { render(); checkResumedSource() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Large JSON stays in memory across rotation; do not exceed Android's saved-state parcel limit.
        if (state.draft.length <= 30_000) outState.putString("draft", state.draft)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { previewDialog?.dismiss(); super.onDestroy() }
    private fun source() = Source(store.all(), store.categoryDefinitions(), store.history())
    override fun onResume() {
        super.onResume()
        // A chooser/SAF return is not itself a revision. Compare the actual export inputs.
        state.sourceCheckRequested = true
        checkResumedSource()
    }
    private fun checkResumedSource() {
        // If an operation spans resume, its completion revision performs the deferred check.
        if (!state.sourceCheckRequested || state.busy) return
        state.sourceCheckRequested = false
        if (state.output != null) state.run(executor, { source() }) { state.observeSource(it) }
    }
    private fun createOutput(mode: ChatGptMode) {
        state.mode = mode
        val options = AiShareOptions(state.fields, state.selectedIds)
        val filter = state.statusFilter
        state.run(executor, {
            val source = source()
            source to AiPackage.files(source.records.filter { filter == 0 || it.status == ReadingStatus.entries[filter - 1] }, source.categories, source.history, mode, options)
        }) { (source, files) ->
            state.acceptOutput(source, files)
            val text = state.output!!
            state.message = if (text.length > 12_000) "Önizlemede ilk 12.000 karakter gösteriliyor. Kopyalama, paylaşma ve dosya çıktısı tam JSON'u kullanır." else "JSON hazır; kopyalayabilir, paylaşabilir veya dosyaya kaydedebilirsin."
        }
    }
    private fun invalidateOutput() {
        state.clearOutput()
        state.message = "Seçim değişti; önizlemeyi yeniden oluştur. Amaç: ${state.mode.name}."
        render()
    }
    private fun sharePackage(zipped: Boolean) {
        val files = state.files ?: return
        val bytes = if (zipped) PortableFiles.zip(files) else files.getValue("context.json")
        state.run(executor, {
            val directory = File(cacheDir, "shared-exports").apply { mkdirs() }
            directory.listFiles()?.filter { it.isFile && it.lastModified() < System.currentTimeMillis() - 86_400_000 }?.forEach { it.delete() }
            File(directory, "${java.util.UUID.randomUUID()}.${if (zipped) "zip" else "json"}").apply { writeBytes(bytes) }
        }) { file ->
            val uri = FileProvider.getUriForFile(this, "$packageName.exports", file)
            val send = Intent(Intent.ACTION_SEND).setType(if (zipped) "application/zip" else "application/json")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri("AI paketi", uri)
            startActivity(Intent.createChooser(send, "Dosyayı paylaş"))
        }
    }
    private fun previewText(text: String) {
        state.run(executor, { ChatGptTransfer.preview(text, store.all()) }) { preview ->
            state.draft = text; state.preview = preview; state.searchNewCovers = false; state.recommendationCovers = RecommendationCovers(); state.message = "JSON doğrulandı; değişiklikleri incele."
        }
    }
    private fun render() {
        if (!::input.isInitialized) return
        findViewById<ProgressBar>(R.id.loading).visibility = if (state.busy) View.VISIBLE else View.GONE
        actionButtons.forEach { it.isEnabled = !state.busy }
        optionViews.forEach { it.isEnabled = !state.busy }
        outputButtons.forEach { it.isEnabled = !state.busy && state.output != null }
        input.isEnabled = !state.busy
        status.text = if (state.busy) "İşlem sürüyor…" else state.message
        selectionSummary.text = "Amaç: ${listOf("Listeyi paylaş", "Yeni öneri", "Özet")[state.mode.ordinal]} · Seri: ${state.selectedIds?.size ?: "Hepsi"} · Durum: ${if (state.statusFilter == 0) "Hepsi" else ReadingStatus.entries[state.statusFilter - 1].label}"
        val display = state.output?.take(12_000).orEmpty()
        if (output.text.toString() != display) output.setText(display)
        if (input.text.toString() != state.draft) {
            bindingInput = true; input.setText(state.draft); bindingInput = false
        }
        if (!state.busy && previewDialog?.isShowing != true) state.preview?.let(::confirmPreview)
    }
    private fun confirmPreview(preview: ChatGptPreview) {
        val additions = preview.changes.count { it.before == null }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 8) }
        column.addView(Ui.text(this, "$additions yeni seri, ${preview.changes.size - additions} güncelleme, ${preview.unchanged} aynı kayıt.\nHer satıra dokunarak değişiklikleri incele. Onay vermeden kayıt yapılmaz.", 14f))
        val list = ListView(this).apply {
            adapter = ArrayAdapter(this@ChatGptActivity, android.R.layout.simple_list_item_1,
                preview.changes.map { "${if (it.before == null) "Yeni" else "Güncellenecek"}: ${it.after.title}" })
            setOnItemClickListener { _, _, position, _ ->
                val proposal = preview.changes[position]
                AlertDialog.Builder(this@ChatGptActivity).setTitle(proposal.after.title).setMessage(AiProtocol.preview(proposal)).setPositiveButton("Kapat", null).show()
            }
        }
        column.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, with(Ui) { dp(240) }))
        if (additions > 0) {
            column.addView(CheckBox(this).apply {
                text = "Yeni serilerin eksik kapaklarını ara ve otomatik kaydet"
                isChecked = state.searchNewCovers
                setOnCheckedChangeListener { _, checked -> state.searchNewCovers = checked }
            })
            column.addView(Ui.text(this, "Yalnızca onaylanan yeni seri adları ve bağlantıları Türkçe kataloglarda aranır. Mevcut kapaklar korunur; hatada seri kalır. İlerleme ve Durdur: Kütüphane. Süren kuyruk varsa bu aramalar sıranın sonuna eklenir. AI hizmetine gönderilmez.", 12f))
        }
        previewDialog = AlertDialog.Builder(this).setTitle("ChatGPT içe aktarma önizlemesi").setView(column)
            .setNegativeButton("İptal") { _, _ -> state.preview = null }
            .setPositiveButton(if (preview.changes.isEmpty()) "Kapat" else "Onayla ve içe aktar") { _, _ ->
                state.preview = null
                val searchCovers = state.searchNewCovers
                val coverRequest = state.recommendationCovers
                if (preview.changes.isNotEmpty()) state.run(executor, { store.applyTransfer(preview.changes) }) { result ->
                    state.importApplied(result.size)
                    if (searchCovers) state.message += if (coverRequest.start(application as LibraryApplication, preview, result, true))
                        " Yeni serilerin kapak araması sıraya alındı. İlerleme/Durdur: Kütüphane."
                    else " Kapak işi eklenmedi (yeni seri yok veya bu onay zaten sıraya alındı)."
                }
            }.setOnCancelListener { state.preview = null }.show()
    }
}
