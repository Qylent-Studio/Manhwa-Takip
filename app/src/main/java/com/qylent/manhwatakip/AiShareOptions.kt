package com.qylent.manhwatakip

import org.json.JSONArray
import org.json.JSONObject

enum class AiShareField { STATUS, CHAPTER, OPINION, CATEGORIES, NOTES, REASONS, URL, HISTORY }

data class AiShareOptions(val fields: Set<AiShareField>, val selectedIds: Set<String>? = null)

// Construct the allowed projection first; downstream text and aggregates only see this data.
internal fun Series.aiShareProjection(fields: Set<AiShareField>, legacy: Boolean): JSONObject = JSONObject().put("title", title).apply {
    if (AiShareField.STATUS in fields) put("status", status.name)
    if (AiShareField.CHAPTER in fields) put("lastChapter", lastChapter)
    if (AiShareField.OPINION in fields) put("opinion", opinion.name)
    if (AiShareField.CATEGORIES in fields) put("categories", JSONArray(categories))
    if (AiShareField.NOTES in fields) put("notes", notes)
    if (AiShareField.REASONS in fields) {
        put("likedReason", likedReason); put("dislikedReason", dislikedReason); put("dropReason", dropReason)
    }
    if (AiShareField.URL in fields) put("url", url)
    if (legacy) {
        put("id", id); put("alternativeTitles", JSONArray(alternativeTitles)); put("favorite", favorite); put("pinned", pinned)
    }
}
