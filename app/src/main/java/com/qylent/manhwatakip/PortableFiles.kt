package com.qylent.manhwatakip

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object PortableFiles {
    fun read(input: InputStream, limit: Int = FullBackupCodec.MAX_BYTES): ByteArray {
        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val count = input.read(buffer); if (count < 0) break
            require(output.size() + count <= limit) { "Dosya boyutu sınırı aşıldı." }; output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    fun text(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("Dosya UTF-8 biçiminde değil.") }
    fun zip(files: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> files.forEach { (name, bytes) ->
            require(name.matches(Regex("[A-Za-z0-9_.-]+")) && bytes.size <= FullBackupCodec.MAX_BYTES)
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
        return output.toByteArray().also { require(it.size <= FullBackupCodec.MAX_BYTES) { "ZIP boyutu sınırı aşıldı." } }
    }
    fun backup(bytes: ByteArray): ByteArray {
        require(bytes.isNotEmpty() && bytes.size <= FullBackupCodec.MAX_BYTES) { "Yedek boyutu geçersiz." }
        if (bytes.size < 2 || bytes[0] != 80.toByte() || bytes[1] != 75.toByte()) return bytes.copyOf()
        try {
            ZipInputStream(bytes.inputStream()).use { zip ->
                val entry = zip.nextEntry
                require(entry != null && entry.name == "backup.json" && !entry.isDirectory) { "ZIP yalnızca backup.json içermeli." }
                val payload = read(zip); zip.closeEntry()
                require(zip.nextEntry == null) { "ZIP yalnızca backup.json içermeli." }
                return payload
            }
        } catch (error: java.io.IOException) { throw IllegalArgumentException("ZIP dosyası bozuk.", error) }
    }
}
