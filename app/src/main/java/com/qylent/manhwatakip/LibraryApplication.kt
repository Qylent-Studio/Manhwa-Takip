package com.qylent.manhwatakip

import android.app.Application
import java.util.concurrent.Executors

class LibraryApplication : Application() {
    val store by lazy { LibraryStore(this) }
    val covers by lazy { CoverStore(this) }
    val executor = Executors.newSingleThreadExecutor()
    val networkExecutor = Executors.newFixedThreadPool(2)
    val bulkCovers = BulkCoverQueue()
    val proposals = java.util.concurrent.ConcurrentHashMap<String, AiProposal>()
    lateinit var chats: ChatStore
    var chatState = AiActivity.ChatState()
    override fun onCreate() { super.onCreate(); chats = ChatStore(this) }
    @Volatile var bridgePending: java.util.concurrent.Future<String>? = null
}
