package com.qylent.manhwatakip

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class RecommendationCoversTest {
    @Test fun newApprovalAfterStopRequestIsRetainedWhileCancelledWorkerDrains() {
        val tasks = mutableListOf<Runnable>(); val executor = Executor { tasks.add(it) }
        val queue = BulkCoverQueue(); val old = Series(title = "Cancelled"); val fresh = Series(title = "Approved after stop")
        queue.start(listOf(old), executor, { error("cancelled work must not run") }, { _, _ -> fail() })
        queue.cancel()
        val searched = mutableListOf<String>()
        val preview = ChatGptPreview(listOf(AiProposal(null, fresh)), 0)
        assertTrue(RecommendationCovers().start(true, preview, listOf(fresh), queue, executor,
            { searched.add(it.id); CoverResult("", "", byteArrayOf()) }, { _, _ -> }))
        tasks.single().run()
        assertEquals(listOf(fresh.id), searched); assertEquals(1, queue.progress!!.saved)
        assertFalse(queue.progress!!.running)
    }
    @Test fun appendingDuringActiveDiscoveryPreservesTotalsAndCompletesBothJobs() {
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val old = Series(title = "Active"); val fresh = Series(title = "Queued")
        val searched = java.util.Collections.synchronizedList(mutableListOf<String>())
        val result = CoverResult("", "", byteArrayOf()); val queue = BulkCoverQueue()
        try {
            queue.start(listOf(old), worker, {
                searched.add(it.id); entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); result
            }, { _, _ -> })
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val preview = ChatGptPreview(listOf(AiProposal(null, fresh)), 0)
            assertTrue(RecommendationCovers().start(true, preview, listOf(fresh), queue, worker,
                { searched.add(it.id); result }, { _, _ -> }))
            release.countDown()
            worker.submit {}.get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(listOf(old.id, fresh.id), searched)
            assertEquals(2, queue.progress!!.total); assertEquals(2, queue.progress!!.completed); assertEquals(2, queue.progress!!.saved)
            assertFalse(queue.progress!!.running)
        } finally { release.countDown(); queue.cancel(); worker.shutdownNow() }
    }
    @Test fun busyQueueRetainsApprovedNewIdsAndRunsThemOnceAfterUnrelatedWork() {
        val old = Series(title = "Unrelated")
        val fresh = Series(title = "Approved new")
        val update = Series(title = "Existing update")
        val tasks = mutableListOf<Runnable>(); val executor = Executor { tasks.add(it) }
        val queue = BulkCoverQueue(); val searched = mutableListOf<String>()
        val result = CoverResult("", "", byteArrayOf())
        queue.start(listOf(old), executor, { searched.add(it.id); result }, { _, _ -> })
        val request = RecommendationCovers()
        val preview = ChatGptPreview(listOf(AiProposal(null, fresh), AiProposal(update, update.copy(notes = "Changed"))), 0)
        assertTrue("Busy queue must accept and retain approved work", request.start(true, preview, listOf(fresh, update), queue, executor,
            { searched.add(it.id); result }, { _, _ -> }))
        assertFalse(request.start(true, preview, listOf(fresh, update), queue, executor, { error("duplicate") }, { _, _ -> }))
        tasks.single().run()
        assertEquals(listOf(old.id, fresh.id), searched)
        assertEquals(2, queue.progress!!.completed); assertEquals(2, queue.progress!!.saved)
        assertFalse(request.start(true, preview, listOf(fresh, update), queue, executor, { error("duplicate") }, { _, _ -> }))
    }
    @Test fun cancellingBusyQueueCancelsDeferredApprovedWorkWithoutRepeatingOrSaving() {
        val tasks = mutableListOf<Runnable>(); val executor = Executor { tasks.add(it) }
        val queue = BulkCoverQueue(); val old = Series(title = "Old"); val fresh = Series(title = "New")
        queue.start(listOf(old), executor, { error("cancelled") }, { _, _ -> fail() })
        val request = RecommendationCovers(); val preview = ChatGptPreview(listOf(AiProposal(null, fresh)), 0)
        assertTrue(request.start(true, preview, listOf(fresh), queue, executor, { error("cancelled") }, { _, _ -> fail() }))
        queue.cancel(); tasks.single().run()
        assertTrue(queue.progress!!.cancelled); assertEquals(0, queue.progress!!.completed)
        assertFalse(request.start(true, preview, listOf(fresh), queue, executor, { error("duplicate") }, { _, _ -> fail() }))
    }
    @Test fun offDoesNotScheduleAndOnSchedulesApprovedNewIdsOnceEvenAfterCompletion() {
        val fresh = Series(title = "New")
        val old = Series(title = "Old", coverUrl = "https://example.com/old.jpg")
        val preview = ChatGptPreview(listOf(AiProposal(null, fresh), AiProposal(old, old.copy(notes = "Changed"))), 0)
        val tasks = mutableListOf<Runnable>(); val executor = Executor { tasks.add(it) }
        var calls = 0; val saved = mutableListOf<String>()
        val queue = BulkCoverQueue()
        val request = RecommendationCovers()
        val find: (Series) -> CoverResult = { calls++; CoverResult("", "", byteArrayOf()) }
        val save: (Series, CoverResult) -> Unit = { entry, _ -> saved.add(entry.id) }
        assertFalse(request.start(false, preview, listOf(fresh, old), queue, executor, find, save))
        assertEquals(0, calls); assertTrue(tasks.isEmpty())
        assertTrue(request.start(true, preview, listOf(fresh, old), queue, executor, find, save))
        assertFalse(request.start(true, preview, listOf(fresh, old), queue, executor, find, save))
        tasks.single().run()
        assertEquals(1, calls); assertEquals(listOf(fresh.id), saved)
        assertFalse(request.start(true, preview, listOf(fresh, old), queue, executor, find, save))
    }
    @Test fun failedCoverLeavesImportedSeriesAndExistingCoversUntouchedAndCancelDoesNotRepeat() {
        val fresh = Series(title = "New")
        val preview = ChatGptPreview(listOf(AiProposal(null, fresh)), 0)
        val queue = BulkCoverQueue(); val tasks = mutableListOf<Runnable>()
        var saved = 0; var calls = 0
        val request = RecommendationCovers()
        request.start(true, preview, listOf(fresh), queue, Executor { tasks.add(it) }, { calls++; error("offline") }, { _, _ -> saved++ })
        tasks.single().run()
        assertEquals(1, calls); assertEquals(0, saved); assertEquals(1, queue.progress!!.failures.size)
        assertEquals(ReadingStatus.PLANNED, fresh.status)
        val cancelled = RecommendationCovers(); tasks.clear()
        cancelled.start(true, preview, listOf(fresh), queue, Executor { tasks.add(it) }, { calls++; error("unexpected") }, { _, _ -> saved++ })
        queue.cancel(); tasks.single().run()
        assertEquals(1, calls); assertEquals(0, saved)
    }
}
