package com.qylent.manhwatakip

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.Closeable
import java.util.UUID

data class ChatRecord(
    val id: String = UUID.randomUUID().toString(),
    val history: List<Pair<String, String>> = emptyList(),
    val draft: String = "",
    val answer: String = "Seri adını/bağlantısını yaz, okuma kaydını anlat veya kişisel öneri iste. AI bilgileri doğrulanmış kaynak değildir.",
    val proposals: List<BridgePending> = emptyList(),
)

/** Local chat archive; message rows avoid SQLite's per-row CursorWindow limit. */
class ChatStore(context: Context, name: String = "chats.db") : Closeable {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, name, null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE chats (id TEXT PRIMARY KEY NOT NULL, draft TEXT NOT NULL, answer TEXT NOT NULL, updated_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE active_chat (id INTEGER PRIMARY KEY CHECK(id = 1), chat_id TEXT NOT NULL)")
            db.execSQL("CREATE TABLE chat_messages (chat_id TEXT NOT NULL, sequence INTEGER NOT NULL, role TEXT NOT NULL, body TEXT NOT NULL, PRIMARY KEY(chat_id, sequence))")
            db.execSQL("CREATE TABLE chat_proposals (chat_id TEXT NOT NULL, token TEXT NOT NULL, data TEXT NOT NULL, PRIMARY KEY(chat_id, token))")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            check(oldVersion == 1 && newVersion == 2)
            // Preserve the initial development-format archive, including large JSON via chunk reads.
            val oldActive = db.rawQuery("SELECT chat_id FROM active_chat WHERE id=1", null).use { if (it.moveToFirst()) it.getString(0) else null }
            val old = db.rawQuery("SELECT id, length(data) FROM chats", null).use { cursor -> buildList {
                while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getInt(1))
            } }
            db.execSQL("ALTER TABLE chats RENAME TO old_chats"); db.execSQL("DROP TABLE active_chat")
            onCreate(db)
            old.forEach { (id, length) ->
                val data = buildString {
                    for (start in 1..length step 16000) db.rawQuery("SELECT substr(data, ?, 16000) FROM old_chats WHERE id=?", arrayOf(start.toString(), id)).use { it.moveToFirst(); append(it.getString(0)) }
                }
                val json = JSONObject(data); val messages = json.getJSONArray("history")
                write(db, ChatRecord(id, (0 until messages.length()).map { i -> messages.getJSONObject(i).let { it.getString("role") to it.getString("text") } },
                    json.getString("draft"), json.getString("answer"), BridgeCodec.decode(json.getJSONObject("pending").toString()).proposals))
            }
            oldActive?.let { activate(db, it) }
            db.execSQL("DROP TABLE old_chats")
        }
    }
    private fun insert(db: SQLiteDatabase, table: String, values: ContentValues) {
        check(db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE) >= 0) { "Sohbet kaydedilemedi." }
    }
    private fun activate(db: SQLiteDatabase, id: String) = insert(db, "active_chat", ContentValues().apply { put("id", 1); put("chat_id", id) })
    private fun write(db: SQLiteDatabase, chat: ChatRecord) {
        insert(db, "chats", ContentValues().apply { put("id", chat.id); put("draft", chat.draft); put("answer", chat.answer); put("updated_at", System.currentTimeMillis()) })
        db.delete("chat_messages", "chat_id=?", arrayOf(chat.id))
        chat.history.forEachIndexed { index, (role, text) ->
            require(role in listOf("user", "assistant"))
            insert(db, "chat_messages", ContentValues().apply { put("chat_id", chat.id); put("sequence", index); put("role", role); put("body", text) })
        }
        db.delete("chat_proposals", "chat_id=?", arrayOf(chat.id))
        chat.proposals.forEach { pending ->
            insert(db, "chat_proposals", ContentValues().apply { put("chat_id", chat.id); put("token", pending.token); put("data", BridgeCodec.encodeLocal(BridgeResponse(0, listOf(pending))).toString()) })
        }
        activate(db, chat.id)
    }
    fun save(chat: ChatRecord) {
        val db = helper.writableDatabase; db.beginTransaction()
        try { write(db, chat); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    fun active(): ChatRecord? = helper.readableDatabase.rawQuery("SELECT chat_id FROM active_chat WHERE id=1", null).use { if (it.moveToFirst()) get(it.getString(0)) else null }
    fun get(id: String): ChatRecord? {
        val db = helper.readableDatabase
        return db.query("chats", arrayOf("draft", "answer"), "id=?", arrayOf(id), null, null, null).use { chat ->
            if (!chat.moveToFirst()) null else {
                val history = db.query("chat_messages", arrayOf("role", "body"), "chat_id=?", arrayOf(id), null, null, "sequence ASC").use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) } }
                val proposals = db.query("chat_proposals", arrayOf("data"), "chat_id=?", arrayOf(id), null, null, "token ASC").use { cursor -> buildList { while (cursor.moveToNext()) addAll(BridgeCodec.decode(cursor.getString(0)).proposals) } }
                ChatRecord(id, history, chat.getString(0), chat.getString(1), proposals)
            }
        }
    }
    fun list(): List<Pair<String, String>> = helper.readableDatabase.rawQuery("SELECT c.id, substr(COALESCE((SELECT body FROM chat_messages WHERE chat_id=c.id ORDER BY sequence LIMIT 1), NULLIF(c.draft,''), 'Boş sohbet'),1,80) FROM chats c ORDER BY updated_at DESC, id ASC", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1).replace('\n', ' ')) }
    }
    fun removeProposal(token: String) { helper.writableDatabase.delete("chat_proposals", "token=?", arrayOf(token)) }
    fun importMissing(chats: List<ChatRecord>, activeId: String?): Int {
        val db = helper.writableDatabase; db.beginTransaction()
        try {
            val previous = active()?.id
            val additions = chats.filter { get(it.id) == null }
            additions.forEach { write(db, it) }
            if (previous != null) activate(db, previous)
            else if (activeId != null && get(activeId) != null) activate(db, activeId)
            else db.delete("active_chat", "id=1", null)
            db.setTransactionSuccessful(); return additions.size
        } finally { db.endTransaction() }
    }
    override fun close() = helper.close()
}
