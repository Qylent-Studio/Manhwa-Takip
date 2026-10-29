package com.qylent.manhwatakip

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Future

class BridgeActivity : LibraryActivity() {
    private lateinit var key: EditText
    private lateinit var result: TextView
    private lateinit var cards: LinearLayout
    private val app get() = application as LibraryApplication
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            val pending = app.bridgePending ?: return
            if (!pending.isDone) { handler.postDelayed(this, 200); return }
            app.bridgePending = null
            result.text = runCatching { pending.get() }.getOrElse {
                val cause = (it as? java.util.concurrent.ExecutionException)?.cause ?: it
                if (cause is IllegalArgumentException) cause.message ?: "İşlem tamamlanamadı." else "Köprü işlemi tamamlanamadı. Telefon kayıtların korunuyor."
            }
            lock(false); reload()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        shell("ChatGPT / MCP bağlantısı")
        val column = Ui.scroll(this, page)
        column.addView(Ui.text(this, "Özel bilgisayar köprüsü USB/ADB ile çalışır. Bağlantıyı açtığında bütün seri, takip, özel kategori, beğeni, neden ve notların bilgisayarla ve bağlı YZ uygulamalarıyla paylaşılır. API anahtarın paylaşılmaz. Sürekli arka plan eşitlemesi yoktur. Erişim her eşitlemeden 15 dakika sonra sona erer.", 14f))
        Ui.label(this, column, "Bilgisayar eşleştirme anahtarı")
        key = Ui.input(this, "Yerel eşleştirme anahtarı", 128).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false; importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        column.addView(key)
        val save = Ui.button(this, "Eşleştirme anahtarını kaydet") {
            val entered = key.text.toString().trim()
            io({ BridgePreferences(this).saveKey(entered) }) {
                key.text.clear()
                result.text = "Anahtar kaydedildi; henüz doğrulanmadı. Bağlantıyı kontrol et ile doğrulayabilirsin. Bu işlem paylaşımı açmaz."
                notice("Anahtar telefonda şifrelenerek kaydedildi.")
            }
        }
        column.addView(save); actionButtons.add(save)
        val check = Ui.button(this, "Bağlantıyı kontrol et") { launch(false, checkOnly = true) }
        column.addView(check); actionButtons.add(check)
        val sync = Ui.button(this, "Bağlantıyı aç / eşitle", true) {
            AlertDialog.Builder(this).setTitle("Kütüphaneyi paylaş ve eşitle")
                .setMessage("Kütüphanendeki tüm seri/takip/özel kategori/neden/not alanları USB ile özel bilgisayar köprüsüne ve bağlı YZ uygulamalarına açılacak. Bekleyen öneriler alınır; kayıtların yalnızca ayrıca telefonda onaylayınca değişir.")
                .setNegativeButton("İptal", null).setPositiveButton("Paylaş ve eşitle") { _, _ -> launch(false) }.show()
        }
        column.addView(sync); actionButtons.add(sync)
        val close = Ui.button(this, "Bağlantıyı kapat") { launch(true) }
        column.addView(close); actionButtons.add(close)
        val reset = Ui.button(this, "Yerel köprü ayarlarını sıfırla") {
            AlertDialog.Builder(this).setTitle("Yerel köprü ayarları sıfırlansın mı?")
                .setMessage("Telefondaki eşleştirme anahtarı ve bekleyen MCP önerileri kaldırılır. Serilerin ve notların korunur. Bilgisayarın mevcut anahtarını yeniden kaydetmen gerekir. Ulaşılamayan bilgisayar köprüsü bu işlemle kapanmaz; son eşitlemeden en fazla 15 dakika sonra erişimi sona erer. Hemen kapatmak için önce Bağlantıyı kapat kullan veya bilgisayarda köprüyü durdur.")
                .setNegativeButton("İptal", null).setPositiveButton("Yerel ayarları sıfırla") { _, _ ->
                    io({ BridgePreferences(this).clear() }) { removeCards(); key.text.clear(); result.text = "Yerel ayarlar sıfırlandı."; reload() }
                }.show()
        }
        column.addView(reset); actionButtons.add(reset)
        Ui.label(this, column, "Bağlantı durumu")
        result = Ui.text(this, savedInstanceState?.getString("connectionResult") ?: "Anahtarın kayıtlı olması bağlantının doğrulandığı anlamına gelmez. Bağlantıyı kontrol et kütüphaneyi paylaşmadan anahtarı doğrular. Kütüphaneyi paylaşmak için ayrıca Bağlantıyı aç / eşitle kullan.", 14f)
        column.addView(result)
        cards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; column.addView(cards)
        lock(app.bridgePending != null)
    }
    private fun launch(disconnect: Boolean, checkOnly: Boolean = false) {
        if (app.bridgePending != null || busy) return
        app.bridgePending = app.networkExecutor.submit<String> task@ {
            if (checkOnly) {
                val savedKey = app.executor.submit<String> { BridgePreferences(this).key() }.get()
                BridgeClient().check(savedKey)
                return@task "Anahtar doğrulandı; bilgisayar köprüsüne erişiliyor. Bu kontrol kütüphaneyi paylaşmaz ve paylaşım süresini uzatmaz. Kütüphaneyi paylaşmak için ayrıca Bağlantıyı aç / eşitle kullan."
            }
            val input = app.executor.submit<Triple<String, List<Series>, Map<String, String>>> {
                val preferences = BridgePreferences(this)
                Triple(preferences.key(), if (disconnect) emptyList() else app.store.all(), app.store.bridgeDecisions())
            }.get()
            if (disconnect) {
                BridgeClient().disconnect(input.first)
                app.executor.submit { BridgePreferences(this).clearPending(); app.store.ackBridgeDecisions(input.third) }.get()
                "Bilgisayar bağlantısı kapatıldı; bilgisayardaki paylaşılan snapshot ve öneriler temizlendi."
            } else {
                val response = BridgeClient().sync(input.first, input.second, input.third)
                app.executor.submit {
                    BridgePreferences(this).receive(response, app.store.bridgeDecisions().filterKeys { it !in input.third })
                    app.store.ackBridgeDecisions(input.third)
                }.get()
                "Eşitlendi: ${response.proposals.size} bekleyen öneri. Son paylaşım: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(response.lastSync))}. Kayıtların değişmedi."
            }
        }
        result.text = if (checkOnly) "Anahtar kontrol ediliyor; kütüphane gönderilmiyor…" else "USB köprüsüyle işlem sürüyor…"
        lock(true); handler.post(poll)
    }
    private fun removeCards() { app.proposals.keys.filter { it.startsWith("mcp:") }.forEach { app.proposals.remove(it) } }
    private fun reload() {
        io({ val decided = app.store.bridgeDecisions(); BridgePreferences(this).pending().filter { it.token !in decided } }) { pending ->
            removeCards(); cards.removeAllViews()
            pending.forEach { entry ->
                val token = "mcp:${entry.token}"; app.proposals[token] = entry.proposal
                Ui.gap(this, cards)
                cards.addView(Ui.text(this, "MCP önerisi: ${entry.proposal.after.title}\n\n${AiProtocol.preview(entry.proposal)}", 14f))
                cards.addView(Ui.button(this, "İncele: ${entry.proposal.after.title}") { startActivity(Intent(this, EditActivity::class.java).putExtra("proposalId", token)) })
            }
            lock(app.bridgePending != null)
        }
    }
    private fun lock(active: Boolean) { if (::key.isInitialized) key.isEnabled = !active && !busy; actionButtons.forEach { it.isEnabled = !active && !busy } }
    override fun onBusyChanged(active: Boolean) { lock(active || app.bridgePending != null) }
    override fun onResume() { super.onResume(); reload(); handler.post(poll) }
    override fun onPause() { handler.removeCallbacks(poll); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("connectionResult", result.text.toString())
        super.onSaveInstanceState(outState)
    }
}
