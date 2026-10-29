package com.qylent.manhwatakip

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedPreferences(context: Context, val preferencesName: String) {
    private val prefs = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val alias = "manhwa.$preferencesName"
    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun read(): JSONObject {
        val blob = prefs.getString("encrypted", null) ?: return JSONObject()
        try {
            val obj = JSONObject(blob)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, Base64.decode(obj.getString("iv"), Base64.NO_WRAP)))
            return JSONObject(String(cipher.doFinal(Base64.decode(obj.getString("data"), Base64.NO_WRAP)), Charsets.UTF_8))
        } catch (_: Exception) { throw IllegalArgumentException("Şifreli ayarlar açılamadı. Ayarları sıfırlayıp anahtarı yeniden gir.") }
    }
    @Synchronized fun write(data: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val encrypted = JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)).toString()
        check(prefs.edit().putString("encrypted", encrypted).commit())
    }
    @Synchronized fun clear() { check(prefs.edit().clear().commit()); KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
}

class AiSecretStore(context: Context, val preferencesName: String = "ai_settings") {
    class Connection(val settings: AiSettings, val key: String, val consented: Boolean) {
        override fun toString() = "AI bağlantısı (anahtar gizli)"
    }
    private val encrypted = EncryptedPreferences(context, preferencesName)
    private fun read() = encrypted.read()
    private fun write(data: JSONObject) = encrypted.write(data)
    @Synchronized fun settings(): AiSettings? = read().let {
        if (!it.has("baseUrl")) null else AiSettings(it.getString("baseUrl"), it.getString("model")).validated()
    }
    @Synchronized fun key(): String = read().optString("key", "")
    @Synchronized fun consented(): Boolean = read().optBoolean("consent", false)
    @Synchronized fun connection(): Connection? {
        val data = read()
        if (!data.has("baseUrl")) return null
        return Connection(AiSettings(data.getString("baseUrl"), data.getString("model")).validated(), data.getString("key"), data.optBoolean("consent", false))
    }
    @Synchronized fun consent(expected: AiSettings) {
        val data = read()
        require(data.optString("baseUrl") == expected.baseUrl && data.optString("model") == expected.model) { "AI ayarları değişti. Yeni sağlayıcı için paylaşımı yeniden onayla." }
        write(data.put("consent", true))
    }
    @Synchronized fun save(settings: AiSettings, key: String) {
        val valid = settings.validated()
        require((key.isNotBlank() || (key.isEmpty() && valid.isLocalHttp)) && key.length <= 4096 && key.none { it.isISOControl() }) { "Geçerli bir API anahtarı gir." }
        val old = read()
        val same = old.optString("baseUrl") == valid.baseUrl && old.optString("model") == valid.model
        write(JSONObject().put("baseUrl", valid.baseUrl).put("model", valid.model).put("key", key)
            .put("consent", same && old.optBoolean("consent", false)))
    }
    @Synchronized fun clear() = encrypted.clear()
    @Synchronized fun importMissing(settings: AiSettings, key: String?) {
        val valid = settings.validated()
        val old = read()
        if (!old.has("baseUrl")) {
            write(JSONObject().put("baseUrl", valid.baseUrl).put("model", valid.model).put("key", key ?: "").put("consent", false))
        } else if (key != null && old.optString("key").isEmpty() && old.optString("baseUrl") == valid.baseUrl && old.optString("model") == valid.model) {
            write(old.put("key", key).put("consent", false))
        }
    }
}
