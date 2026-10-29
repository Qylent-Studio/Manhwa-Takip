package com.qylent.manhwatakip

import org.junit.Assert.*
import org.junit.Test

class LibraryOrderTest {
    @Test fun pinnedStayFirstAndChapterSortingIsNumeric() {
        val entries = listOf(Series(title = "Dokuz", lastChapter = "9"), Series(title = "On", lastChapter = "10"), Series(title = "Sabit", lastChapter = "1", pinned = true))
        assertEquals(listOf("Sabit", "On", "Dokuz"), LibraryOrder.sort(entries, "chapter", true).map { it.title })
        assertEquals(listOf("Sabit", "Dokuz", "On"), LibraryOrder.sort(entries, "chapter", false).map { it.title })
    }
}
