package com.qylent.manhwatakip

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.time.LocalDate

class BackupsActivity : LibraryActivity() {
    class State : ViewModel() {
        var encrypted: ByteArray? = null
        var restore: ByteArray? = null
        var preview: FullRestorePreview? = null
        override fun onCleared() { encrypted?.fill(0); restore?.fill(0) }
    }
    private val state by lazy { ViewModelProvider(this)[State::class.java] }
    private val full by lazy { (application as LibraryApplication).let { FullBackupStore(it.store, it.covers, it.chats, AppPreferences(this), AiSecretStore(this), BridgePreferences(this)) } }
    private val selections = mutableListOf<CheckBox>()
    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = state.encrypted
        if (uri != null && bytes != null) io({
            contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) } ?: error("Dosya açılamadı")
            state.encrypted = null; bytes.fill(0)
        }) { notice("Şifreli tam yedek kaydedildi."); finish() }
        else if (uri != null) notice("Parolayı yeniden girerek yedeği oluştur.")
    }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) io({ contentResolver.openInputStream(uri)?.use(FullBackupStore::read) ?: error("Dosya açılamadı") }) { bytes ->
            if (FullBackupCodec.isEncrypted(bytes)) { state.restore = bytes; askRestorePassword() }
            else if (bytes.take(2) == listOf(80.toByte(), 75.toByte()) || runCatching { org.json.JSONObject(PortableFiles.text(bytes)).optString("format") == "manhwa-full" }.getOrDefault(false)) {
                io({ full.previewPortable(bytes).also { state.preview = it } }) { confirmRestore(it) }
            } else io({ BackupCodec.readLibrary(bytes.inputStream()) }) { backup -> previewLegacy(backup) }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        shell("Şifreli tam yedek")
        val column = Ui.scroll(this, page)
        column.addView(Ui.text(this, "Yedek parolayla şifrelenir. Parolayı unutursan dosya açılamaz. Parola telefonda veya yedekte saklanmaz.", 14f))
        val labels = listOf("Kütüphane ve kategoriler", "Kapaklar", "Okuma geçmişi", "Sohbetler ve taslaklar", "Uygulama ve AI ayarları", "API ve MCP anahtarları")
        labels.forEachIndexed { index, label ->
            val check = CheckBox(this).apply { text = label; setTextColor(Ui.ink); isChecked = savedInstanceState?.getBoolean("part$index", true) ?: true }
            selections.add(check); column.addView(check)
        }
        fun dependencies() {
            listOf(1, 2).forEach { index -> selections[index].isEnabled = selections[0].isChecked; if (!selections[0].isChecked) selections[index].isChecked = false }
            selections[5].isEnabled = selections[4].isChecked; if (!selections[4].isChecked) selections[5].isChecked = false
        }
        selections[0].setOnCheckedChangeListener { _, _ -> dependencies() }; selections[4].setOnCheckedChangeListener { _, _ -> dependencies() }; dependencies()
        fun action(label: String, block: () -> Unit) { Ui.button(this, label, action = block).also { column.addView(it); actionButtons.add(it) } }
        action("Parolalı yedek oluştur") { createBackup() }
        action("Parolasız JSON / ZIP yedek") { startActivity(android.content.Intent(this, PortableExportActivity::class.java).putExtra("backup", true)) }
        action("Hazır yedeği dosyaya kaydet") {
            if (state.encrypted == null) notice("Önce parolalı yedeği oluştur.") else chooseExport()
        }
        action("Yedeği geri yükle") { importFile.launch(arrayOf("application/octet-stream", "application/json", "application/zip", "text/plain")) }
        action("Seçilen yedeğin parolasını yeniden gir") { if (state.restore == null) notice("Önce geri yüklenecek dosyayı seç.") else askRestorePassword() }
        column.addView(Ui.text(this, "Geri yükleme önizlemeden sonra başlar. Mevcut seri, sohbet, kapak, ayar ve anahtarlar korunur. AI paylaşımı ve MCP bağlantısı kendiliğinden açılmaz. Eski JSON yedekleri de okunabilir.", 14f))
        state.preview?.let { confirmRestore(it) } ?: state.restore?.let { askRestorePassword() }
    }
    override fun onSaveInstanceState(outState: Bundle) { selections.forEachIndexed { index, check -> outState.putBoolean("part$index", check.isChecked) }; super.onSaveInstanceState(outState) }
    private fun password(title: String, repeat: Boolean, completed: (CharArray) -> Unit) {
        val column = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(24, 16, 24, 16) }
        fun field(hint: String) = Ui.input(this, hint, 256).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false; importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING; column.addView(this)
        }
        val first = field("Yedek parolası"); val second: EditText? = if (repeat) field("Parolayı tekrar gir") else null
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(column).setNegativeButton("İptal", null).setPositiveButton("Devam", null).create()
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val chars = first.text.toString().toCharArray()
                if (chars.size !in 8..256 || second != null && !chars.contentEquals(second.text.toString().toCharArray())) {
                    chars.fill('\u0000'); first.error = "En az 8 karakter ve aynı parola gerekli."; return@setOnClickListener
                }
                first.text.clear(); second?.text?.clear(); dialog.dismiss(); completed(chars)
            }
        }
        dialog.setOnDismissListener { first.text.clear(); second?.text?.clear() }; dialog.show()
    }
    private fun chooseExport() = exportFile.launch("manhwa-tam-${LocalDate.now()}.mhtbackup")
    private fun createBackup() {
        val checked = selections.map { it.isChecked }
        val selection = BackupSelection(checked[0], checked[1], checked[2], checked[3], checked[4], checked[5])
        password("Tam yedek parolası", true) { chars -> io({
            try { full.export(selection, chars).also { state.encrypted?.fill(0); state.encrypted = it } } finally { chars.fill('\u0000') }
        }) { chooseExport() } }
    }
    private fun askRestorePassword() {
        val bytes = state.restore ?: return
        password("Yedeğin parolası", false) { chars -> io({ try { full.preview(bytes, chars).also { state.preview = it } } finally { chars.fill('\u0000') } }) { confirmRestore(it) } }
    }
    private fun confirmRestore(preview: FullRestorePreview) {
        AlertDialog.Builder(this).setTitle("Tam yedeği geri yükle")
            .setMessage("Seriler: ${preview.seriesPlan.added} eklenecek; ${preview.seriesPlan.skipped} mevcut kayıt/çakışma korunacak.\nSohbetler: ${preview.chatsAdditions} eklenecek; ${preview.chatCount - preview.chatsAdditions} korunacak.\nKapaklar: ${preview.coversAdditions} eklenecek; ${preview.coverCount - preview.coversAdditions} atlanacak.\nGeçmiş: ${preview.historyAdditions} eklenecek; ${preview.historyCount - preview.historyAdditions} mevcut veya uyumsuz kayıt atlanacak.\n\nAyarlar: ${preview.settingsAction}\nAPI anahtarı: ${preview.apiKeyAction}\nMCP anahtarı: ${preview.bridgeKeyAction}\n\nYalnızca eksikler eklenecek. Önizlemeden sonra değişen mevcut bilgiler de korunur.")
            .setNegativeButton("İptal") { _, _ -> state.preview = null; state.restore = null }
            .setPositiveButton("Eksikleri geri yükle") { _, _ -> io({ full.importMissing(preview) }) { result ->
                state.preview = null; state.restore = null
                notice("${result.library.added} seri, ${result.chatsAdded} sohbet ve ${result.coversAdded} kapak eklendi. Mevcut veriler korundu."); finish()
            } }.show()
    }
    private fun previewLegacy(backup: LibraryBackup) {
        io({ store.previewImport(backup.records) }) { preview ->
            AlertDialog.Builder(this).setTitle("Eski JSON yedeğini geri yükle").setMessage("${preview.added} seri eklenecek; ${preview.skipped} mevcut seri korunacak. Anahtarlar bu biçimde bulunmaz.")
                .setNegativeButton("İptal", null).setPositiveButton("Geri yükle") { _, _ -> io({ store.importMissing(backup.records, backup.categories) }) { notice("${it.added} seri eklendi."); finish() } }.show()
        }
    }
}
