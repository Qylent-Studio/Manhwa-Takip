package com.qylent.manhwatakip

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PortableFilesTest {
    @Test fun jsonZipRoundTripAndUnsafeArchives() {
        val bytes = "{\"title\":\"Türkçe 雪 🐉\"}".toByteArray()
        assertArrayEquals(bytes, PortableFiles.backup(PortableFiles.zip(mapOf("backup.json" to bytes))))
        assertArrayEquals(bytes, PortableFiles.backup(bytes))
        val bad = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("../backup.json")); zip.write(bytes); zip.closeEntry()
        } }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) { PortableFiles.backup(bad) }
        assertThrows(IllegalArgumentException::class.java) { PortableFiles.backup(PortableFiles.zip(mapOf("backup.json" to bytes, "extra" to bytes))) }
    }
    @Test fun desktopChangesHaveStrictFieldsAndStaleGuard() {
        val entry = Series(title = "Korunan", notes = "Kalsın", createdAt = 1, updatedAt = 2)
        val json = """{"changes":[{"id":"${entry.id}","expectedUpdatedAt":2,"fields":{"lastChapter":"12,5","favorite":true}}]}"""
        val proposal = DesktopTransfer.preview(json, listOf(entry)).single()
        assertEquals("12.5", proposal.after.lastChapter); assertEquals("Kalsın", proposal.after.notes)
        assertTrue(proposal.after.favorite)
        assertThrows(IllegalArgumentException::class.java) { DesktopTransfer.preview(json, listOf(entry.copy(updatedAt = 3))) }
        assertThrows(IllegalArgumentException::class.java) { DesktopTransfer.preview(json.replace("lastChapter", "apiKey"), listOf(entry)) }
        assertThrows(IllegalArgumentException::class.java) { DesktopTransfer.preview(json.replace("true", "\"true\""), listOf(entry)) }
    }
}
