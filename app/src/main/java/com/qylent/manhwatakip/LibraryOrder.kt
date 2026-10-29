package com.qylent.manhwatakip

object LibraryOrder {
    fun sort(entries: List<Series>, by: String, descending: Boolean): List<Series> {
        val collator = java.text.Collator.getInstance(java.util.Locale.forLanguageTag("tr-TR"))
        val order = when (by) {
            "title" -> Comparator<Series> { a, b -> collator.compare(a.title, b.title) }
            "created" -> compareBy { it.createdAt }
            "chapter" -> compareBy { ReadingHistory.chapter(it.lastChapter) }
            else -> compareBy { it.updatedAt }
        }
        return entries.sortedWith(compareByDescending<Series> { it.pinned }.then(if (descending) order.reversed() else order).thenBy { it.id })
    }
}
