package com.qylent.manhwatakip

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

data class BackupSelection(val library: Boolean = true, val covers: Boolean = true, val history: Boolean = true,
    val chats: Boolean = true, val settings: Boolean = true, val keys: Boolean = true)

class FullRestorePreview internal constructor(private val payload: ByteArray, val seriesCount: Int, val chatCount: Int,
    val coverCount: Int, val historyCount: Int, val hasSettings: Boolean, val hasKeys: Boolean,
    val seriesPlan: ImportResult, val historyAdditions: Int, val chatsAdditions: Int, val coversAdditions: Int,
    val apiKeyAction: String, val bridgeKeyAction: String, val settingsAction: String) {
    internal fun payload() = payload.copyOf()
    override fun toString() = "Tam yedek önizlemesi (anahtarlar gizli)"
}
data class FullRestoreResult(val library: ImportResult, val chatsAdded: Int, val coversAdded: Int)

class FullBackupStore(private val library: LibraryStore, private val covers: CoverStore, private val chats: ChatStore,
    private val preferences: AppPreferences, private val ai: AiSecretStore, private val bridge: BridgePreferences) {
    fun export(selection: BackupSelection, password: CharArray): ByteArray {
        val payload = payload(selection)
        try { return FullBackupCodec.encrypt(payload, password) } finally { payload.fill(0) }
    }
    fun exportPortable(selection: BackupSelection = BackupSelection(keys = false)): ByteArray = payload(selection.copy(keys = false))
    private fun payload(selection: BackupSelection): ByteArray {
        require(selection.library || selection.chats || selection.settings) { "Yedeklenecek içeriği seç." }
        require((!selection.covers && !selection.history || selection.library) && (!selection.keys || selection.settings)) { "Kapak/geçmiş için kütüphaneyi; anahtarlar için ayarları seç." }
        val root = JSONObject().put("format", "manhwa-full").put("version", 1).put("exportedAt", System.currentTimeMillis())
        val records = if (selection.library) library.all() else emptyList()
        if (selection.library) root.put("library", JSONObject(BackupCodec.encodeLibrary(records, library.categoryDefinitions())))
        if (selection.covers) {
            var size = 0
            root.put("covers", JSONArray().apply { records.forEach { record -> covers.bytes(record.id)?.let { bytes ->
                size += bytes.size; require(size <= 20 * 1024 * 1024) { "Kapaklar 20 MB sınırını aşıyor; kapaksız yedek seçebilirsin." }
                put(JSONObject().put("seriesId", record.id).put("jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP)))
            } } })
        }
        if (selection.history) {
            val ids = records.mapTo(mutableSetOf()) { it.id }
            root.put("history", JSONArray().apply { library.history().filter { it.seriesId in ids }.forEach { change ->
                put(JSONObject().put("id", change.id).put("seriesId", change.seriesId).put("before", change.before)
                    .put("after", change.after).put("at", change.at).put("undoOf", change.undoOf ?: JSONObject.NULL))
            } })
        }
        if (selection.chats) {
            require(chats.list().size <= 1000) { "Tam yedek en fazla 1.000 sohbet içerebilir." }
            root.put("chats", JSONArray().apply { chats.list().forEach { (id, _) -> val chat = requireNotNull(chats.get(id))
                put(JSONObject().put("id", chat.id).put("draft", chat.draft).put("answer", chat.answer)
                    .put("history", JSONArray().apply { chat.history.forEach { (role, text) -> put(JSONObject().put("role", role).put("text", text)) } })
                    .put("pending", BridgeCodec.encodeLocal(BridgeResponse(0, chat.proposals))))
            } }).put("activeChat", chats.active()?.id ?: JSONObject.NULL)
        }
        if (selection.settings) {
            root.put("preferences", preferences.backup())
            ai.settings()?.let { root.put("ai", JSONObject().put("baseUrl", it.baseUrl).put("model", it.model)) }
        }
        if (selection.keys) root.put("keys", JSONObject().apply {
            if (root.has("ai")) put("api", ai.key())
            put("bridge", bridge.key())
        })
        val payload = root.toString().toByteArray(Charsets.UTF_8)
        validate(payload)
        return payload
    }

    fun preview(bytes: ByteArray, password: CharArray): FullRestorePreview {
        val payload = FullBackupCodec.decrypt(bytes, password)
        return previewPayload(payload)
    }
    fun previewPortable(bytes: ByteArray): FullRestorePreview {
        val payload = PortableFiles.backup(bytes)
        val text = PortableFiles.text(payload)
        ChatGptTransfer.strictJson(text)
        require(!JSONObject(text).has("keys")) { "Parolasız yedek anahtar içeremez." }
        return previewPayload(payload)
    }
    private fun previewPayload(payload: ByteArray): FullRestorePreview {
        try {
            val data = validate(payload)
            val current = library.all().associateBy { it.id }
            val currentKeys = current.values.mapTo(mutableSetOf()) { it.duplicateKey() }
            val coverIds = data.library.records.filter { current[it.id] == it || it.id !in current && it.duplicateKey() !in currentKeys }.mapTo(mutableSetOf()) { it.id }
            val apiAction = if (data.keys?.has("api") != true || data.keys.optString("api").isEmpty()) "Yedekte yok"
                else if (ai.settings() == null || ai.settings() == data.ai && ai.key().isEmpty()) "Eklenecek" else "Mevcut anahtar korunacak"
            val bridgeAction = if (data.keys?.optString("bridge").isNullOrEmpty()) "Yedekte yok" else if (bridge.key().isEmpty()) "Eklenecek" else "Mevcut anahtar korunacak"
            return FullRestorePreview(payload.copyOf(), data.library.records.size, data.chats.size, data.covers.size,
                data.history.size, data.preferences != null, data.keys != null, library.previewImport(data.library.records),
                library.previewHistoryImport(data.library.records, data.history), data.chats.count { chats.get(it.id) == null },
                data.covers.count { (id, _) -> id in coverIds && covers.bytes(id) == null },
                apiAction, bridgeAction, if (data.preferences == null) "Yedekte yok" else "${preferences.missingBackupFields(data.preferences)} eksik görünüm ayarı eklenecek. AI: ${if (data.ai == null) "yedekte yok" else if (ai.settings() == null) "eklenecek" else "mevcut ayarlar korunacak"}.")
        } finally { payload.fill(0) }
    }

    fun importMissing(preview: FullRestorePreview): FullRestoreResult {
        // Revalidate the complete payload before touching either database, files or settings.
        val bytes = preview.payload()
        val data = try { validate(bytes) } finally { bytes.fill(0) }
        val result = library.importMissing(data.library.records, data.library.categories, data.history)
        var coversAdded = 0
        var chatsAdded = 0
        try {
            data.covers.forEach { (id, image) ->
                val incoming = data.library.records.first { it.id == id }
                if (library.get(id) == incoming && covers.bytes(id) == null) { covers.save(id, image); coversAdded++ }
            }
            chatsAdded = chats.importMissing(data.chats, data.activeChat)
            data.preferences?.let(preferences::importMissing)
            data.ai?.let { ai.importMissing(it, data.keys?.optString("api")?.takeIf { data.keys.has("api") }) }
            data.keys?.optString("bridge")?.takeIf { it.isNotEmpty() && bridge.key().isEmpty() }?.let(bridge::saveKey)
        } catch (_: Exception) {
            // ponytail: two SQLite databases and atomic files cannot share one transaction; retry adds only missing data.
            throw IllegalArgumentException("Geri yükleme tamamlanamadı. Mevcut veriler korunuyor; ${result.added} seri, $chatsAdded sohbet ve $coversAdded kapak eklendi. Aynı yedeği yeniden deneyebilirsin.")
        }
        return FullRestoreResult(result, chatsAdded, coversAdded)
    }

    private class Contents(val library: LibraryBackup, val covers: Map<String, ByteArray>, val history: List<ProgressChange>,
        val chats: List<ChatRecord>, val activeChat: String?, val preferences: JSONObject?, val ai: AiSettings?, val keys: JSONObject?)
    private fun validate(payload: ByteArray): Contents {
        require(payload.isNotEmpty() && payload.size <= FullBackupCodec.MAX_BYTES) { "Tam yedek çok büyük." }
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(payload)).toString()
            val tokener = JSONTokener(text); val root = tokener.nextValue()
            require(root is JSONObject && tokener.nextClean() == '\u0000' && root.get("format") == "manhwa-full" && number(root, "version") == 1L && number(root, "exportedAt") >= 0) { "Tam yedek biçimi geçersiz." }
            require(root.has("library") || root.has("chats") || root.has("preferences")) { "Yedek içeriği boş." }
            val records = if (root.has("library")) BackupCodec.decodeLibrary(root.getJSONObject("library").toString()) else LibraryBackup(emptyList(), emptyList())
            val ids = records.records.mapTo(mutableSetOf()) { it.id }
            require(root.has("library") || !root.has("covers") && !root.has("history"))
            val images = linkedMapOf<String, ByteArray>()
            if (root.has("covers")) array(root, "covers", 10_000).forEach { image ->
                val id = string(image, "seriesId", 36); require(id in ids && id !in images) { "Yedekte kapak ilişkisi geçersiz." }
                val encoded = string(image, "jpeg", 7 * 1024 * 1024)
                val bytes = Base64.decode(encoded, Base64.NO_WRAP)
                require(bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()) { "Yedekte kapak biçimi geçersiz." }
                CoverStore.decode(bytes).recycle(); images[id] = bytes
            }
            val history = if (root.has("history")) array(root, "history", 100_000).map { item ->
                ProgressChange(string(item, "id", 36), string(item, "seriesId", 36), string(item, "before", 20), string(item, "after", 20), number(item, "at"), if (item.isNull("undoOf")) null else string(item, "undoOf", 36))
            } else emptyList()
            ReadingHistory.validate(history, ids)
            val archive = if (root.has("chats")) array(root, "chats", 1000).map { item ->
                val id = string(item, "id", 36); require(UUID.fromString(id).toString() == id)
                val messages = array(item, "history", 10_000).map { message ->
                    val role = string(message, "role", 16); require(role in listOf("user", "assistant"))
                    role to string(message, "text", 100_000)
                }
                ChatRecord(id, messages, string(item, "draft", 100_000), string(item, "answer", 100_000), BridgeCodec.decode(item.getJSONObject("pending").toString()).proposals)
            } else emptyList()
            require(archive.map { it.id }.distinct().size == archive.size) { "Yedekte mükerrer sohbet var." }
            val active = if (!root.has("activeChat") || root.isNull("activeChat")) null else string(root, "activeChat", 36).also { id -> require(archive.any { it.id == id }) }
            val settings = if (root.has("preferences")) root.getJSONObject("preferences").also(AppPreferences::validateBackup) else null
            val connection = if (root.has("ai")) root.getJSONObject("ai").let { require(settings != null); AiSettings(string(it, "baseUrl", 2048), string(it, "model", 200)).validated() } else null
            val keys = if (root.has("keys")) root.getJSONObject("keys").also { data ->
                require(settings != null)
                val bridgeKey = string(data, "bridge", 128)
                require(bridgeKey.isEmpty() || bridgeKey.matches(Regex("[A-Za-z0-9_-]{32,128}")))
                if (data.has("api")) { require(connection != null); val key = string(data, "api", 4096); require(key.none { it.isISOControl() } && (key.isEmpty() || key.isNotBlank())) }
            } else null
            return Contents(records, images, history, archive, active, settings, connection, keys)
        } catch (error: Exception) {
            throw IllegalArgumentException("Yedek eksik, bozuk veya desteklenmiyor; hiçbir veri değiştirilmedi.", error)
        }
    }
    private fun array(root: JSONObject, key: String, max: Int): List<JSONObject> {
        val value = root.get(key); require(value is JSONArray && value.length() <= max)
        return (0 until value.length()).map { value.getJSONObject(it) }
    }
    private fun string(root: JSONObject, key: String, max: Int): String { val value = root.get(key); require(value is String && value.length <= max); return value }
    private fun number(root: JSONObject, key: String): Long { val value = root.get(key); require(value is Int || value is Long); return (value as Number).toLong() }
    companion object {
        fun read(input: InputStream): ByteArray {
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= FullBackupCodec.MAX_BYTES + 60) { "Yedek 32 MB sınırını aşıyor." }; output.write(buffer, 0, count) }
            return output.toByteArray()
        }
    }
}
