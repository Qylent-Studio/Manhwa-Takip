package com.qylent.manhwatakip

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class BulkCoverQueue {
    data class Progress(val total: Int, val completed: Int = 0, val saved: Int = 0, val skipped: Int = 0,
        val current: String = "", val failures: List<Pair<String, String>> = emptyList(),
        val running: Boolean = true, val cancelled: Boolean = false)

    @Volatile var progress: Progress? = null
        private set
    @Volatile private var stop = CountDownLatch(1)
    val cancelRequested get() = stop.count == 0L
    private data class Work(val entry: Series, val find: (Series) -> CoverResult,
        val save: (Series, CoverResult) -> Unit, val hasCover: (Series) -> Boolean, val cancelled: CountDownLatch)
    private val pending = java.util.ArrayDeque<Work>()
    private var active: Work? = null

    @Synchronized fun start(entries: List<Series>, executor: Executor,
        find: (Series) -> CoverResult, save: (Series, CoverResult) -> Unit,
        hasCover: (Series) -> Boolean = { false }): Boolean {
        if (progress?.running == true) return false
        return enqueue(entries, executor, find, save, hasCover)
    }

    /** Retain approved additions behind the current work, in the same process-owned lifecycle. */
    @Synchronized fun enqueue(entries: List<Series>, executor: Executor,
        find: (Series) -> CoverResult, save: (Series, CoverResult) -> Unit,
        hasCover: (Series) -> Boolean = { false }): Boolean {
        require(entries.isNotEmpty() && entries.map { it.id }.distinct().size == entries.size)
        val running = progress?.running == true
        if (!running || stop.count == 0L) stop = CountDownLatch(1)
        entries.forEach { pending.add(Work(it, find, save, hasCover, stop)) }
        progress = if (running) progress!!.copy(total = progress!!.total + entries.size) else Progress(entries.size)
        if (!running) executor.execute { drain() }
        return true
    }

    private fun drain() {
        var gap: CountDownLatch? = null
        while (true) {
            val work = synchronized(this) {
                pending.pollFirst().also {
                    active = it
                    if (it == null) progress = progress!!.copy(current = "", running = false)
                }
            } ?: return
            if (work.cancelled.count == 0L) { update { it.copy(cancelled = true) }; continue }
            gap?.await(2, TimeUnit.SECONDS)
            gap = null
            if (work.cancelled.count == 0L) { update { it.copy(cancelled = true) }; continue }
            update { it.copy(current = work.entry.title) }
            try {
                if (work.hasCover(work.entry)) {
                    update { it.copy(completed = it.completed + 1, skipped = it.skipped + 1) }
                    continue
                }
                val found = work.find(work.entry)
                if (work.cancelled.count == 0L) { update { it.copy(cancelled = true) }; continue }
                work.save(work.entry, found)
                update { it.copy(completed = it.completed + 1, saved = it.saved + 1) }
            } catch (error: Exception) {
                val message = if (error is IllegalArgumentException) error.message ?: "Kapak uygulanamadı."
                    else "Kapak işlemi tamamlanamadı; mevcut kapak korundu."
                update { it.copy(completed = it.completed + 1, failures = it.failures + (work.entry.title to message)) }
            }
            gap = work.cancelled
            if (work.cancelled.count == 0L) update { it.copy(cancelled = true) }
        }
    }
    @Synchronized private fun update(change: (Progress) -> Progress) { progress = change(progress!!) }
    @Synchronized fun cancel() { stop.countDown(); active?.cancelled?.countDown(); pending.forEach { it.cancelled.countDown() } }
    @Synchronized fun clear() { if (progress?.running != true) progress = null }
}
