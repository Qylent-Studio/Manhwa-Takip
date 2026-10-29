package com.qylent.manhwatakip

import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class DesktopUsbAcceptanceTest {
    @Test fun temporaryManualCoverAppliesWithoutAiApproval() {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("manualCoverAcceptance") == "true")
        require(android.os.Build.HARDWARE == "ranchu" || android.os.Build.FINGERPRINT.contains("generic")) { "EMULATOR_ONLY" }
        val context = instrumentation.targetContext; val app = context.applicationContext as LibraryApplication
        val id = requireNotNull(args.getString("fixtureId")); require(java.util.UUID.fromString(id).toString() == id)
        val operation = requireNotNull(args.getString("operationId")); val fixtureKey = requireNotNull(args.getString("fixtureKey"))
        val vault = EncryptedPreferences(context, "bridge_settings"); val oldPreferences = vault.read()
        val original = app.store.all(); val originalChats = app.chats.list()
        require(app.store.get(id) == null)
        try {
            vault.write(JSONObject().put("key", fixtureKey))
            val row = app.store.save(Series(id = id, title = "USB kapak $id", notes = "Kalsın", lastChapter = "1", coverUrl = "https://okutoon.com/old.jpg", createdAt = 1, updatedAt = 1))
            val bitmap = android.graphics.Bitmap.createBitmap(8, 12, android.graphics.Bitmap.Config.ARGB_8888)
            val oldImage = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray(); bitmap.recycle()
            app.covers.save(id, oldImage)
            ActivityScenario.launch<DesktopTransferActivity>(Intent(context, DesktopTransferActivity::class.java).putExtra("operation", operation)).use {
                val deadline = System.currentTimeMillis() + 30_000
                while (app.store.get(id)?.coverUrl?.startsWith("https://manhwa-takip.invalid/local-cover/") != true && System.currentTimeMillis() < deadline) Thread.sleep(100)
                val saved = app.store.get(id)!!
                assertTrue(saved.coverUrl.startsWith("https://manhwa-takip.invalid/local-cover/"))
                assertEquals(row.copy(coverUrl = saved.coverUrl, updatedAt = saved.updatedAt), saved)
                assertFalse(oldImage.contentEquals(app.covers.bytes(id)!!)); assertTrue(app.store.history(id).isEmpty())
                app.executor.submit {}.get(30, java.util.concurrent.TimeUnit.SECONDS)
            }
            assertEquals(original, app.store.all().filter { it.id != id }); assertEquals(originalChats, app.chats.list())
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "MANUAL_COVER_SIGNED_ROUNDTRIP_NO_SECOND_APPROVAL_OK\n") })
        } finally {
            app.covers.remove(id); app.store.get(id)?.let(app.store::delete)
            context.openOrCreateDatabase("library.db", 0, null).use { db -> db.delete("deleted_series", "id = ?", arrayOf(id)) }
            vault.write(oldPreferences)
        }
    }

    @Test fun temporaryUsbEditKeepsUserLibraryAndAiApproval() {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("companionUsbAcceptance") == "true")
        val context = instrumentation.targetContext; val app = context.applicationContext as LibraryApplication
        val id = requireNotNull(args.getString("fixtureId")); require(java.util.UUID.fromString(id).toString() == id)
        val operation = requireNotNull(args.getString("operationId"))
        val fixtureKey = args.getString("fixtureKey")
        val vault = EncryptedPreferences(context, "bridge_settings"); val oldPreferences = vault.read()
        val original = app.store.all(); val originalChats = app.chats.list(); val pending = BridgePreferences(context).pending()
        require(app.store.get(id) == null)
        try {
            if (fixtureKey != null) {
                require(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) { "PHYSICAL_KEY_OVERRIDE_REFUSED" }
                vault.write(JSONObject().put("key", fixtureKey))
            }
            app.store.save(Series(id = id, title = "USB geçici test $id", notes = "Geçici", lastChapter = "1", createdAt = 1, updatedAt = 1))
            val intent = Intent(context, DesktopTransferActivity::class.java).putExtra("operation", operation)
            ActivityScenario.launch<DesktopTransferActivity>(intent).use {
                val deadline = System.currentTimeMillis() + 30_000
                while (app.store.get(id)?.lastChapter != "9" && System.currentTimeMillis() < deadline) Thread.sleep(100)
                assertEquals("9", app.store.get(id)!!.lastChapter); assertEquals("USB kabul", app.store.get(id)!!.notes)
                assertTrue(app.store.get(id)!!.favorite); assertEquals(1, app.store.history(id).size)
                // Export runs on the same executor after the save; wait for result delivery before cleaning the fixture.
                app.executor.submit {}.get(30, java.util.concurrent.TimeUnit.SECONDS)
            }
            assertEquals(original, app.store.all().filter { it.id != id }); assertEquals(originalChats, app.chats.list())
            if (fixtureKey == null) assertEquals(pending, BridgePreferences(context).pending())
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "COMPANION_USB_TEMPORARY_EDIT_OK\nPERSONAL_LIBRARY_UNCHANGED\n") })
        } finally {
            app.store.get(id)?.let(app.store::delete)
            context.openOrCreateDatabase("library.db", 0, null).use { db ->
                db.delete("reading_history", "series_id = ?", arrayOf(id)); db.delete("deleted_series", "id = ?", arrayOf(id))
            }
            if (fixtureKey != null) vault.write(oldPreferences)
        }
    }
}
