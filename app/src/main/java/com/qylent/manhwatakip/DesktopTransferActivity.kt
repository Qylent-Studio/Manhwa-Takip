package com.qylent.manhwatakip

import android.os.Bundle
import android.app.AlertDialog
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Short USB exchange, authenticated independently from MCP proposal approval. */
class DesktopTransferActivity : LibraryActivity() {
    class Approval(val covers: List<DesktopCover>) { val done = CountDownLatch(1); var accepted = false }
    class ImportApproval(val preview: ChatGptPreview) {
        val done = CountDownLatch(1); var accepted = false; var searchCovers = false
        val covers = RecommendationCovers()
    }
    class State : ViewModel() { var started = false; val finished = MutableLiveData<String>(); val approval = MutableLiveData<Approval?>(); val importApproval = MutableLiveData<ImportApproval?>() }
    private var importDialog: AlertDialog? = null
    private val state by lazy { ViewModelProvider(this)[State::class.java] }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); shell("Bilgisayar aktarımı")
        page.addView(Ui.text(this, "USB üzerinden aktarım sürüyor. İşlem bitince bu ekran kapanır.", 16f))
        state.finished.observe(this) { message -> notice(message); finish() }
        state.importApproval.observe(this) { approval ->
            importDialog?.dismiss()
            if (approval == null || approval.done.count == 0L) return@observe
            val preview = approval.preview
            val additions = preview.changes.count { it.before == null }
            val column = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(24, 16, 24, 8) }
            column.addView(Ui.text(this, "$additions yeni seri, ${preview.changes.size - additions} güncelleme, ${preview.unchanged} aynı kayıt. Satıra dokunarak tüm alan değişikliklerini incele. Onaysız kayıt yapılmaz.", 14f))
            column.addView(android.widget.ListView(this).apply {
                adapter = android.widget.ArrayAdapter(this@DesktopTransferActivity, android.R.layout.simple_list_item_1,
                    preview.changes.map { "${if (it.before == null) "Yeni" else "Güncellenecek"}: ${it.after.title}" })
                setOnItemClickListener { _, _, index, _ ->
                    AlertDialog.Builder(this@DesktopTransferActivity).setTitle(preview.changes[index].after.title)
                        .setMessage(AiProtocol.preview(preview.changes[index])).setPositiveButton("Kapat", null).show()
                }
            }, android.widget.LinearLayout.LayoutParams(-1, with(Ui) { dp(200) }))
            if (additions > 0) {
                column.addView(android.widget.CheckBox(this).apply {
                    text = "Yeni serilerin eksik kapaklarını ara ve otomatik kaydet"; isChecked = approval.searchCovers
                    setOnCheckedChangeListener { _, checked -> approval.searchCovers = checked }
                })
                column.addView(Ui.text(this, "Yalnızca onaylanan yeni adlar ve bağlantılar Türkçe kataloglarda aranır; AI hizmetine gönderilmez. Mevcut kapaklar korunur. Hatalarda seri kalır. İlerleme/Durdur: Kütüphane. Süren kuyruk varsa aramalar sıranın sonuna eklenir.", 12f))
            }
            importDialog = AlertDialog.Builder(this).setTitle("AI içe aktarma önizlemesi").setView(column)
                .setNegativeButton("Vazgeç") { _, _ -> approval.done.countDown() }
                .setPositiveButton("Onayla ve içe aktar") { _, _ -> approval.accepted = true; approval.done.countDown() }
                .setOnCancelListener { approval.done.countDown() }.show()
        }
        state.approval.observe(this) { approval ->
            if (approval == null || approval.done.count == 0L) return@observe
            val names = approval.covers.take(20).joinToString("\n") { it.proposal.after.title + if (it.replaceExisting) " — mevcut kapak değiştirilecek" else " — eksik kapak eklenecek" }
            AlertDialog.Builder(this).setTitle("${approval.covers.size} kapak uygulansın mı?")
                .setMessage("Bilgisayarda önizlenip seçilen kapaklar uygulanacak. Değiştirme olarak işaretlenen serilerin mevcut kapağı değişir. Okuma bilgileri korunur.\n\n$names${if (approval.covers.size > 20) "\n…" else ""}")
                .setNegativeButton("Vazgeç") { _, _ -> approval.done.countDown() }
                .setPositiveButton("Kapakları onayla") { _, _ -> approval.accepted = true; approval.done.countDown() }
                .setOnCancelListener { approval.done.countDown() }.show()
        }
        if (state.started) return
        state.started = true
        val operation = intent.getStringExtra("operation").orEmpty()
        if (!operation.matches(Regex("[a-f0-9-]{36}"))) { state.finished.value = "Aktarımı bilgisayar uygulamasından başlat."; return }
        val app = application as LibraryApplication
        app.executor.execute {
            val outcome = runCatching {
                val key = BridgePreferences(this).key()
                require(key.isNotEmpty()) { "Önce mevcut bilgisayar eşleştirmesini telefona kaydet." }
                val envelope = JSONObject(request("/job/$operation", key))
                val payload = envelope.getString("payload")
                val signature = mac(key, "response\n$payload")
                require(MessageDigest.isEqual(signature.toByteArray(), envelope.getString("signature").toByteArray())) { "Bilgisayar kimliği doğrulanamadı." }
                ChatGptTransfer.strictJson(payload)
                val job = JSONObject(payload)
                require(job.getString("id") == operation) { "Aktarım oturumu uyuşmuyor." }
                val full = FullBackupStore(app.store, app.covers, app.chats, AppPreferences(this), AiSecretStore(this), BridgePreferences(this))
                val result = runCatching {
                    var applied = 0
                    var coverScheduling: String? = null
                    when (job.getString("action")) {
                        "read" -> require(job.keys().asSequence().toSet() == setOf("id", "action"))
                        "edit" -> {
                            require(job.keys().asSequence().toSet() == setOf("id", "action", "changes"))
                            val proposals = DesktopTransfer.preview(JSONObject().put("changes", job.getJSONArray("changes")).toString(), app.store.all())
                            if (proposals.isNotEmpty()) applied = app.store.applyTransfer(proposals).size
                        }
                        "ai_import" -> {
                            require(job.keys().asSequence().toSet() == setOf("id", "action", "text"))
                            val preview = ChatGptTransfer.preview(job.getString("text"), app.store.all())
                            val approval = ImportApproval(preview)
                            state.importApproval.postValue(approval)
                            val accepted = approval.done.await(60, TimeUnit.SECONDS) && approval.accepted
                            state.importApproval.postValue(null)
                            require(accepted) { "AI önerisi onaylanmadı veya onay süresi doldu; hiçbir kayıt değiştirilmedi." }
                            if (preview.changes.isNotEmpty()) {
                                val saved = app.store.applyTransfer(preview.changes)
                                applied = saved.size
                                if (approval.searchCovers) coverScheduling = if (approval.covers.start(app, preview, saved, true))
                                    "Yeni serilerin kapak araması sıraya alındı. İlerleme/Durdur: Kütüphane."
                                else "Kapak işi eklenmedi (yeni seri yok veya bu onay zaten sıraya alındı)."
                            }
                        }
                        "restore" -> {
                            require(job.keys().asSequence().toSet() == setOf("id", "action", "backup"))
                            applied = full.importMissing(full.previewPortable(job.getJSONObject("backup").toString().toByteArray())).library.added
                        }
                        "covers", "manual_covers" -> {
                            require(job.keys().asSequence().toSet() == setOf("id", "action", "changes"))
                            val manual = job.getString("action") == "manual_covers"
                            val covers = DesktopCovers.preview(job.getJSONArray("changes"), app.store.all(), manual)
                            require(full.exportPortable().size.toLong() + covers.sumOf { it.jpeg.length.toLong() + 2500 } <= FullBackupCodec.MAX_BYTES) { "Kapaklarla birlikte tam yedek 32 MB sınırını aşıyor; daha az kapak seç." }
                            if (!manual) {
                                val approval = Approval(covers); state.approval.postValue(approval)
                                val accepted = approval.done.await(60, TimeUnit.SECONDS) && approval.accepted
                                state.approval.postValue(null)
                                require(accepted) { "Kapaklar onaylanmadı veya onay süresi doldu. Taslaklar bilgisayarda korunuyor." }
                            }
                            applied = DesktopCovers.apply(covers, app.store, app.covers)
                        }
                        else -> throw IllegalArgumentException("Aktarım işlemi desteklenmiyor.")
                    }
                    JSONObject().put("ok", true).put("applied", applied).put("snapshot", JSONObject(PortableFiles.text(full.exportPortable())))
                        .also { if (coverScheduling != null) it.put("warning", coverScheduling) }
                }.getOrElse { error ->
                    if (error is PartialCoverTransfer) JSONObject().put("ok", true).put("applied", error.applied)
                        .put("warning", error.message).put("snapshot", JSONObject(PortableFiles.text(full.exportPortable())))
                    else JSONObject().put("ok", false).put("error", if (error is IllegalArgumentException) error.message ?: "Veri geçersiz." else "İşlem tamamlanamadı. Güncel telefon verilerini yeniden oku.")
                }
                request("/result/$operation", key, result.toString())
                if (result.getBoolean("ok")) "Bilgisayar aktarımı tamamlandı." else result.getString("error")
            }
            state.finished.postValue(outcome.getOrElse { "Bilgisayar aktarımı doğrulanamadı. USB bağlantısını ve eşleştirmeyi kontrol et." })
        }
    }
    override fun onDestroy() { importDialog?.dismiss(); super.onDestroy() }
    private fun request(path: String, key: String, body: String? = null): String {
        val connection = URL("http://127.0.0.1:8767$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000; connection.readTimeout = 30_000; connection.instanceFollowRedirects = false
            val bytes = body?.toByteArray() ?: byteArrayOf()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            val method = if (body == null) "GET" else "POST"
            connection.setRequestProperty("Authorization", "Manhwa-HMAC ${mac(key, "request\n$method\n$path\n$digest")}")
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8"); connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            require(connection.responseCode == 200) { "Aktarım oturumu reddedildi." }
            return connection.inputStream.use { PortableFiles.text(PortableFiles.read(it, FullBackupCodec.MAX_BYTES * 2 + 8192)) }
        } finally { connection.disconnect() }
    }
    private fun mac(key: String, text: String): String = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key.toByteArray(), "HmacSHA256")); doFinal(text.toByteArray())
    }.joinToString("") { "%02x".format(it.toInt() and 255) }
}
