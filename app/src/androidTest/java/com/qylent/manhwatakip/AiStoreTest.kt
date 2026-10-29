package com.qylent.manhwatakip

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Test

class AiStoreTest {
    @Test fun httpsSettingsRoundTripRequiresNonblankKey() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AiSecretStore(context, "local-ai-test-${System.nanoTime()}")
        try {
            val settings = AiSettings("https://example.invalid/v1", "fixture-model")
            store.save(settings, "fixture-key")
            assertEquals(settings, store.settings())
            assertEquals("fixture-key", store.connection()!!.key)
            assertFalse(store.consented())
            store.consent(settings)
            assertTrue(store.consented())
            assertThrows(IllegalArgumentException::class.java) { store.save(AiSettings("https://example.com/v1", "test"), "") }
            assertThrows(IllegalArgumentException::class.java) { store.save(settings, "   ") }
            assertEquals(settings, store.settings())
        } finally { store.clear() }
    }
    @Test fun schemaOneUpgradeKeepsExistingRecordsAndAddsEmptyOutbox() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${System.nanoTime()}.db"
        val before = Series(title = "Eski kütüphane", notes = "Eski sürüm notu", lastChapter = "15")
        LibraryStore(context, name).use { it.save(before) }
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use {
            it.execSQL("DROP TABLE bridge_decisions"); it.execSQL("DROP TABLE deleted_series"); it.version = 1
        }
        LibraryStore(context, name).use {
            assertEquals(before, it.get(before.id))
            assertEquals(emptyMap<String, String>(), it.bridgeDecisions())
        }
        context.deleteDatabase(name)
    }
    @Test fun schemaTwoUpgradePreservesLibraryAndUnsentDecisions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-two-${System.nanoTime()}.db"
        val before = Series(title = "Sürüm iki testi", notes = "Korunacak")
        LibraryStore(context, name).use { it.save(before); it.recordBridgeRejection("pending-decision") }
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use {
            it.execSQL("DROP TABLE deleted_series"); it.version = 2
        }
        LibraryStore(context, name).use {
            assertEquals(before, it.get(before.id))
            assertEquals(mapOf("pending-decision" to "rejected"), it.bridgeDecisions())
            it.delete(before)
            assertThrows(IllegalArgumentException::class.java) { it.save(before) }
        }
        context.deleteDatabase(name)
    }

    @Test fun mcpDecisionIsAtomicWithLibraryWriteAndSurvivesReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "mcp-outbox-${System.nanoTime()}.db"
        lateinit var before: Series
        LibraryStore(context, name).use { store ->
            before = store.save(Series(title = "Outbox"))
            store.applyProposal(AiProposal(before, before.copy(lastChapter = "20")), "approved-token")
            assertEquals(mapOf("approved-token" to "approved"), store.bridgeDecisions())
            for (index in 1..199) store.recordBridgeRejection("reject-$index")
            val fresh = store.get(before.id)!!
            assertThrows(IllegalArgumentException::class.java) { store.applyProposal(AiProposal(fresh, fresh.copy(lastChapter = "30")), "overflow") }
            assertEquals("20", store.get(before.id)!!.lastChapter)
            assertFalse(store.bridgeDecisions().containsKey("overflow"))
        }
        LibraryStore(context, name).use { store ->
            assertEquals("approved", store.bridgeDecisions()["approved-token"])
            store.ackBridgeDecisions(mapOf("approved-token" to "approved"))
            assertFalse(store.bridgeDecisions().containsKey("approved-token"))
            assertEquals("20", store.get(before.id)!!.lastChapter)
        }
        context.deleteDatabase(name)
    }
    @Test fun approvalRejectsConcurrentEditAndDuplicateNewRecord() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "ai-${System.nanoTime()}.db"
        LibraryStore(context, name).use { store ->
            val before = store.save(Series(title = "Eski", notes = "Korunmalı"))
            val proposal = AiProposal(before, before.copy(lastChapter = "20"))
            assertEquals("", store.get(before.id)!!.lastChapter)
            store.save(before.copy(notes = "Yeni not"))
            assertThrows(IllegalArgumentException::class.java) { store.applyProposal(proposal) }
            assertEquals("Yeni not", store.get(before.id)!!.notes)
            assertEquals("", store.get(before.id)!!.lastChapter)
            val fresh = store.get(before.id)!!
            assertEquals("20", store.applyProposal(AiProposal(fresh, fresh.copy(lastChapter = "20"))).lastChapter)
            assertThrows(IllegalArgumentException::class.java) { store.applyProposal(AiProposal(null, Series(title = "Eski"))) }
            assertEquals(1, store.all().size)
        }
        context.deleteDatabase(name)
    }

    @Test fun encryptedSettingsRoundTripAndBackupExcludesCredentials() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = AiSecretStore(context, "ai-test-${System.nanoTime()}")
        settings.save(AiSettings("https://example.com/v1", "test"), "test-key-local-only")
        settings.consent(AiSettings("https://example.com/v1", "test"))
        val originalConnection = settings.connection()!!
        settings.save(AiSettings("https://second.example.com/v1", "other"), "second-test-key-local-only")
        assertFalse(settings.consented())
        assertThrows(IllegalArgumentException::class.java) { settings.consent(originalConnection.settings) }
        assertEquals("https://example.com/v1", originalConnection.settings.baseUrl)
        assertEquals("test-key-local-only", originalConnection.key)
        assertFalse(originalConnection.toString().contains("test-key-local-only"))
        settings.save(AiSettings("https://example.com/v1", "test"), "test-key-local-only")
        assertEquals("test-key-local-only", settings.key())
        val prefs = context.getSharedPreferences(settings.preferencesName, Context.MODE_PRIVATE)
        assertFalse(prefs.all.values.any { it.toString().contains("test-key-local-only") })
        assertFalse(BackupCodec.encode(listOf(Series(title = "A"))).contains("test-key-local-only"))
        settings.clear()
        assertEquals("", settings.key())
    }
}
