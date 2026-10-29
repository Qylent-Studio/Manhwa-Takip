package com.qylent.manhwatakip

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DesktopAuthTest {
    @Test fun rogueLoopbackNeverReceivesKeyAndForgedJobCannotMutate() {
        assumeTrue(android.os.Build.MODEL.contains("sdk"))
        val app = ApplicationProvider.getApplicationContext<LibraryApplication>()
        val vault = EncryptedPreferences(app, "bridge_settings"); val previous = vault.read()
        val key = "rogue-listener-synthetic-fixture-key-1234567"
        val entry = app.store.save(Series(title = "Sahte sunucu testi ${System.nanoTime()}", lastChapter = "1"))
        val operation = java.util.UUID.randomUUID().toString(); val headers = mutableListOf<String>(); val received = CountDownLatch(1)
        ServerSocket(8767, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 15_000
            val thread = Thread {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000; val reader = socket.getInputStream().bufferedReader()
                    while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; headers.add(line) }
                    val job = JSONObject().put("id", operation).put("action", "edit").put("changes", org.json.JSONArray().put(JSONObject()
                        .put("id", entry.id).put("expectedUpdatedAt", entry.updatedAt).put("fields", JSONObject().put("lastChapter", "99"))))
                    val bytes = JSONObject().put("payload", job.toString()).put("signature", "0".repeat(64)).toString().toByteArray()
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes)
                    received.countDown()
                }
            }.apply { isDaemon = true; start() }
            try {
                vault.write(JSONObject().put("key", key))
                ActivityScenario.launch<DesktopTransferActivity>(Intent(app, DesktopTransferActivity::class.java).putExtra("operation", operation)).use {
                    assertTrue(received.await(15, TimeUnit.SECONDS)); app.executor.submit {}.get(15, TimeUnit.SECONDS)
                    assertFalse("Raw pairing key sent to rogue listener", headers.any { it.contains(key) })
                    assertTrue(headers.any { it.startsWith("Authorization: Manhwa-HMAC ", ignoreCase = true) })
                    assertEquals(entry, app.store.get(entry.id))
                }
            } finally {
                vault.write(previous); app.store.get(entry.id)?.let(app.store::delete)
                app.openOrCreateDatabase("library.db", 0, null).use { it.delete("deleted_series", "id = ?", arrayOf(entry.id)) }
            }
        }
    }
}
