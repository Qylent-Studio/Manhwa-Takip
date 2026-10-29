package com.qylent.manhwatakip

import android.os.Bundle
import android.app.AlertDialog

class HistoryActivity : LibraryActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); shell("Okuma geçmişi") }
    override fun onResume() { super.onResume(); reload() }
    private fun reload() {
        io({ store.all().associateBy { it.id } to store.history(intent.getStringExtra("seriesId")) }) { (entries, history) ->
            actionButtons.clear(); shell("Okuma geçmişi")
            val column = Ui.scroll(this, page)
            column.addView(Ui.text(this, "Tarihli geçmiş bu güncellemeden itibaren tutulur. Eski bölüm bilgilerinin geçmiş tarihleri tahmin edilmez.", 14f))
            if (history.isEmpty()) { Ui.gap(this, column); column.addView(Ui.text(this, "Henüz bölüm değişikliği yok.")) }
            val latest = history.groupBy { it.seriesId }.mapValues { it.value.first().id }
            history.forEach { change ->
                Ui.gap(this, column, 16)
                val entry = entries[change.seriesId]
                column.addView(Ui.text(this, entry?.title ?: "Kütüphaneden kaldırılan seri", 18f, true))
                column.addView(Ui.text(this, "${change.before.ifBlank { "Belirtilmedi" }} → ${change.after.ifBlank { "Belirtilmedi" }}${if (change.undoOf != null) " · Geri alma" else ""}", 16f))
                column.addView(Ui.text(this, java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(change.at)), 13f).apply { setTextColor(Ui.muted) })
                if (entry != null && latest[entry.id] == change.id && change.undoOf == null && entry.lastChapter == change.after) {
                    val undo = Ui.button(this, "Bu bölüm değişikliğini geri al") {
                        AlertDialog.Builder(this).setTitle("Bölüm değişikliği geri alınsın mı?").setMessage("${entry.title}: ${change.after.ifBlank { "Belirtilmedi" }} → ${change.before.ifBlank { "Belirtilmedi" }}")
                            .setNegativeButton("Vazgeç", null).setPositiveButton("Geri al") { _, _ -> io({ store.undoProgress(entry, change.id) }) { reload() } }.show()
                    }
                    column.addView(undo); actionButtons.add(undo)
                }
            }
        }
    }
}
