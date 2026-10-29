package com.qylent.manhwatakip

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class BridgeClient(private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    fun check(key: String) {
        val reply = JSONObject(post("/health", key, "{}"))
        require(reply.optString("server") == "manhwa-usb-bridge" && reply.opt("pid") is Number && reply.getLong("pid") > 0) { "USB portunda beklenen bilgisayar köprüsü yok. Kısayollardan MCP köprüsünü başlat." }
    }
    fun sync(key: String, library: List<Series>, decisions: Map<String, String>): BridgeResponse = BridgeCodec.decode(post("/sync", key,
        JSONObject().put("library", JSONObject(BackupCodec.encode(library))).put("decisions", JSONObject(decisions)).toString()))
    fun disconnect(key: String) { val reply = JSONObject(post("/disconnect", key, "{}")); require(reply.optBoolean("disconnected")) { "Köprü bağlantıyı kapatmayı doğrulamadı." } }
    private fun post(path: String, key: String, body: String): String {
        require(key.matches(Regex("[A-Za-z0-9_-]{32,128}"))) { "Önce eşleştirme anahtarını kaydet." }
        require(body.toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024) { "Eşitleme içeriği 8 MiB sınırını aşıyor." }
        var connection: HttpURLConnection? = null
        try {
            val wire = connectionFactory(URL("http://127.0.0.1:8765$path"))
            connection = wire
            wire.connectTimeout = 5000; wire.readTimeout = 15000
            wire.instanceFollowRedirects = false; wire.requestMethod = "POST"; wire.doOutput = true
            wire.setRequestProperty("Content-Type", "application/json; charset=utf-8"); wire.setRequestProperty("Authorization", "Bearer $key")
            wire.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            when (wire.responseCode) {
                200 -> Unit
                401 -> throw IllegalArgumentException("Telefondaki anahtar bilgisayar köprüsüyle eşleşmiyor. Kısayollardan mevcut eşleştirme anahtarını al; telefonda eşleştirme ayarlarını onayla sıfırlayıp yeniden kaydet. Kayıtların değişmedi.")
                403 -> throw IllegalArgumentException("Bilgisayar köprüsü erişimi engelledi. USB yönlendirmesini ve doğru köprüye bağlandığını kontrol et. Kayıtların değişmedi.")
                400 -> throw IllegalArgumentException("Köprü eşitleme verisini kabul etmedi. Telefon uygulaması ve bilgisayar köprüsünün sürümlerini kontrol et. Kayıtların değişmedi.")
                else -> throw IllegalArgumentException("Bilgisayar köprüsü isteği tamamlayamadı. Kısayollardan MCP köprüsünü kontrol et ve yeniden dene. Kayıtların değişmedi.")
            }
            return wire.inputStream.use { input ->
                val bytes = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; require(bytes.size() + n <= 8 * 1024 * 1024) { "Köprü yanıtı çok büyük." }; bytes.write(buffer, 0, n) }
                bytes.toString("UTF-8")
            }
        } catch (e: IllegalArgumentException) { throw e }
        catch (_: Exception) { throw IllegalArgumentException("USB köprüsüne ulaşılamadı. Bilgisayarı, USB bağlantısını ve ADB yönlendirmesini kontrol et. Kayıtların değişmedi.") }
        finally { connection?.disconnect() }
    }
}
