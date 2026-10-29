package com.qylent.manhwatakip

import android.content.Context
import android.content.res.Configuration

class AppPreferences(context: Context, name: String = "application") {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    var viewMode: String
        get() = prefs.getString("viewMode", "list") ?: "list"
        set(value) { require(value in listOf("list", "grid")); check(prefs.edit().putString("viewMode", value).commit()) }
    var sort: String
        get() = prefs.getString("sort", "updated") ?: "updated"
        set(value) { require(value in listOf("title", "created", "updated", "chapter")); check(prefs.edit().putString("sort", value).commit()) }
    var descending: Boolean
        get() = prefs.getBoolean("descending", true)
        set(value) { check(prefs.edit().putBoolean("descending", value).commit()) }
    var favoritesOnly: Boolean
        get() = prefs.getBoolean("favoritesOnly", false)
        set(value) { check(prefs.edit().putBoolean("favoritesOnly", value).commit()) }
    var theme: String
        get() = prefs.getString("theme", "dark") ?: "dark"
        set(value) { require(value in listOf("system", "light", "dark")); check(prefs.edit().putString("theme", value).commit()) }
    // Unset keeps the existing adaptive layout until the user makes a choice.
    var gridColumns: Int?
        get() = if (prefs.contains("gridColumns")) prefs.getInt("gridColumns", 3) else null
        set(value) { require(value == null || value in 2..3); check((if (value == null) prefs.edit().remove("gridColumns") else prefs.edit().putInt("gridColumns", value)).commit()) }
    var textSize: String
        get() = prefs.getString("textSize", "normal") ?: "normal"
        set(value) { require(value in listOf("normal", "large", "extra_large")); check(prefs.edit().putString("textSize", value).commit()) }
    val textScale get() = when (textSize) { "large" -> 1.15f; "extra_large" -> 1.3f; else -> 1f }
    fun isDark(context: Context) = theme == "dark" || theme == "system" && context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    fun backup() = org.json.JSONObject().put("theme", theme).put("viewMode", viewMode).put("sort", sort).put("descending", descending).put("favoritesOnly", favoritesOnly).put("textSize", textSize).apply { gridColumns?.let { put("gridColumns", it) } }
    fun missingBackupFields(data: org.json.JSONObject) = data.keys().asSequence().count { !prefs.contains(it) }
    fun importMissing(data: org.json.JSONObject) {
        validateBackup(data)
        val edit = prefs.edit()
        listOf("theme", "viewMode", "sort").filter { !prefs.contains(it) }.forEach { edit.putString(it, data.getString(it)) }
        listOf("descending", "favoritesOnly").filter { !prefs.contains(it) }.forEach { edit.putBoolean(it, data.getBoolean(it)) }
        if (data.has("gridColumns") && !prefs.contains("gridColumns")) edit.putInt("gridColumns", data.getInt("gridColumns"))
        if (data.has("textSize") && !prefs.contains("textSize")) edit.putString("textSize", data.getString("textSize"))
        check(edit.commit())
    }
    companion object {
        fun validateBackup(data: org.json.JSONObject) {
            require(data.get("theme") in listOf("system", "light", "dark") && data.get("viewMode") in listOf("list", "grid") && data.get("sort") in listOf("title", "created", "updated", "chapter")) { "Yedekte uygulama ayarları geçersiz." }
            require(data.get("descending") is Boolean && data.get("favoritesOnly") is Boolean) { "Yedekte görünüm ayarları geçersiz." }
            require(!data.has("gridColumns") || data.get("gridColumns") is Int && data.getInt("gridColumns") in 2..3) { "Yedekte kart sayısı geçersiz." }
            require(!data.has("textSize") || data.get("textSize") in listOf("normal", "large", "extra_large")) { "Yedekte yazı boyutu geçersiz." }
        }
    }
}
