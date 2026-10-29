package com.qylent.manhwatakip

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout

class DetailActivity : LibraryActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); shell("Seri detayı") }
    override fun onResume() {
        super.onResume()
        reload()
    }
    private fun reload() {
        val id = intent.getStringExtra("seriesId")
        if (id == null) { finish(); return }
        io({ store.get(id) to store.categoryDefinitions() }) { (entry, definitions) ->
            if (entry == null) { notice("Kayıt bulunamadı."); finish() } else render(entry, definitions)
        }
    }

    private fun render(entry: Series, definitions: List<CategoryDefinition>) {
        actionButtons.clear()
        shell("Seri detayı")
        val actions = LinearLayout(this)
        if (entry.url.isNotBlank()) {
            val read = Ui.button(this, "Okumaya git", true) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
                catch (_: ActivityNotFoundException) { notice("Bağlantıyı açacak bir tarayıcı bulunamadı.") }
            }
            actions.addView(read, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            actionButtons.add(read)
        }
        val edit = Ui.button(this, "Düzenle") { startActivity(Intent(this, EditActivity::class.java).putExtra("seriesId", entry.id)) }
        actions.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = with(Ui) { dp(8) } })
        actionButtons.add(edit)
        val next = Ui.button(this, "+1") { io({ store.updateChapter(entry, ReadingHistory.nextChapter(entry.lastChapter)) }) { reload() } }.apply { contentDescription = "${entry.title}: bir bölüm ilerle" }
        actions.addView(next); actionButtons.add(next)
        page.addView(actions)
        Ui.gap(this, page, 16)
        val column = Ui.scroll(this, page)
        val app = application as LibraryApplication
        if (entry.coverUrl.isNotEmpty()) {
            val cover = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; contentDescription = "${entry.title} kapağı" }
            column.addView(cover, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, with(Ui) { dp(190) })); app.covers.bind(this, cover, entry.id)
        }
        column.addView(Ui.text(this, entry.title, 28f, true))
        val favorite = Ui.button(this, if (entry.favorite) "Favorilerden çıkar" else "Favorilere ekle") { io({ store.applyProposal(AiProposal(entry, entry.copy(favorite = !entry.favorite))) }) { reload() } }
        column.addView(favorite); actionButtons.add(favorite)
        val pin = Ui.button(this, if (entry.pinned) "Sabitlemeyi kaldır" else "Sabitle") { io({ store.applyProposal(AiProposal(entry, entry.copy(pinned = !entry.pinned))) }) { reload() } }
        column.addView(pin); actionButtons.add(pin)
        val history = Ui.button(this, "Okuma geçmişi") { startActivity(Intent(this, HistoryActivity::class.java).putExtra("seriesId", entry.id)) }
        column.addView(history); actionButtons.add(history)
        val turkishCover = Ui.button(this, "Türkçe kaynaklarda kapak ara") {
            startActivity(Intent(this, EditActivity::class.java).putExtra("seriesId", entry.id).putExtra("findTurkishCover", true))
        }
        column.addView(turkishCover); actionButtons.add(turkishCover)
        val chapter = Ui.button(this, "Bölüm gir") {
            val input = Ui.input(this, "Son okunan bölüm", 20).apply { setText(entry.lastChapter); keyListener = android.text.method.DigitsKeyListener.getInstance("0123456789.,") }
            AlertDialog.Builder(this).setTitle(entry.title).setView(input).setNegativeButton("Vazgeç", null)
                .setPositiveButton("Kaydet") { _, _ -> io({ store.updateChapter(entry, input.text.toString()) }) { reload() } }.show()
        }
        column.addView(chapter); actionButtons.add(chapter)
        if (entry.url.isNotBlank()) {
            val fetch = Ui.button(this, "Kapağı getir / yenile") {}
            fetch.setOnClickListener {
                if (busy) return@setOnClickListener
                fetch.isEnabled = false; fetch.text = "Kapak getiriliyor…"
                app.networkExecutor.execute {
                    val result = runCatching { CoverFetcher().fetch(entry.url).also { CoverStore.decode(it.bytes).recycle() } }
                    runOnUiThread {
                        if (isDestroyed || isFinishing) return@runOnUiThread
                        fetch.isEnabled = true; fetch.text = "Kapağı getir / yenile"
                        result.fold({ cover -> io({
                            store.applyProposal(AiProposal(entry, entry.copy(coverUrl = cover.imageUrl)))
                            app.covers.save(entry.id, cover.bytes)
                        }) { reload() } }, { notice(if (it is IllegalArgumentException) it.message ?: "Kapak alınamadı." else "Kapak alınamadı; mevcut bilgiler korunuyor.") })
                    }
                }
            }
            column.addView(fetch); actionButtons.add(fetch)
        }
        fun field(label: String, value: String) {
            Ui.label(this, column, label)
            column.addView(Ui.text(this, value.ifBlank { "Belirtilmedi" }).apply { setTextIsSelectable(true) })
        }
        field("Okuma durumu", entry.status.label)
        if (entry.alternativeTitles.isNotEmpty()) field("Alternatif adlar", entry.alternativeTitles.joinToString("\n"))
        Ui.label(this, column, "Özel kategoriler")
        if (entry.categories.isEmpty()) column.addView(Ui.text(this, "Belirtilmedi"))
        entry.categories.sortedBy { name -> definitions.indexOfFirst { CategoryNames.key(it.name) == CategoryNames.key(name) }.let { if (it < 0) Int.MAX_VALUE else it } }.forEach { name ->
            column.addView(Ui.categoryLabel(this, name, definitions.firstOrNull { CategoryNames.key(it.name) == CategoryNames.key(name) }?.colorId ?: "green"))
            Ui.gap(this, column, 6)
        }
        field("Son okunan bölüm", entry.lastChapter)
        field("Beğeni", entry.opinion.label)
        field("Neden sevdim?", entry.likedReason)
        field("Neden sevmedim?", entry.dislikedReason)
        field("Bırakma / ara verme nedeni", entry.dropReason)
        field("Kendi notlarım", entry.notes)
        field("Okuma bağlantısı", entry.url)
        Ui.gap(this, column, 20)
        val delete = Ui.button(this, "Seriyi sil") {
            if (!busy) AlertDialog.Builder(this).setTitle("Seri silinsin mi?")
                .setMessage("${entry.title} ve bu seriye ait bölüm, neden ve not bilgileri kütüphaneden silinecek. Bu işlem geri alınamaz.")
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Sil") { _, _ ->
                    if (!busy) io({ store.delete(entry) }) { notice("Seri silindi."); finish() }
                }.show()
        }
        column.addView(delete); actionButtons.add(delete)
    }
}
