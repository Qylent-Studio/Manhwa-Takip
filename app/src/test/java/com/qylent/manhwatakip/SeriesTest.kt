package com.qylent.manhwatakip

import org.junit.Assert.*
import org.junit.Test

class SeriesTest {
    @Test fun trimsTitleAndNormalizesFractionalChapter() {
        val entry = Series(title = "  Çılgın Okur  ", lastChapter = " 48,50 ").normalized()
        assertEquals("Çılgın Okur", entry.title)
        assertEquals("48.5", entry.lastChapter)
    }

    @Test fun rejectsBlankTitleAndOversizedNotes() {
        assertThrows(IllegalArgumentException::class.java) { Series(title = "  ").normalized() }
        assertThrows(IllegalArgumentException::class.java) {
            Series(title = "Okur", notes = "x".repeat(4001)).normalized()
        }
    }

    @Test fun rejectsUnsafeOrMalformedLinks() {
        listOf("javascript:alert(1)", "file:///sdcard/book", "https://", "not a link", "https://user:pass@example.com").forEach { link ->
            assertThrows(link, IllegalArgumentException::class.java) { Series(title = "Okur", url = link).normalized() }
        }
        assertEquals("https://example.com/chapter/48", Series(title = "Okur", url = "https://example.com/chapter/48").normalized().url)
    }

    @Test fun rejectsNegativeOrInvalidChapters() {
        listOf("-1", "NaN", "1e8", "48..5", "1,2,3", "x", "9".repeat(21)).forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) { Series(title = "Okur", lastChapter = value).normalized() }
        }
        assertEquals("", Series(title = "Okur").normalized().lastChapter)
        assertEquals("0", Series(title = "Okur", lastChapter = "0").normalized().lastChapter)
    }

    @Test fun turkishSearchAndDuplicateKeyIgnoreCase() {
        assertTrue(Series(title = "IŞIK VE İNCİ").matches("ışık"))
        assertTrue(Series(title = "IŞIK VE İNCİ").matches("inci"))
        assertEquals(Series(title = " IŞIK ").duplicateKey(), Series(title = "ışık").duplicateKey())
        assertNotEquals(Series(title = "Okur", url = "https://example.com/a").duplicateKey(), Series(title = "Okur", url = "https://example.com/b").duplicateKey())
    }

    @Test fun changingStatusDoesNotEraseReasons() {
        val entry = Series(title = "Okur", likedReason = "Karakterler", dislikedReason = "Tempo", dropReason = "Tekrar")
        val changed = entry.copy(status = ReadingStatus.COMPLETED, opinion = Opinion.DISLIKED).normalized()
        assertEquals("Karakterler", changed.likedReason)
        assertEquals("Tempo", changed.dislikedReason)
        assertEquals("Tekrar", changed.dropReason)
    }
}
