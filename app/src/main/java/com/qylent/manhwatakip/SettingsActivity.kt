package com.qylent.manhwatakip

import android.content.Intent
import android.os.Bundle
import android.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import java.io.IOException
import java.time.LocalDate

class SettingsActivity : LibraryActivity() {
    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) io({
            val bytes = BackupCodec.encodeLibrary(store.all(), store.categoryDefinitions()).toByteArray(Charsets.UTF_8)
            val output = contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Dosya açılamadı.")
            output.use { it.write(bytes); it.flush() }
        }) { notice("Yedek dosyası kaydedildi.") }
    }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) io({
            val input = contentResolver.openInputStream(uri) ?: throw IOException("Dosya açılamadı.")
            val incoming = input.use(BackupCodec::readLibrary)
            val keys = store.categories().mapTo(mutableSetOf(), CategoryNames::key)
            Triple(incoming, store.previewImport(incoming.records), incoming.categories.count { CategoryNames.key(it.name) !in keys })
        }) { (incoming, preview, newCategories) ->
            AlertDialog.Builder(this).setTitle("Yedeği geri yükle")
                .setMessage("${preview.added} yeni kayıt eklenecek. ${preview.skipped} mevcut kayıt atlanacak.\n$newCategories yeni kategori eklenecek.\n\nMevcut kayıtların, notların ve kategori ayarların değişmeyecek.")
                .setNegativeButton("İptal", null)
                .setPositiveButton("Geri yükle") { _, _ ->
                    io({ store.importMissing(incoming.records, incoming.categories) }) { result ->
                        notice("${result.added} kayıt eklendi, ${result.skipped} kayıt atlandı.")
                    }
                }.show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shell("Ayarlar")
        val column = Ui.scroll(this, page)
        Ui.label(this, column, "Uygulama")
        column.addView(Ui.button(this, "Uygulama ayarları") { startActivity(Intent(this, AppSettingsActivity::class.java)) })
        column.addView(Ui.button(this, "Kategoriler") { startActivity(Intent(this, CategoriesActivity::class.java)) })
        column.addView(Ui.button(this, "İstatistikler") { startActivity(Intent(this, StatsActivity::class.java)) })
        column.addView(Ui.button(this, "Yedekler") { if (!busy) showBackups() }.also { actionButtons.add(it) })
        column.addView(Ui.button(this, "Şifreli tam yedek") { startActivity(Intent(this, BackupsActivity::class.java)) })
        column.addView(Ui.button(this, "Parolasız JSON / ZIP yedek") { startActivity(Intent(this, PortableExportActivity::class.java).putExtra("backup", true)) })
        Ui.label(this, column, "Bilgisayar uygulaması")
        column.addView(Ui.text(this, "Bilgisayarda Manhwa Takip açıkken USB kablosunu bağla. Veriler otomatik alınır. Elle düzenleyip Telefona aktar seçince ek telefon onayı gerekmez; AI önerileri onay beklemeye devam eder.", 14f))
        Ui.label(this, column, "Uygulama içi AI")
        column.addView(Ui.text(this, "Sağlayıcı, model ve telefonda şifrelenen API anahtarı.", 14f))
        column.addView(Ui.button(this, "AI ayarları") { startActivity(Intent(this, AiSettingsActivity::class.java)) })
        Ui.label(this, column, "ChatGPT bağlantısı")
        column.addView(Ui.text(this, "USB eşleştirme, paylaşımı açma/kapatma ve telefonda onaylanacak MCP önerileri.", 14f))
        column.addView(Ui.button(this, "ChatGPT / MCP") { startActivity(Intent(this, BridgeActivity::class.java)) })
    }

    private fun showBackups() {
        AlertDialog.Builder(this).setTitle("Yedekler")
            .setItems(arrayOf("Dosyaya yedekle", "Yedekten geri yükle", "Şifreli tam yedek (anahtarlar dahil)")) { _, which ->
                if (which == 0) AlertDialog.Builder(this).setTitle("Yedek dosyası")
                    .setMessage("Dosya seri adlarını, ilerlemeni, özel kategori seçimlerini ve kişisel notlarını içerir; şifrelenmez. Güvendiğin bir konuma kaydet.")
                    .setNegativeButton("İptal", null).setPositiveButton("Dosya seç") { _, _ -> exportFile.launch("manhwa-takip-${LocalDate.now()}.json") }.show()
                else if (which == 1) importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                else startActivity(Intent(this, BackupsActivity::class.java))
            }.show()
    }
}
