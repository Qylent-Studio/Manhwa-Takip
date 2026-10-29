package com.qylent.manhwatakip

import java.util.concurrent.Executor

/** One approval owns one optional queue submission; never derive work from the whole library. */
class RecommendationCovers {
    private var submitted = false
    @Synchronized fun start(enabled: Boolean, preview: ChatGptPreview, applied: List<Series>, queue: BulkCoverQueue,
        executor: Executor, find: (Series) -> CoverResult, save: (Series, CoverResult) -> Unit,
        hasCover: (Series) -> Boolean = { false }): Boolean {
        if (!enabled || submitted) return false
        submitted = true
        val ids = preview.changes.filter { it.before == null }.map { it.after.id }.toSet()
        val entries = applied.filter { it.id in ids }.distinctBy { it.id }
        return entries.isNotEmpty() && queue.enqueue(entries, executor, find, save, hasCover)
    }
}

/** Existing finder and conflict-safe cover writer. No manual-cover transport or raw cover writes. */
fun RecommendationCovers.start(app: LibraryApplication, preview: ChatGptPreview, applied: List<Series>, enabled: Boolean): Boolean =
    start(enabled, preview, applied, app.bulkCovers, app.networkExecutor, { entry ->
        val fetcher = CoverFetcher()
        AiCoverFinder(null, { source -> fetcher.fetch(source).also { CoverStore.decode(it.bytes).recycle() } }, fetcher::search)
            .find(entry.title, entry.url, entry.alternativeTitles.take(20))
    }, { entry, cover ->
        try { app.executor.submit<Series> { app.store.applyCover(entry, cover, app.covers) }.get() }
        catch (error: java.util.concurrent.ExecutionException) { throw (error.cause as? Exception ?: error) }
    }, { entry -> app.covers.contains(entry.id) || app.store.get(entry.id) != entry })
