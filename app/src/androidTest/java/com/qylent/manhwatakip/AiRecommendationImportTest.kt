package com.qylent.manhwatakip

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import org.junit.Assert.*
import org.junit.Test

class AiRecommendationImportTest {
    private val app get() = ApplicationProvider.getApplicationContext<LibraryApplication>()
    private val idle = ScreenIdle()
    @org.junit.Before fun before() { androidx.test.espresso.IdlingRegistry.getInstance().register(idle) }
    @org.junit.After fun after() { androidx.test.espresso.IdlingRegistry.getInstance().unregister(idle); idle.close() }

    @Test fun localApprovalDefersBehindBusyQueueAndSkipsChangedDeletedAndCoveredNewIds() {
        assertTrue(app.bulkCovers.progress?.running != true)
        val tasks = mutableListOf<Runnable>()
        var oldSearches = 0
        app.bulkCovers.start(listOf(Series(title = "Unrelated active job")), java.util.concurrent.Executor { tasks.add(it) },
            { oldSearches++; CoverResult("", "", byteArrayOf()) }, { _, _ -> })
        val suffix = System.nanoTime().toString()
        val names = listOf("Changed $suffix", "Deleted $suffix", "Covered $suffix")
        val ids = mutableListOf<String>()
        try {
            ActivityScenario.launch(ChatGptActivity::class.java).use { scenario ->
                val json = org.json.JSONArray().also { rows -> names.forEach { rows.put(org.json.JSONObject().put("title", it)) } }.toString()
                onView(withHint("ChatGPT JSON'unu yapıştır")).perform(scrollTo(), replaceText(json), closeSoftKeyboard())
                onView(withText("Yapıştırılan JSON'u önizle")).perform(scrollTo(), click())
                onView(withText("Yeni serilerin eksik kapaklarını ara ve otomatik kaydet")).inRoot(isDialog()).perform(click())
                scenario.recreate()
                onView(withText("Onayla ve içe aktar")).inRoot(isDialog()).perform(click())
                onView(withText(org.hamcrest.Matchers.containsString("Yeni serilerin kapak araması sıraya alındı."))).perform(scrollTo()).check(matches(isDisplayed()))
                val added = names.map { name -> app.store.all().single { it.title == name } }; ids.addAll(added.map { it.id })
                assertEquals(4, app.bulkCovers.progress!!.total)
                scenario.recreate()
                assertEquals(4, app.bulkCovers.progress!!.total)
                val changed = app.store.save(added[0].copy(notes = "Edited while waiting"))
                app.store.delete(added[1])
                app.covers.save(added[2].id, fixtureJpeg())
                val stored = app.covers.bytes(added[2].id)
                tasks.single().run(); tasks.clear()
                assertEquals(1, oldSearches); assertEquals(4, app.bulkCovers.progress!!.completed)
                assertEquals(3, app.bulkCovers.progress!!.skipped); assertEquals(1, app.bulkCovers.progress!!.saved)
                assertEquals(changed, app.store.get(added[0].id)); assertNull(app.store.get(added[1].id))
                assertArrayEquals(stored, app.covers.bytes(added[2].id))
            }
        } finally {
            app.bulkCovers.cancel(); tasks.forEach { it.run() }; app.bulkCovers.clear()
            ids.forEach { id -> app.covers.remove(id); app.store.get(id)?.let(app.store::delete) }
        }
    }

    private fun fixtureJpeg(): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(8, 12, android.graphics.Bitmap.Config.ARGB_8888)
        return try { java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray() }
        finally { bitmap.recycle() }
    }

    @Test fun optionalCoverFailureKeepsImportedSeriesAndLateUserCoverIsPreserved() {
        val database = "recommendation-${System.nanoTime()}.db"
        var id: String? = null
        try {
            LibraryStore(app, database).use { store ->
                val preview = ChatGptTransfer.preview("""{"title":"Cover fixture"}""", emptyList())
                val applied = store.applyTransfer(preview.changes); val fresh = applied.single(); id = fresh.id
                val queue = BulkCoverQueue()
                RecommendationCovers().start(true, preview, applied, queue, java.util.concurrent.Executor { it.run() },
                    { throw IllegalArgumentException("Fixture offline") }, { expected, result -> store.applyCover(expected, result, app.covers) })
                assertEquals(fresh, store.get(fresh.id)); assertEquals(1, queue.progress!!.failures.size)
                val image = android.graphics.Bitmap.createBitmap(8, 12, android.graphics.Bitmap.Config.ARGB_8888)
                val bytes = java.io.ByteArrayOutputStream().also { image.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
                image.recycle()
                var userEdit: Series? = null
                var storedUserCover: ByteArray? = null
                RecommendationCovers().start(true, preview, applied, queue, java.util.concurrent.Executor { it.run() }, {
                    userEdit = store.save(fresh.copy(notes = "User changed record", coverUrl = "https://example.com/user.jpg"))
                    app.covers.save(fresh.id, bytes)
                    storedUserCover = app.covers.bytes(fresh.id)
                    CoverResult("https://example.com/read", "https://example.com/ai.jpg", bytes)
                }, { expected, result -> store.applyCover(expected, result, app.covers) })
                assertEquals(userEdit, store.get(fresh.id)); assertArrayEquals(storedUserCover, app.covers.bytes(fresh.id))
                assertEquals(1, queue.progress!!.failures.size); assertEquals(0, queue.progress!!.saved)
                assertTrue(store.history().isEmpty())
            }
        } finally { id?.let(app.covers::remove); app.deleteDatabase(database) }
    }

    @Test fun signedPcImportRequiresPhoneApprovalSurvivesRotationAndRejectsStaleBatch() {
        require(android.os.Build.HARDWARE == "ranchu") { "EMULATOR_ONLY" }
        val vault = EncryptedPreferences(app, "bridge_settings")
        val oldPreferences = vault.read()
        val fixtureKey = "task4-synthetic-protocol-fixture"
        val original = app.store.all()
        val row = app.store.save(Series(title = "PC fixture ${System.nanoTime()}", notes = "Keep", lastChapter = "8"))
        val title = "PC new ${System.nanoTime()}"
        val input = """[{"id":"${row.id}","lastChapter":"9"},{"title":"$title"}]"""
        val coverTasks = mutableListOf<Runnable>()
        var oldSearches = 0
        try {
            vault.write(org.json.JSONObject().put("key", fixtureKey))
            // Cancel, reject an intervening edit atomically, then approve a fresh preview.
            repeat(3) { attempt ->
                if (attempt == 2) {
                    assertTrue(app.bulkCovers.progress?.running != true)
                    app.bulkCovers.start(listOf(Series(title = "Unrelated PC queue")), java.util.concurrent.Executor { coverTasks.add(it) },
                        { oldSearches++; CoverResult("", "", byteArrayOf()) }, { _, _ -> })
                }
                val operation = java.util.UUID.randomUUID().toString()
                val job = org.json.JSONObject().put("id", operation).put("action", "ai_import").put("text", input).toString()
                val server = java.net.ServerSocket().apply { reuseAddress = true; soTimeout = 30000; bind(java.net.InetSocketAddress("127.0.0.1", 8767), 4) }
                val result = java.util.concurrent.CompletableFuture<org.json.JSONObject>()
                val worker = Thread {
                    try {
                        repeat(2) { index ->
                            server.accept().use { socket ->
                                socket.soTimeout = 15000
                                val stream = socket.getInputStream().buffered()
                                fun line(): String {
                                    val bytes = java.io.ByteArrayOutputStream()
                                    while (true) { val byte = stream.read(); require(byte >= 0); if (byte == 10) break; if (byte != 13) bytes.write(byte) }
                                    return bytes.toString("UTF-8")
                                }
                                val request = line()
                                val headers = mutableMapOf<String, String>()
                                while (true) {
                                    val header = line()
                                    if (header.isEmpty()) break
                                    headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                                }
                                val path = if (index == 0) "/job/$operation" else "/result/$operation"
                                val method = if (index == 0) "GET" else "POST"
                                assertTrue(request.startsWith("$method $path "))
                                val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
                                var count = 0
                                while (count < body.size) { val read = stream.read(body, count, body.size - count); require(read > 0); count += read }
                                val text = body.toString(Charsets.UTF_8)
                                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
                                assertEquals("Manhwa-HMAC ${mac(fixtureKey, "request\n$method\n$path\n$hash")}", headers["authorization"])
                                val response = if (index == 0) org.json.JSONObject().put("payload", job).put("signature", mac(fixtureKey, "response\n$job")).toString()
                                    else { result.complete(org.json.JSONObject(text)); "{}" }
                                val bytes = response.toByteArray()
                                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes)
                            }
                        }
                    } catch (error: Throwable) { result.completeExceptionally(error) }
                }.apply { start() }
                try {
                    val intent = android.content.Intent(app, DesktopTransferActivity::class.java).putExtra("operation", operation)
                    ActivityScenario.launch<DesktopTransferActivity>(intent).use { scenario ->
                        // USB work is asynchronous and intentionally not an Espresso busy resource during approval.
                        val deadline = System.currentTimeMillis() + 15000
                        var ready = false
                        while (!ready && System.currentTimeMillis() < deadline) {
                            scenario.onActivity { activity -> ready = androidx.lifecycle.ViewModelProvider(activity)[DesktopTransferActivity.State::class.java].importApproval.value != null }
                            if (!ready) Thread.sleep(30)
                        }
                        assertTrue("Phone preview must open", ready)
                        assertEquals("8", app.store.get(row.id)!!.lastChapter)
                        assertTrue(app.store.all().none { it.title == title })
                        scenario.recreate()
                        onView(withText("AI içe aktarma önizlemesi")).inRoot(isDialog()).check(matches(isDisplayed()))
                        if (attempt == 2) {
                            onView(withText("Yeni serilerin eksik kapaklarını ara ve otomatik kaydet")).inRoot(isDialog()).perform(click())
                            scenario.recreate()
                            onView(withText("Yeni serilerin eksik kapaklarını ara ve otomatik kaydet")).inRoot(isDialog()).check(matches(isChecked()))
                        }
                        if (attempt == 1) app.store.save(app.store.get(row.id)!!.copy(notes = "Changed while approving"))
                        onView(withText(if (attempt == 0) "Vazgeç" else "Onayla ve içe aktar")).inRoot(isDialog()).perform(click())
                        val response = result.get(30, java.util.concurrent.TimeUnit.SECONDS)
                        assertEquals(attempt == 2, response.getBoolean("ok"))
                        app.executor.submit {}.get(30, java.util.concurrent.TimeUnit.SECONDS)
                        if (attempt < 2) { assertEquals("8", app.store.get(row.id)!!.lastChapter); assertTrue(app.store.all().none { it.title == title }) }
                        else {
                            assertEquals("9", app.store.get(row.id)!!.lastChapter)
                            assertEquals("Changed while approving", app.store.get(row.id)!!.notes)
                            val fresh = app.store.all().single { it.title == title }
                            assertEquals(ReadingStatus.PLANNED, fresh.status); assertEquals("", fresh.lastChapter)
                            assertTrue(response.getString("warning").contains("kapak araması sıraya alındı"))
                            assertEquals(2, app.bulkCovers.progress!!.total) // Update row is not added to cover work.
                            app.covers.save(fresh.id, fixtureJpeg())
                            coverTasks.single().run(); coverTasks.clear()
                            assertEquals(1, oldSearches); assertEquals(1, app.bulkCovers.progress!!.skipped)
                            assertEquals(2, app.bulkCovers.progress!!.completed)
                        }
                    }
                } finally { server.close(); worker.join(5000) }
            }
            assertEquals(original, app.store.all().filter { it.id != row.id && it.title != title })
        } finally {
            app.bulkCovers.cancel(); coverTasks.forEach { it.run() }; app.bulkCovers.clear()
            vault.write(oldPreferences)
            app.store.all().filter { it.id == row.id || it.title == title }.forEach { app.covers.remove(it.id); app.store.delete(it) }
        }
    }

    private fun mac(key: String, text: String): String = javax.crypto.Mac.getInstance("HmacSHA256").run {
        init(javax.crypto.spec.SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        doFinal(text.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    @Test fun cancelledAndRotatedPreviewDoesNotStartCoversAndDefaultApprovalAddsPlannedOnly() {
        val title = "Recommendation ${System.nanoTime()}"
        val before = app.store.all()
        val queueBefore = app.bulkCovers.progress
        try {
            ActivityScenario.launch(ChatGptActivity::class.java).use { scenario ->
                onView(withHint("ChatGPT JSON'unu yapıştır")).perform(scrollTo(), replaceText("""{"title":"$title"}"""), closeSoftKeyboard())
                onView(withText("Yapıştırılan JSON'u önizle")).perform(scrollTo(), click())
                onView(withText("Yeni serilerin eksik kapaklarını ara ve otomatik kaydet")).inRoot(isDialog()).check(matches(isNotChecked()))
                onView(withText("Yeni serilerin eksik kapaklarını ara ve otomatik kaydet")).perform(click())
                scenario.recreate()
                onView(withText("Yeni serilerin eksik kapaklarını ara ve otomatik kaydet")).inRoot(isDialog()).check(matches(isChecked()))
                assertEquals(before, app.store.all()); assertEquals(queueBefore, app.bulkCovers.progress)
                onView(withText("İptal")).inRoot(isDialog()).perform(click())
                assertEquals(before, app.store.all()); assertEquals(queueBefore, app.bulkCovers.progress)
                onView(withText("Yapıştırılan JSON'u önizle")).perform(scrollTo(), click())
                onView(withText("Yeni serilerin eksik kapaklarını ara ve otomatik kaydet")).inRoot(isDialog()).check(matches(isNotChecked()))
                onView(withText("Onayla ve içe aktar")).inRoot(isDialog()).perform(click())
                onView(withText("1 seri değişikliği uygulandı.")).perform(scrollTo()).check(matches(isDisplayed()))
                scenario.recreate()
                val added = app.store.all().single { it.title == title }
                assertEquals(ReadingStatus.PLANNED, added.status); assertEquals("", added.lastChapter)
                assertEquals(queueBefore, app.bulkCovers.progress)
                assertTrue(app.store.history().none { it.seriesId == added.id })
            }
        } finally { app.store.all().filter { it.title == title }.forEach(app.store::delete) }
    }
}
