package com.qylent.manhwatakip

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class ReadingHistoryTest {
    @Test fun incrementAndUndoUseDecimalProgressWithoutDoubleCounting() {
        assertEquals("1", ReadingHistory.nextChapter(""))
        assertEquals("49.5", ReadingHistory.nextChapter("48,5"))
        assertThrows(IllegalArgumentException::class.java) { ReadingHistory.nextChapter("-1") }
        val changes = listOf(ProgressChange("a", "series", "48.5", "51.5", 1),
            ProgressChange("b", "series", "51.5", "48.5", 2, "a"),
            ProgressChange("c", "series", "48.5", "49.5", 3))
        assertEquals(BigDecimal.ONE, ReadingHistory.trackedProgress(changes))
    }
    @Test fun customColorsKeepLegacyNamesAndRejectBadHex() {
        assertTrue(CategoryDefinition.validColor("green"))
        assertTrue(CategoryDefinition.validColor("red"))
        assertTrue(CategoryDefinition.validColor("#E53935"))
        assertFalse(CategoryDefinition.validColor("#12"))
        assertFalse(CategoryDefinition.validColor("#abcdef00"))
        val category = CategoryDefinition("Kırmızı", "#E53935")
        assertEquals(listOf(category), BackupCodec.decodeLibrary(BackupCodec.encodeLibrary(emptyList(), listOf(category))).categories)
    }
}
