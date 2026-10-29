package com.qylent.manhwatakip

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BulkCoverQueueTest {
    private val cover = CoverResult("https://trmanga.com/webtoon/test", "https://cdn.trmanga.com/test.jpg", byteArrayOf(1))

    @Test fun existingCoversNeverReachSearchOrSaveAndDoNotWait() {
        val queue = BulkCoverQueue(); val started = System.nanoTime()
        queue.start((1..3).map { Series(title = "Kapaklı $it") }, java.util.concurrent.Executor { it.run() },
            { fail("Kapaklı seri için arama/AI çağrılmamalı"); cover }, { _, _ -> fail("Eski kapak değişmemeli") }, hasCover = { true })
        assertEquals(3, queue.progress!!.skipped); assertEquals(3, queue.progress!!.completed)
        assertEquals(0, queue.progress!!.saved); assertTrue(queue.progress!!.failures.isEmpty())
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000)
    }

    @Test fun coverAddedWhileQueueRunsIsSkippedBeforeAnySearch() {
        val queue = BulkCoverQueue(); val first = Series(title = "Eksik"); val second = Series(title = "Sonradan gelen")
        val covered = mutableSetOf<String>(); val searched = mutableListOf<String>()
        queue.start(listOf(first, second), java.util.concurrent.Executor { it.run() },
            { searched.add(it.id); cover }, { _, _ -> covered.add(second.id) }, hasCover = { it.id in covered })
        assertEquals(listOf(first.id), searched); assertEquals(1, queue.progress!!.saved)
        assertEquals(1, queue.progress!!.skipped); assertEquals(2, queue.progress!!.completed)
    }

    @Test fun savesBeforeNextSearchAndWaitsTwoSeconds() {
        val queue = BulkCoverQueue()
        val first = Series(title = "Bir"); val second = Series(title = "İki")
        val events = mutableListOf<String>(); var savedAt = 0L
        assertTrue(queue.start(listOf(first, second), java.util.concurrent.Executor { it.run() }, { entry ->
            if (entry == second) assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - savedAt) >= 1950)
            events.add("find:${entry.title}"); cover
        }, { entry, _ -> events.add("save:${entry.title}"); savedAt = System.nanoTime() }))
        assertEquals(listOf("find:Bir", "save:Bir", "find:İki", "save:İki"), events)
        assertEquals(2, queue.progress!!.saved); assertFalse(queue.progress!!.running)
    }

    @Test fun failedItemIsNotSavedAndDoesNotStopRemainingItems() {
        val queue = BulkCoverQueue(); val saved = mutableListOf<String>()
        queue.start(listOf(Series(title = "Hata"), Series(title = "Başarılı")), java.util.concurrent.Executor { it.run() }, {
            if (it.title == "Hata") throw IllegalArgumentException("Kapak bulunamadı")
            cover
        }, { entry, _ -> saved.add(entry.title) })
        assertEquals(listOf("Başarılı"), saved)
        assertEquals(2, queue.progress!!.completed); assertEquals(1, queue.progress!!.failures.size)
        assertEquals("Kapak bulunamadı", queue.progress!!.failures.first().second)
    }

    @Test fun cancellationDuringSearchPreventsSaveAndDuplicateQueue() {
        val worker = Executors.newSingleThreadExecutor()
        val queue = BulkCoverQueue(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val done = CountDownLatch(1); var saved = false
        try {
            assertTrue(queue.start(listOf(Series(title = "Bir"), Series(title = "İki")), worker, {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); cover
            }, { _, _ -> saved = true }))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(queue.start(listOf(Series(title = "Üç")), worker, { cover }, { _, _ -> fail() }))
            queue.cancel(); release.countDown(); worker.execute { done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS)); assertFalse(saved)
            assertTrue(queue.progress!!.cancelled); assertFalse(queue.progress!!.running)
            assertEquals(0, queue.progress!!.completed)
        } finally { release.countDown(); worker.shutdownNow() }
    }

    @Test fun cancellationDuringGapKeepsSavedCoverAndSkipsNextItem() {
        val queue = BulkCoverQueue(); var searched = 0
        queue.start(listOf(Series(title = "Bir"), Series(title = "İki")), java.util.concurrent.Executor { it.run() }, {
            searched++; cover
        }, { _, _ -> queue.cancel() })
        assertEquals(1, searched); assertEquals(1, queue.progress!!.saved)
        assertEquals(1, queue.progress!!.completed); assertTrue(queue.progress!!.cancelled)
    }
}
