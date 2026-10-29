package com.qylent.manhwatakip

import android.content.Context
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.Closeable

data class ImportResult(val added: Int, val skipped: Int)

class LibraryStore(context: Context, databaseName: String = "library.db") : Closeable {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, databaseName, null, 6) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE series (
                id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, url TEXT NOT NULL,
                last_chapter TEXT NOT NULL, status TEXT NOT NULL, opinion TEXT NOT NULL,
                liked_reason TEXT NOT NULL, disliked_reason TEXT NOT NULL, drop_reason TEXT NOT NULL,
                notes TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                duplicate_key TEXT NOT NULL UNIQUE, categories TEXT NOT NULL DEFAULT '[]', local_fields TEXT NOT NULL DEFAULT '{}'
            )""")
            createBridgeOutbox(db)
            createDeletedSeries(db)
            createCategories(db)
            createHistory(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            check(oldVersion in 1..5 && newVersion == 6) { "Desteklenmeyen veri geçişi." }
            if (oldVersion == 1) createBridgeOutbox(db)
            if (oldVersion < 3) createDeletedSeries(db)
            val columns = db.rawQuery("PRAGMA table_info(series)", null).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(1)) } }
            if ("categories" !in columns) db.execSQL("ALTER TABLE series ADD COLUMN categories TEXT NOT NULL DEFAULT '[]'")
            if ("local_fields" !in columns) db.execSQL("ALTER TABLE series ADD COLUMN local_fields TEXT NOT NULL DEFAULT '{}'")
            createCategories(db)
            val categoryColumns = db.rawQuery("PRAGMA table_info(categories)", null).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(1)) } }
            if ("color_id" !in categoryColumns) db.execSQL("ALTER TABLE categories ADD COLUMN color_id TEXT NOT NULL DEFAULT 'green'")
            if ("position" !in categoryColumns) {
                db.execSQL("ALTER TABLE categories ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
                val names = db.rawQuery("SELECT folded FROM categories ORDER BY name ASC", null).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
                names.forEachIndexed { index, key -> db.execSQL("UPDATE categories SET position=? WHERE folded=?", arrayOf<Any>(index, key)) }
            }
            createHistory(db)
        }
    }.apply { setWriteAheadLoggingEnabled(true) }

    fun all(): List<Series> = helper.readableDatabase.query("series", null, null, null, null, null, "updated_at DESC, title ASC").use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.entry()) }
    }

    fun get(id: String): Series? = helper.readableDatabase.query("series", null, "id = ?", arrayOf(id), null, null, null).use { cursor ->
        if (cursor.moveToFirst()) cursor.entry() else null
    }

    fun delete(entry: Series) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            require(get(entry.id) == entry) { "Kayıt silme onayından sonra değişti veya bulunamadı. Seriyi yeniden aç; hiçbir kayıt silinmedi." }
            check(db.delete("series", "id = ?", arrayOf(entry.id)) == 1)
            db.insertWithOnConflict("deleted_series", null, ContentValues().apply { put("id", entry.id) }, SQLiteDatabase.CONFLICT_IGNORE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun save(entry: Series): Series {
        val valid = entry.normalized()
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val duplicate = db.query("series", arrayOf("id"), "duplicate_key = ? AND id != ?", arrayOf(valid.duplicateKey(), valid.id), null, null, null, "1").use { it.moveToFirst() }
            require(!duplicate) { "Bu seri aynı bağlantıyla zaten kayıtlı. Mevcut kaydı düzenleyebilirsin." }
            val previous = get(valid.id)
            require(previous != null || !db.query("deleted_series", arrayOf("id"), "id = ?", arrayOf(valid.id), null, null, null, "1").use { it.moveToFirst() }) {
                "Bu kayıt silinmiş. Eski taslak veya öneriyle tekrar eklenemez."
            }
            val saved = if (previous == null) valid else valid.copy(createdAt = previous.createdAt, updatedAt = maxOf(valid.updatedAt, System.currentTimeMillis(), previous.updatedAt + 1))
            if (previous == null) db.insertOrThrow("series", null, saved.values())
            else check(db.update("series", saved.values(), "id = ?", arrayOf(saved.id)) == 1)
            saved.categories.forEach { addCategory(it) }
            if (previous != null && previous.lastChapter != saved.lastChapter) {
                db.insertOrThrow("reading_history", null, ContentValues().apply {
                    put("id", java.util.UUID.randomUUID().toString()); put("series_id", saved.id)
                    put("before_chapter", previous.lastChapter); put("after_chapter", saved.lastChapter); put("at", saved.updatedAt)
                })
            }
            db.setTransactionSuccessful()
            return saved
        } finally { db.endTransaction() }
    }

    fun applyCover(expected: Series, cover: CoverResult, covers: CoverStore): Series {
        val db = helper.writableDatabase
        var previous: ByteArray? = null
        var replaced = false
        try {
            db.beginTransaction()
            try {
                require(get(expected.id) == expected) { "Seri işlem sırasında değişti veya silindi; kapak atlandı." }
                require(!covers.contains(expected.id)) { "Serinin kapağı zaten var; mevcut kapak korundu." }
                previous = covers.bytes(expected.id)
                val saved = save(expected.copy(coverUrl = cover.imageUrl))
                covers.save(saved.id, cover.bytes)
                replaced = true
                db.setTransactionSuccessful()
                return saved
            } finally { db.endTransaction() }
        } catch (error: Exception) {
            if (replaced) covers.restore(expected.id, previous)
            throw error
        }
    }

    fun previewImport(entries: List<Series>): ImportResult {
        val incoming = BackupCodec.validate(entries)
        return missing(incoming).let { ImportResult(it.size, incoming.size - it.size) }
    }

    fun applyProposal(proposal: AiProposal, bridgeToken: String? = null): Series {
        val valid = proposal.after.normalized()
        require(proposal.before == null || proposal.before.id == valid.id) { "Kayıt kimliği değiştirilemez." }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val current = get(valid.id)
            require((if (bridgeToken != null) current?.wireProjection() else current) == proposal.before) { "Kayıt önizlemeden sonra değişti. Yeni bilgiyle tekrar öneri hazırla; hiçbir değişiklik uygulanmadı." }
            bridgeToken?.let { writeBridgeDecision(db, it, "approved") }
            val after = if (bridgeToken != null && current != null) valid.keepLocalFields(current) else valid
            val saved = save(after.copy(updatedAt = maxOf(System.currentTimeMillis(), (proposal.before?.updatedAt ?: valid.createdAt) + 1)))
            db.setTransactionSuccessful()
            return saved
        } finally { db.endTransaction() }
    }

    fun applyTransfer(proposals: List<AiProposal>): List<Series> {
        require(proposals.isNotEmpty()) { "İçe aktarılacak değişiklik yok." }
        val after = BackupCodec.validate(proposals.map { it.after })
        require(proposals.all { it.before == null || it.before.id == it.after.id }) { "Kayıt kimliği değiştirilemez." }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            require(proposals.all { get(it.after.id) == it.before }) { "Seriler önizlemeden sonra değişti; hiçbir değişiklik uygulanmadı. Yeniden önizle." }
            val changedIds = after.mapTo(mutableSetOf()) { it.id }
            val unchanged = all().filter { it.id !in changedIds }
            BackupCodec.validate(unchanged + after)
            ChatGptTransfer.validateNewIdentities(proposals.filter { it.before == null }.map { it.after },
                unchanged + proposals.filter { it.before != null }.map { it.after })
            val result = proposals.map { applyProposal(it) }
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    private fun createDeletedSeries(db: SQLiteDatabase) {
        // Keep only deleted IDs so stale create proposals and edit drafts cannot resurrect records.
        db.execSQL("CREATE TABLE deleted_series (id TEXT PRIMARY KEY NOT NULL)")
    }
    private fun createBridgeOutbox(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE bridge_decisions (token TEXT PRIMARY KEY NOT NULL, decision TEXT NOT NULL)")
    }
    fun bridgeDecisions(): Map<String, String> = helper.readableDatabase.query("bridge_decisions", null, null, null, null, null, null).use { cursor ->
        buildMap { while (cursor.moveToNext()) put(cursor.getString(cursor.getColumnIndexOrThrow("token")), cursor.getString(cursor.getColumnIndexOrThrow("decision"))) }
    }
    private fun writeBridgeDecision(db: SQLiteDatabase, token: String, decision: String) {
        require(token.isNotBlank() && token.length <= 128 && decision in setOf("approved", "rejected")) { "Köprü kararı geçersiz." }
        val existing = bridgeDecisions()
        require(token !in existing) { "Bu öneri için karar zaten kaydedildi." }
        require(existing.size < 200) { "Önce kararlarını bilgisayarla eşitle; kayıt değiştirilmedi." }
        db.insertOrThrow("bridge_decisions", null, ContentValues().apply { put("token", token); put("decision", decision) })
    }
    fun recordBridgeRejection(token: String) {
        val db = helper.writableDatabase; db.beginTransaction()
        try { writeBridgeDecision(db, token, "rejected"); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    fun ackBridgeDecisions(sent: Map<String, String>) {
        val db = helper.writableDatabase; db.beginTransaction()
        try {
            sent.forEach { (token, decision) -> db.delete("bridge_decisions", "token = ? AND decision = ?", arrayOf(token, decision)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun importMissing(entries: List<Series>, categories: List<CategoryDefinition> = emptyList(), changes: List<ProgressChange> = emptyList()): ImportResult {
        // Validate the entire batch before the first write: an invalid tail cannot leave partial data.
        val incoming = BackupCodec.validate(entries)
        val definitions = CategoryDefinition.validate(categories)
        ReadingHistory.validate(changes, incoming.mapTo(mutableSetOf()) { it.id })
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val additions = missing(incoming)
            definitions.forEach { addCategory(it.name, it.colorId) }
            additions.forEach { entry -> db.insertOrThrow("series", null, entry.values()); entry.categories.forEach { addCategory(it) } }
            missingHistory(incoming, changes).forEach { change ->
                // A colliding history ID belongs to the existing phone data and is never replaced.
                db.insertWithOnConflict("reading_history", null, ContentValues().apply {
                    put("id", change.id); put("series_id", change.seriesId); put("before_chapter", change.before)
                    put("after_chapter", change.after); put("at", change.at); put("undo_of", change.undoOf)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
            return ImportResult(additions.size, incoming.size - additions.size)
        } finally { db.endTransaction() }
    }

    private fun missing(incoming: List<Series>): List<Series> {
        val existing = all()
        val ids = existing.mapTo(mutableSetOf()) { it.id }
        val keys = existing.mapTo(mutableSetOf()) { it.duplicateKey() }
        return incoming.filter { it.id !in ids && it.duplicateKey() !in keys }
    }
    fun previewHistoryImport(entries: List<Series>, changes: List<ProgressChange>): Int = missingHistory(entries, changes).size
    private fun missingHistory(incoming: List<Series>, changes: List<ProgressChange>): List<ProgressChange> {
        val compatible = missing(incoming).mapTo(mutableSetOf()) { it.id }
        incoming.filter { entry -> get(entry.id)?.let { it.duplicateKey() == entry.duplicateKey() && it.lastChapter == entry.lastChapter } == true }.forEach { compatible.add(it.id) }
        val existing = history().associateBy { it.id }
        val additions = changes.filter { it.seriesId in compatible && it.id !in existing }
        val available = existing + additions.associateBy { it.id }
        val originals = changes.associateBy { it.id }
        return additions.filter { it.undoOf == null || available[it.undoOf] == originals[it.undoOf] }
    }

    override fun close() = helper.close()

    private fun createHistory(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS reading_history (id TEXT PRIMARY KEY NOT NULL, series_id TEXT NOT NULL, before_chapter TEXT NOT NULL, after_chapter TEXT NOT NULL, at INTEGER NOT NULL, undo_of TEXT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS reading_history_series ON reading_history(series_id, at)")
    }
    fun history(seriesId: String? = null): List<ProgressChange> = helper.readableDatabase.query("reading_history", null,
        if (seriesId == null) null else "series_id=?", seriesId?.let { arrayOf(it) }, null, null, "at DESC, id DESC").use { cursor ->
        buildList { while (cursor.moveToNext()) add(ProgressChange(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getLong(4), cursor.getString(5))) }
    }
    fun updateChapter(expected: Series, chapter: String): Series = applyProposal(AiProposal(expected, expected.copy(lastChapter = chapter)))
    fun undoProgress(expected: Series, changeId: String): Series {
        val db = helper.writableDatabase; db.beginTransaction()
        try {
            val last = history(expected.id).firstOrNull()
            require(last?.id == changeId && last.undoOf == null && last.after == expected.lastChapter) { "Yalnızca en son bölüm değişikliği geri alınabilir." }
            val saved = updateChapter(expected, last.before)
            val correction = history(expected.id).first()
            check(db.update("reading_history", ContentValues().apply { put("undo_of", last.id) }, "id=?", arrayOf(correction.id)) == 1)
            db.setTransactionSuccessful(); return saved
        } finally { db.endTransaction() }
    }
    fun applyBulk(expected: List<Series>, status: ReadingStatus?, addCategories: List<String>, removeCategories: List<String>): List<Series> {
        require(expected.isNotEmpty() && expected.map { it.id }.distinct().size == expected.size) { "Düzenlenecek serileri seç." }
        val additions = addCategories.map(CategoryNames::normalize)
        val removals = removeCategories.mapTo(mutableSetOf(), CategoryNames::key)
        val db = helper.writableDatabase; db.beginTransaction()
        try {
            require(expected.all { get(it.id) == it }) { "Seriler önizlemeden sonra değişti; toplu işlem uygulanmadı." }
            val result = expected.map { entry -> save(entry.copy(status = status ?: entry.status,
                categories = (entry.categories.filter { CategoryNames.key(it) !in removals } + additions).distinctBy(CategoryNames::key))) }
            db.setTransactionSuccessful(); return result
        } finally { db.endTransaction() }
    }

    private fun Series.values() = ContentValues().apply {
        put("id", id); put("title", title); put("url", url); put("last_chapter", lastChapter)
        put("status", status.name); put("opinion", opinion.name); put("liked_reason", likedReason)
        put("disliked_reason", dislikedReason); put("drop_reason", dropReason); put("notes", notes)
        put("created_at", createdAt); put("updated_at", updatedAt); put("duplicate_key", duplicateKey())
        put("categories", org.json.JSONArray(categories).toString())
        put("local_fields", BackupCodec.localFields(this@values).toString())
    }

    private fun Cursor.entry(): Series {
        fun text(column: String) = getString(getColumnIndexOrThrow(column))
        fun number(column: String) = getLong(getColumnIndexOrThrow(column))
        return Series(id = text("id"), title = text("title"), url = text("url"), lastChapter = text("last_chapter"),
            status = ReadingStatus.valueOf(text("status")), opinion = Opinion.valueOf(text("opinion")),
            likedReason = text("liked_reason"), dislikedReason = text("disliked_reason"), dropReason = text("drop_reason"),
            notes = text("notes"), createdAt = number("created_at"), updatedAt = number("updated_at"),
            categories = BackupCodec.categoryList(org.json.JSONArray(text("categories")))).let { BackupCodec.withLocalFields(it, org.json.JSONObject(text("local_fields"))) }
    }

    private fun createCategories(db: SQLiteDatabase) = db.execSQL("CREATE TABLE IF NOT EXISTS categories (name TEXT NOT NULL, folded TEXT PRIMARY KEY NOT NULL, color_id TEXT NOT NULL DEFAULT 'green', position INTEGER NOT NULL DEFAULT 0)")
    fun categories(): List<String> = categoryDefinitions().map { it.name }
    fun categoryDefinitions(): List<CategoryDefinition> = helper.readableDatabase.query("categories", arrayOf("name", "color_id", "position"), null, null, null, null, "position ASC, name ASC").use { cursor ->
        buildList { while (cursor.moveToNext()) add(CategoryDefinition(cursor.getString(0), cursor.getString(1), cursor.getInt(2))) }
    }
    fun addCategory(name: String, colorId: String = "green") {
        val valid = CategoryDefinition(name, colorId).normalized()
        val db = helper.writableDatabase
        val position = db.rawQuery("SELECT COALESCE(MAX(position), -1) + 1 FROM categories", null).use { it.moveToFirst(); it.getInt(0) }
        db.insertWithOnConflict("categories", null, ContentValues().apply { put("name", valid.name); put("folded", CategoryNames.key(valid.name)); put("color_id", valid.colorId); put("position", position) }, SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun changeCategory(old: String, replacement: String?, colorId: String? = null) {
        val oldKey = CategoryNames.key(old)
        val next = replacement?.let(CategoryNames::normalize)
        require(colorId == null || CategoryDefinition.validColor(colorId)) { "Kategori rengi geçersiz." }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            require(categories().any { CategoryNames.key(it) == oldKey }) { "Kategori bulunamadı." }
            require(next == null || categories().none { CategoryNames.key(it) != oldKey && CategoryNames.key(it) == CategoryNames.key(next) }) { "Bu kategori zaten var." }
            all().filter { entry -> entry.categories.any { CategoryNames.key(it) == oldKey } }.forEach { entry ->
                val names = entry.categories.mapNotNull { if (CategoryNames.key(it) == oldKey) next else it }
                if (names != entry.categories) check(db.update("series", entry.copy(categories = names, updatedAt = maxOf(System.currentTimeMillis(), entry.updatedAt + 1)).values(), "id = ?", arrayOf(entry.id)) == 1)
            }
            if (next == null) db.delete("categories", "folded = ?", arrayOf(oldKey))
            else check(db.update("categories", ContentValues().apply {
                put("name", next); put("folded", CategoryNames.key(next)); colorId?.let { put("color_id", it) }
            }, "folded = ?", arrayOf(oldKey)) == 1)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun moveCategory(name: String, direction: Int) {
        require(direction == -1 || direction == 1)
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val names = categories().toMutableList()
            val index = names.indexOfFirst { CategoryNames.key(it) == CategoryNames.key(name) }
            require(index >= 0) { "Kategori bulunamadı." }
            if (index + direction in names.indices) java.util.Collections.swap(names, index, index + direction)
            // ponytail: O(n) position writes; batch reorder if very large category lists become common.
            names.forEachIndexed { position, item -> db.execSQL("UPDATE categories SET position=? WHERE folded=?", arrayOf<Any>(position, CategoryNames.key(item))) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
