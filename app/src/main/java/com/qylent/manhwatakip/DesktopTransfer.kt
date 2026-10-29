package com.qylent.manhwatakip

import org.json.JSONArray
import org.json.JSONObject

object DesktopTransfer {
    fun preview(text: String, current: List<Series>): List<AiProposal> {
        require(text.toByteArray().size <= 8 * 1024 * 1024) { "Düzenleme paketi çok büyük." }
        ChatGptTransfer.strictJson(text)
        val root = JSONObject(text)
        require(root.keys().asSequence().toSet() == setOf("changes")) { "Düzenleme paketi geçersiz." }
        val changes = root.getJSONArray("changes")
        require(changes.length() in 1..10_000) { "Düzenleme listesi boş veya çok büyük." }
        val byId = current.associateBy { it.id }; val seen = mutableSetOf<String>()
        return (0 until changes.length()).map { index ->
            val item = changes.getJSONObject(index)
            require(item.keys().asSequence().toSet() == setOf("id", "expectedUpdatedAt", "fields"))
            val id = item.get("id"); require(id is String && seen.add(id)) { "Mükerrer veya geçersiz kimlik." }
            val before = requireNotNull(byId[id]) { "Seri silinmiş veya bulunamadı. Yenile; hiçbir değişiklik uygulanmadı." }
            val expected = item.get("expectedUpdatedAt")
            require((expected is Long || expected is Int) && (expected as Number).toLong() == before.updatedAt) { "Telefon kaydı değişti. Yenile; hiçbir değişiklik uygulanmadı." }
            val fields = item.getJSONObject("fields")
            val allowed = setOf("title", "url", "lastChapter", "status", "opinion", "likedReason", "dislikedReason", "dropReason", "notes", "categories", "alternativeTitles", "favorite", "pinned")
            require(fields.length() > 0 && fields.keys().asSequence().all { it in allowed }) { "Düzenleme alanı geçersiz." }
            fun string(name: String, fallback: String): String = if (!fields.has(name)) fallback else fields.get(name).also { require(it is String) { "$name metin olmalı." } } as String
            fun boolean(name: String, fallback: Boolean): Boolean = if (!fields.has(name)) fallback else fields.get(name).also { require(it is Boolean) { "$name işaret alanı olmalı." } } as Boolean
            fun list(name: String, fallback: List<String>): List<String> {
                if (!fields.has(name)) return fallback
                val items = fields.get(name); require(items is JSONArray && items.length() <= 100)
                return (0 until items.length()).map { items.get(it).also { value -> require(value is String) } as String }
            }
            val after = before.copy(title = string("title", before.title), url = string("url", before.url), lastChapter = string("lastChapter", before.lastChapter),
                status = ReadingStatus.valueOf(string("status", before.status.name)), opinion = Opinion.valueOf(string("opinion", before.opinion.name)),
                likedReason = string("likedReason", before.likedReason), dislikedReason = string("dislikedReason", before.dislikedReason), dropReason = string("dropReason", before.dropReason),
                notes = string("notes", before.notes), categories = list("categories", before.categories), alternativeTitles = list("alternativeTitles", before.alternativeTitles),
                favorite = boolean("favorite", before.favorite), pinned = boolean("pinned", before.pinned)).normalized()
            AiProposal(before, after)
        }.filter { it.before != it.after }
    }
}
