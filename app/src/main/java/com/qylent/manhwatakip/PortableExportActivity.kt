package com.qylent.manhwatakip

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.io.File
import java.time.LocalDate
import java.util.UUID

class PortableExportActivity : LibraryActivity() {
    class State : ViewModel() { var pending: ByteArray? = null }
    private val state by lazy { ViewModelProvider(this)[State::class.java] }
    private var backup = false
    private lateinit var statuses: Spinner; private lateinit var modes: Spinner
    private fun saved(uri: android.net.Uri?) {
        val bytes = state.pending; state.pending = null
        if (uri != null && bytes != null) io({ contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Dosya açılamadı") }) { notice("Dosya kaydedildi.") }
        else if (uri != null) notice("Çıktıyı yeniden oluştur.")
    }
    private val jsonFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json"), ::saved)
    private val zipFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), ::saved)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); backup = intent.getBooleanExtra("backup", false)
        // AI exports have one selection/output owner. Keep the old entry point as an alias.
        if (!backup) { startActivity(Intent(this, ChatGptActivity::class.java)); finish(); return }
        shell(if (backup) "Parolasız tam yedek" else "AI paylaşım paketi")
        val column = Ui.scroll(this, page)
        column.addView(Ui.text(this, if (backup) "Kütüphane, kategoriler, kapaklar, geçmiş, sohbetler ve genel ayarlar. API/eşleştirme anahtarları dahil değildir. Dosya parolasızdır."
            else "Okuma durumuna göre seri, bölüm, not, tercih ve okuma geçmişini paylaş. Sohbetler ve anahtarlar dahil değildir.", 15f))
        statuses = Spinner(this).apply { adapter = ArrayAdapter(this@PortableExportActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Hepsi") + ReadingStatus.entries.map { it.label }); setSelection(savedInstanceState?.getInt("status") ?: 0) }
        modes = Spinner(this).apply { adapter = ArrayAdapter(this@PortableExportActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Okuma profili", "Yeni öneri", "Okunan bölüme kadar özet")); setSelection(savedInstanceState?.getInt("mode") ?: 0) }
        if (!backup) { Ui.label(this, column, "Okuma durumu"); column.addView(statuses); Ui.label(this, column, "Amaç"); column.addView(modes) }
        fun action(title: String, zipped: Boolean, share: Boolean) { Ui.button(this, title) { output(zipped, share) }.also { column.addView(it); actionButtons.add(it) } }
        action("JSON dosyasına kaydet", false, false); action("ZIP dosyasına kaydet", true, false); action("JSON paylaş", false, true); action("ZIP paylaş", true, true)
    }
    override fun onSaveInstanceState(outState: Bundle) { if (::statuses.isInitialized) outState.putInt("status", statuses.selectedItemPosition); if (::modes.isInitialized) outState.putInt("mode", modes.selectedItemPosition); super.onSaveInstanceState(outState) }
    private fun output(zipped: Boolean, share: Boolean) {
        val status = statuses.selectedItemPosition; val mode = ChatGptMode.entries[modes.selectedItemPosition]; val app = application as LibraryApplication
        io({
            val files = if (backup) mapOf("backup.json" to FullBackupStore(app.store, app.covers, app.chats, AppPreferences(this), AiSecretStore(this), BridgePreferences(this)).exportPortable())
            else AiPackage.files(store.all().filter { status == 0 || it.status == ReadingStatus.entries[status - 1] }, store.categoryDefinitions(), store.history(), mode)
            if (zipped) PortableFiles.zip(files) else files.values.first()
        }) { bytes ->
            val name = "manhwa-${if (backup) "tam" else "ai"}-${LocalDate.now()}.${if (zipped) "zip" else "json"}"
            if (!share) { state.pending = bytes; if (zipped) zipFile.launch(name) else jsonFile.launch(name) }
            else io({
                val directory = File(cacheDir, "shared-exports").apply { mkdirs() }
                directory.listFiles()?.filter { it.isFile && it.lastModified() < System.currentTimeMillis() - 24 * 60 * 60 * 1000L }?.forEach { it.delete() }
                File(directory, "${UUID.randomUUID()}-$name").apply { writeBytes(bytes) }
            }) { file ->
                val uri = FileProvider.getUriForFile(this, "$packageName.exports", file)
                val send = Intent(Intent.ACTION_SEND).setType(if (zipped) "application/zip" else "application/json").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                send.clipData = ClipData.newRawUri(name, uri); startActivity(Intent.createChooser(send, "Dosyayı paylaş"))
            }
        }
    }
}
