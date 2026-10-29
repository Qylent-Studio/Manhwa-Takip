package com.qylent.manhwatakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider

/** Temporary chat database and state: UI acceptance never changes the user's chats. */
class ChatFixture : java.io.Closeable {
    private val app = ApplicationProvider.getApplicationContext<Context>() as LibraryApplication
    private val originalStore = app.chats
    private val originalState = app.chatState
    val name = "chat-fixture-${System.nanoTime()}.db"
    init {
        check(originalState.pending == null) { "Önce mevcut AI isteğinin bitmesini bekle." }
        app.chats = ChatStore(app, name); app.chatState = AiActivity.ChatState()
    }
    override fun close() {
        app.executor.submit {}.get()
        app.chatState.tokens.forEach { app.proposals.remove(it) }
        app.chats.close(); app.deleteDatabase(name)
        app.chats = originalStore; app.chatState = originalState
    }
}
