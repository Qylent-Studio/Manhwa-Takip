package com.qylent.manhwatakip

import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONTokener

data class BridgePending(val token: String, val proposal: AiProposal)
data class BridgeResponse(val lastSync: Long, val proposals: List<BridgePending>)

object BridgeCodec {
    fun record(entry: Series): JSONObject = JSONObject(BackupCodec.encode(listOf(entry))).getJSONArray("records").getJSONObject(0)
    private fun entry(record: JSONObject): Series = BackupCodec.decode(JSONObject().put("format", "manhwa-takip").put("version", 1)
        .put("exportedAt", 0).put("records", JSONArray().put(record)).toString()).single().let {
            record.optJSONObject("local")?.let { local -> BackupCodec.withLocalFields(it, local).normalized() } ?: it
        }
    fun decode(text: String): BridgeResponse {
        try {
            require(text.toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024)
            val tokenizer = JSONTokener(text)
            val root = tokenizer.nextValue() as JSONObject
            require(tokenizer.nextClean() == '\u0000')
            val last = root.get("lastSync")
            require((last is Long || last is Int) && (last as Number).toLong() >= 0)
            val items = root.getJSONArray("proposals")
            require(items.length() <= 200)
            val pending = (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                require(item.get("token") is String && item.has("before"))
                val token = item.getString("token")
                require(token.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
                val before = if (item.isNull("before")) null else entry(item.getJSONObject("before"))
                val after = entry(item.getJSONObject("after"))
                require(before == null || (before.id == after.id && before.createdAt == after.createdAt && before.updatedAt == after.updatedAt))
                BridgePending(token, AiProposal(before, after))
            }
            require(pending.map { it.token }.toSet().size == pending.size)
            return BridgeResponse((last as Number).toLong(), pending)
        } catch (_: Exception) { throw IllegalArgumentException("Köprü yanıtı geçersiz. Hiçbir kayıt değiştirilmedi.") }
    }
    fun encode(response: BridgeResponse): JSONObject = JSONObject().put("lastSync", response.lastSync).put("proposals", JSONArray().apply {
        response.proposals.forEach { put(JSONObject().put("token", it.token).put("before", it.proposal.before?.let(::record) ?: JSONObject.NULL).put("after", record(it.proposal.after))) }
    })
    fun encodeLocal(response: BridgeResponse): JSONObject = encode(response).also { root ->
        response.proposals.forEachIndexed { index, pending ->
            val item = root.getJSONArray("proposals").getJSONObject(index)
            pending.proposal.before?.let { item.getJSONObject("before").put("local", BackupCodec.localFields(it)) }
            item.getJSONObject("after").put("local", BackupCodec.localFields(pending.proposal.after))
        }
    }
}
