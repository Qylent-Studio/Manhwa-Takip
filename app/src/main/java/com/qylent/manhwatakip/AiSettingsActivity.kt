package com.qylent.manhwatakip

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager

class AiSettingsActivity : LibraryActivity() {
    private lateinit var base: android.widget.EditText
    private lateinit var model: android.widget.EditText
    private lateinit var key: android.widget.EditText
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        shell("AI ayarları")
        val column = Ui.scroll(this, page)
        column.addView(Ui.text(this, "OpenAI uyumlu Chat Completions servisi kullanılır. API anahtarı telefonda şifrelenir ve yedek/MCP'ye aktarılmaz. Tanımlı yerel HTTP adresinde anahtar isteğe bağlıdır; sunucu anahtar istiyorsa girmen gerekir. HTTP bağlantısında paylaşılan veriler ağda şifrelenmez.", 14f))
        Ui.label(this, column, "Servis temel adresi (HTTPS veya tanımlı yerel adres)")
        base = Ui.input(this, "Servis temel adresi", 2048).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; isSaveEnabled = false }
        column.addView(base)
        Ui.label(this, column, "Model")
        model = Ui.input(this, "Model adı", 200).apply { isSaveEnabled = false }
        column.addView(model)
        Ui.label(this, column, "API anahtarı")
        key = Ui.input(this, "API anahtarı (mevcut anahtarı korumak için boş)", 4096).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false; importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        column.addView(key)
        val secrets = AiSecretStore(this)
        io({ runCatching { secrets.settings() } }) { result ->
            result.fold({ settings -> base.setText(savedInstanceState?.getString("base") ?: settings?.baseUrl ?: ""); model.setText(savedInstanceState?.getString("model") ?: settings?.model ?: "") }, { notice(it.message ?: "Ayarlar açılamadı.") })
        }
        Ui.gap(this, column)
        val save = Ui.button(this, "Ayarları kaydet", true) {
            val settings = try { AiSettings(base.text.toString(), model.text.toString()).validated() }
            catch (e: IllegalArgumentException) { notice(e.message ?: "Ayarlar geçersiz."); return@button }
            val entered = key.text.toString().trim()
            io({
                val actual = if (entered.isNotEmpty()) entered else {
                    if (secrets.settings()?.baseUrl == settings.baseUrl) secrets.key()
                    else { require(settings.isLocalHttp) { "Yeni sağlayıcı için API anahtarını yeniden gir." }; "" }
                }
                secrets.save(settings, actual)
            }) { key.text.clear(); notice("AI ayarları kaydedildi."); finish() }
        }
        column.addView(save); actionButtons.add(save)
        Ui.gap(this, column)
        val reset = Ui.button(this, "AI ayarlarını sıfırla") {
            AlertDialog.Builder(this).setTitle("AI ayarları sıfırlansın mı?").setMessage("Yalnızca sağlayıcı ayarları ve API anahtarı kaldırılır. Kütüphanen korunur.")
                .setNegativeButton("İptal", null).setPositiveButton("Sıfırla") { _, _ -> io({ secrets.clear() }) { key.text.clear(); base.text.clear(); model.text.clear(); notice("AI ayarları sıfırlandı.") } }.show()
        }
        column.addView(reset); actionButtons.add(reset)
    }
    override fun onBusyChanged(active: Boolean) {
        if (::base.isInitialized) base.isEnabled = !active
        if (::model.isInitialized) model.isEnabled = !active
        if (::key.isInitialized) key.isEnabled = !active
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("base", base.text.toString()); outState.putString("model", model.text.toString())
        super.onSaveInstanceState(outState)
    }
}
