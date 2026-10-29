package com.qylent.manhwatakip

import org.junit.Assert.*
import org.junit.Test

class FullBackupCodecTest {
    @Test fun portableEncryptedBackupAuthenticatesPasswordHeaderAndPayload() {
        val plain = "{\"key\":\"synthetic-test-key\",\"notes\":\"Türkçe\"}".toByteArray(Charsets.UTF_8)
        val password = "fixture-pass123".toCharArray()
        val encrypted = FullBackupCodec.encrypt(plain, password)
        assertArrayEquals(plain, FullBackupCodec.decrypt(encrypted, password))
        assertFalse(String(encrypted, Charsets.ISO_8859_1).contains("synthetic-test-key"))
        assertFalse(encrypted.contentEquals(FullBackupCodec.encrypt(plain, password)))
        assertThrows(IllegalArgumentException::class.java) { FullBackupCodec.decrypt(encrypted, "yanlis-test-parolasi".toCharArray()) }
        assertThrows(IllegalArgumentException::class.java) { FullBackupCodec.decrypt(encrypted.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }, password) }
        assertThrows(IllegalArgumentException::class.java) { FullBackupCodec.decrypt(encrypted.copyOf().apply { this[9] = (this[9].toInt() xor 1).toByte() }, password) }
        assertThrows(IllegalArgumentException::class.java) { FullBackupCodec.decrypt(byteArrayOf(1, 2), password) }
    }
    @Test fun shortPasswordAndOversizedPayloadAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { FullBackupCodec.encrypt(byteArrayOf(1), "123".toCharArray()) }
        assertThrows(IllegalArgumentException::class.java) { FullBackupCodec.encrypt(ByteArray(FullBackupCodec.MAX_BYTES + 1), "fixture-pass123".toCharArray()) }
    }
}
