package com.qylent.manhwatakip

import android.content.Context
import org.json.JSONObject

class BridgePreferences(context: Context, name: String = "bridge_settings") {
    private val vault = EncryptedPreferences(context, name)
    fun key(): String = vault.read().optString("key", "")
    fun saveKey(key: String) {
        require(key.matches(Regex("[A-Za-z0-9_-]{32,128}"))) { "Bilgisayardaki yerel eşleştirme anahtarını gir." }
        val data = vault.read()
        require(data.optString("key").isEmpty() || data.optString("key") == key) { "Köprüyü değiştirmek için önce bağlantıyı kapatıp yerel ayarları sıfırla." }
        vault.write(data.put("key", key))
    }
    fun pending(): List<BridgePending> = vault.read().optJSONObject("response")?.let { BridgeCodec.decode(it.toString()).proposals } ?: emptyList()
    fun receive(response: BridgeResponse, remainingDecisions: Map<String, String>) {
        val data = vault.read()
        vault.write(data.put("response", BridgeCodec.encode(response.copy(proposals = response.proposals.filter { it.token !in remainingDecisions }))))
    }
    fun clearPending() {
        val data = vault.read(); data.remove("response"); vault.write(data)
    }
    fun clear() = vault.clear()
}
