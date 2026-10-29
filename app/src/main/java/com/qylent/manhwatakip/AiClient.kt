package com.qylent.manhwatakip

import org.json.JSONObject
import java.net.URL
import java.net.SocketTimeoutException
import java.net.HttpURLConnection

class AiClient internal constructor(private val localBaseUrl: String, private val connectionFactory: (URL) -> HttpURLConnection) {
    constructor(connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) : this(AiSettings.LOCAL_BASE_URL, connectionFactory)
    fun chat(settings: AiSettings, key: String, text: String, snapshot: List<Series>, history: List<Pair<String, String>> = emptyList()): AiReply {
        return AiProtocol.decode(send(settings, key, AiProtocol.request(settings, text, snapshot, history, localBaseUrl)), snapshot)
    }
    fun complete(settings: AiSettings, key: String, messages: org.json.JSONArray): String {
        val body = JSONObject().put("model", settings.validated(localBaseUrl).model).put("stream", false).put("messages", messages).toString()
        require(body.toByteArray(Charsets.UTF_8).size <= 512 * 1024) { "Kapak isteği çok büyük." }
        return send(settings, key, body)
    }
    private fun send(settings: AiSettings, key: String, body: String): String {
        val valid = settings.validated(localBaseUrl)
        val isLocalHttp = localBaseUrl.startsWith("http://") && valid.baseUrl == localBaseUrl
        require((key.isNotBlank() || (key.isEmpty() && isLocalHttp)) && key.length <= 4096 && key.none { it.isISOControl() }) { "Önce AI ayarlarını ve API anahtarını kaydet." }
        val connection = connectionFactory(URL(valid.endpoint))
        try {
            connection.connectTimeout = 15000; connection.readTimeout = 45000
            connection.instanceFollowRedirects = false; connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (key.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer $key")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            when (connection.responseCode) {
                in 200..299 -> Unit
                401, 403 -> throw IllegalArgumentException("AI erişimi reddedildi. Anahtarını ve sağlayıcı izinlerini kontrol et.")
                429 -> throw IllegalArgumentException("AI kullanım sınırına ulaşıldı. Daha sonra yeniden dene.")
                else -> throw IllegalArgumentException("AI servisi isteği tamamlayamadı. Adres/model ayarlarını kontrol et ve yeniden dene.")
            }
            val content = connection.inputStream.use { input ->
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; require(bytes.size() + n <= 1024 * 1024) { "AI yanıtı çok büyük." }; bytes.write(buffer, 0, n) }
                bytes.toString("UTF-8")
            }
            val answer = try {
                val choice = JSONObject(content).getJSONArray("choices").getJSONObject(0)
                require(choice.optString("finish_reason") !in setOf("length", "content_filter", "tool_calls"))
                val message = choice.getJSONObject("message")
                require(message.get("content") is String && !message.has("tool_calls"))
                message.getString("content")
            } catch (_: Exception) { throw IllegalArgumentException("AI yanıtı eksik veya desteklenmiyor. Hiçbir kayıt değiştirilmedi.") }
            return answer
        } catch (_: SocketTimeoutException) { throw IllegalArgumentException("AI bağlantısı zaman aşımına uğradı. Tekrar deneyebilirsin.") }
        catch (error: IllegalArgumentException) { throw error }
        catch (_: Exception) { throw IllegalArgumentException("AI bağlantısı kurulamadı. Ağını ve servis adresini kontrol et.") }
        finally { connection.disconnect() }
    }
}
