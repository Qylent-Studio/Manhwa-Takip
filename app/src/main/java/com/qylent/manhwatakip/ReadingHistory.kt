package com.qylent.manhwatakip

import java.math.BigDecimal

data class ProgressChange(val id: String, val seriesId: String, val before: String, val after: String, val at: Long, val undoOf: String? = null)

object ReadingHistory {
    fun validate(changes: List<ProgressChange>, seriesIds: Set<String>) {
        require(changes.size <= 100_000 && changes.map { it.id }.distinct().size == changes.size) { "Yedekte bölüm geçmişi geçersiz veya çok büyük." }
        val byId = changes.associateBy { it.id }
        changes.forEach { change ->
            require(java.util.UUID.fromString(change.id).toString() == change.id && change.seriesId in seriesIds && change.at >= 0) { "Yedekte geçmiş kimliği veya tarihi geçersiz." }
            chapter(change.before); chapter(change.after)
            change.undoOf?.let { id ->
                val original = byId[id]
                require(original != null && original.id != change.id && original.undoOf == null && original.seriesId == change.seriesId && original.at <= change.at && original.before == change.after && original.after == change.before) { "Yedekte geri alma ilişkisi geçersiz." }
            }
        }
    }
    fun chapter(value: String): BigDecimal {
        val text = value.trim().replace(',', '.')
        require(text.isEmpty() || text.length <= 20 && text.matches(Regex("[0-9]+(\\.[0-9]+)?"))) { "Geçerli bir bölüm numarası gir." }
        return if (text.isEmpty()) BigDecimal.ZERO else BigDecimal(text)
    }
    fun nextChapter(value: String): String = chapter(value).add(BigDecimal.ONE).stripTrailingZeros().toPlainString()
    fun trackedProgress(changes: List<ProgressChange>): BigDecimal = changes.fold(BigDecimal.ZERO) { sum, change ->
        sum.add(chapter(change.after).subtract(chapter(change.before)))
    }.max(BigDecimal.ZERO).stripTrailingZeros()
}
