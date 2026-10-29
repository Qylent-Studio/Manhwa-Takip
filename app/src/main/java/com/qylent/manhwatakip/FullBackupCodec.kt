package com.qylent.manhwatakip

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object FullBackupCodec {
    const val MAX_BYTES = 32 * 1024 * 1024
    private val magic = "MHTFULL1".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 600_000
    private const val HEADER = 44
    fun isEncrypted(bytes: ByteArray) = bytes.size >= magic.size && bytes.copyOfRange(0, magic.size).contentEquals(magic)
    fun encrypt(payload: ByteArray, password: CharArray): ByteArray {
        require(payload.isNotEmpty() && payload.size <= MAX_BYTES) { "Yedek boyutu sınırı aşıldı (32 MB)." }
        checkPassword(password)
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER).put(magic).putInt(ITERATIONS).put(salt).put(nonce).putInt(payload.size).array()
        return header + crypt(Cipher.ENCRYPT_MODE, payload, password, salt, nonce, header)
    }
    fun decrypt(bytes: ByteArray, password: CharArray): ByteArray {
        checkPassword(password)
        require(bytes.size in (HEADER + 17)..(MAX_BYTES + HEADER + 16) && isEncrypted(bytes)) { "Tam yedek biçimi geçersiz." }
        val input = ByteBuffer.wrap(bytes)
        input.position(8)
        require(input.int == ITERATIONS) { "Yedek şifreleme sürümü desteklenmiyor." }
        val salt = ByteArray(16).also(input::get)
        val nonce = ByteArray(12).also(input::get)
        val size = input.int
        require(size in 1..MAX_BYTES && bytes.size == HEADER + size + 16) { "Yedek boyutu geçersiz." }
        return try { crypt(Cipher.DECRYPT_MODE, bytes.copyOfRange(HEADER, bytes.size), password, salt, nonce, bytes.copyOfRange(0, HEADER)) }
        catch (error: java.security.GeneralSecurityException) { throw IllegalArgumentException("Parola yanlış veya yedek bozulmuş.", error) }
    }
    private fun checkPassword(password: CharArray) { require(password.size in 8..256) { "Parola 8–256 karakter olmalı." } }
    private fun crypt(mode: Int, bytes: ByteArray, password: CharArray, salt: ByteArray, nonce: ByteArray, header: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(header); doFinal(bytes)
            }
        } finally { key.fill(0) }
    }
}
