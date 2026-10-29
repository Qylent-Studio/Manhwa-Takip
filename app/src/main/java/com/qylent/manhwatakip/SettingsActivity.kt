package com.qylent.manhwatakip

import android.content.Intent
import android.os.Bundle
import android.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import java.io.IOException
import java.time.LocalDate
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import com.qylent.manhwatakip.Ui.dp

class SettingsActivity : LibraryActivity() {
    private var advancedExpanded = false
    private var group: String? = null
    private val groupBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (!busy) { group = null; render() }
        }
    }
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
        advancedExpanded = savedInstanceState?.getBoolean("advancedExpanded") ?: false
        group = savedInstanceState?.getString("settingsGroup")?.takeIf { it in listOf("library", "backup", "connections") }
        onBackPressedDispatcher.addCallback(this, groupBack)
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("advancedExpanded", advancedExpanded)
        outState.putString("settingsGroup", group)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        actionButtons.clear()
        groupBack.isEnabled = group != null
        shell("Ayarlar")
        val column = Ui.scroll(this, page)
        when (group) {
            "library" -> {
                Ui.label(this, column, "Kütüphane")
                settingRow(column, "Kategoriler", "Oluştur, renk seç ve sırala", R.drawable.ic_library) { open(CategoriesActivity::class.java) }
                settingRow(column, "İstatistikler", "Kayıtlı okuma verilerini görüntüle", R.drawable.ic_history) { open(StatsActivity::class.java) }
            }
            "backup" -> {
                Ui.label(this, column, "Yedekleme")
                column.addView(Ui.text(this, "Yedek al veya geri yükle. Geri yüklemeden önce önizleme ve onay istenir.", 14f))
                Ui.gap(this, column)
                // Existing file launchers and confirmation flows remain unchanged.
                column.addView(Ui.button(this, "Yedekler") { if (!busy) showBackups() }.also { actionButtons.add(it) })
                Ui.gap(this, column, 8)
                settingRow(column, "Şifreli tam yedek", "Parolayla koru; seçersen anahtarları dahil et", R.drawable.ic_backup) { open(BackupsActivity::class.java) }
                settingRow(column, "Parolasız JSON / ZIP yedek", "Tam yedeği anahtarlar olmadan dışa aktar", R.drawable.ic_backup) {
                    startActivity(Intent(this, PortableExportActivity::class.java).putExtra("backup", true))
                }
            }
            "connections" -> {
                Ui.label(this, column, "Bağlantılar")
                Ui.label(this, column, "Bilgisayar / USB")
                column.addView(Ui.text(this, "Bilgisayarda Manhwa Takip açıkken USB kablosunu bağla. Veriler otomatik alınır. Elle düzenleyip Telefona aktar seçince ek telefon onayı gerekmez; AI önerileri onay beklemeye devam eder.", 14f))
                Ui.gap(this, column)
                settingRow(column, "ChatGPT / MCP", "Eşleştirme, paylaşım ve onay bekleyen öneriler", R.drawable.ic_desktop) { open(BridgeActivity::class.java) }
                column.addView(Ui.text(this, "Paylaşım yalnızca sen açtığında başlar. Bu ekranı açmak verilerini paylaşmaz.", 13f))
            }
            else -> {
                column.addView(Ui.text(this, "Uygulamayı kendine göre düzenle.", 14f).apply { setTextColor(Ui.muted) })
                Ui.label(this, column, "Uygulama teması")
                val themes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val current = AppPreferences(this).theme
                listOf("system" to "Sistem", "light" to "Açık", "dark" to "Karanlık").forEach { (mode, label) ->
                    themes.addView(Ui.button(this, label, mode == current) {
                        if (!busy && mode != AppPreferences(this).theme) io({ AppPreferences(this).theme = mode }) { recreate() }
                    }.apply {
                        minWidth = 0; minimumWidth = 0; isSelected = mode == current
                        setPadding(dp(6), dp(10), dp(6), dp(10))
                        actionButtons.add(this)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
                }
                column.addView(themes)
                Ui.label(this, column, "Günlük ayarlar")
                settingRow(column, "Görünüm", "Yazı boyutu ve satır başına kart", R.drawable.ic_settings) { open(AppSettingsActivity::class.java) }
                settingRow(column, "Kütüphane", "Kategoriler ve istatistikler", R.drawable.ic_library) { showGroup("library") }
                settingRow(column, "Yedekleme", "Yedek al veya geri yükle", R.drawable.ic_backup) { showGroup("backup") }
                Ui.gap(this, column, 12)
                val advanced = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                fun fillAdvanced() {
                    advanced.removeAllViews()
                    if (advancedExpanded) {
                        settingRow(advanced, "AI ayarları", "Sağlayıcı, model ve API anahtarı", R.drawable.ic_chat) { open(AiSettingsActivity::class.java) }
                        settingRow(advanced, "Bağlantılar", "Bilgisayar, USB ve ChatGPT / MCP", R.drawable.ic_desktop) { showGroup("connections") }
                    }
                }
                val toggle = Ui.button(this, "Gelişmiş · AI ve bağlantılar") {}.apply {
                    fun describeState() { contentDescription = "Gelişmiş · AI ve bağlantılar, ${if (advancedExpanded) "açık" else "kapalı"}" }
                    describeState()
                    setCompoundDrawablesWithIntrinsicBounds(0, 0, if (advancedExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more, 0)
                    setOnClickListener {
                        if (!busy) {
                            advancedExpanded = !advancedExpanded
                            describeState()
                            setCompoundDrawablesWithIntrinsicBounds(0, 0, if (advancedExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more, 0)
                            fillAdvanced()
                        }
                    }
                    actionButtons.add(this)
                }
                column.addView(toggle); Ui.gap(this, column, 8); column.addView(advanced); fillAdvanced()
                Ui.gap(this, column)
                column.addView(Ui.text(this, "Manhwa Takip · ${BuildConfig.VERSION_NAME}", 12f).apply { setTextColor(Ui.muted); gravity = Gravity.CENTER })
                page.addView(LibraryNavigation.create(this, LibraryNavigation.Destination.SETTINGS) { !busy })
            }
        }
    }

    private fun open(target: Class<*>) { if (!busy) startActivity(Intent(this, target)) }
    private fun showGroup(value: String) { if (!busy) { group = value; render() } }

    private fun settingRow(parent: LinearLayout, title: String, summary: String, icon: Int, action: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(76); setPadding(dp(14), dp(14), dp(14), dp(14))
            background = RippleDrawable(ColorStateList.valueOf(Ui.userBubble), Ui.surface(), null)
            isFocusable = true; contentDescription = "Ayar: $title"
            setOnClickListener { if (!busy) action() }
        }
        row.addView(ImageView(this).apply {
            setImageResource(icon); imageTintList = ColorStateList.valueOf(Ui.accent)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(14) })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(this@SettingsActivity, title, 16f, true))
            addView(Ui.text(this@SettingsActivity, summary, 13f).apply { setTextColor(Ui.muted) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back); rotation = 180f
            imageTintList = ColorStateList.valueOf(Ui.muted)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(8) })
        parent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
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
