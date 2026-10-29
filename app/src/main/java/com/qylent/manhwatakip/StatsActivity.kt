package com.qylent.manhwatakip

import android.os.Bundle

class StatsActivity : LibraryActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); shell("İstatistikler")
        io({ store.all() to store.history() }) { (entries, history) ->
            val column = Ui.scroll(this, page)
            fun metric(label: String, value: String) { Ui.label(this, column, label); column.addView(Ui.text(this, value, 24f, true)) }
            metric("Toplam seri", entries.size.toString())
            metric("Tamamlanan seri", entries.count { it.status == ReadingStatus.COMPLETED }.toString())
            metric("Favoriler", entries.count { it.favorite }.toString())
            metric("Takip döneminde net bölüm ilerlemesi", ReadingHistory.trackedProgress(history).toPlainString())
            column.addView(Ui.text(this, "Bölüm ilerlemesi bu güncellemeden sonraki değişikliklerden hesaplanır; geri almalar ve düzeltmeler düşülür.", 13f).apply { setTextColor(Ui.muted) })
            val recorded = entries.fold(java.math.BigDecimal.ZERO) { total, entry -> total.add(ReadingHistory.chapter(entry.lastChapter)) }
            metric("Kayıtlı bölüm ilerlemesi", recorded.stripTrailingZeros().toPlainString())
            column.addView(Ui.text(this, "Serilerin son bölüm numaraları toplamıdır; atlanan bölümleri veya yeniden okumaları ölçmez.", 13f).apply { setTextColor(Ui.muted) })
            Ui.label(this, column, "Okuma durumları")
            ReadingStatus.entries.forEach { status -> column.addView(Ui.text(this, "${status.label}: ${entries.count { it.status == status }}")) }
            Ui.label(this, column, "Kategori dağılımı")
            val counts = entries.flatMap { it.categories }.groupingBy { it }.eachCount()
            if (counts.isEmpty()) column.addView(Ui.text(this, "Henüz kategori seçilmedi."))
            counts.entries.sortedByDescending { it.value }.forEach { column.addView(Ui.text(this, "${it.key}: ${it.value} seri")) }
        }
    }
}
