package com.qylent.manhwatakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class BridgeRoundTripTest {
    @Test fun realUsbBridgeRequiresApprovalAndReturnsDecision() {
        assumeTrue("Yalnızca özel USB test sunucusuyla çalışır", InstrumentationRegistry.getArguments().getString("usbAcceptance") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = "usb-acceptance-${System.nanoTime()}.db"
        val client = BridgeClient()
        val key = "test-usb-only-not-a-real-pairing-key-1234567890"
        LibraryStore(context, database).use { store ->
            val before = store.save(Series(id = "00000000-0000-0000-0000-000000000042", title = "USB test", lastChapter = "10", notes = "Test notu", categories = listOf("Harem")))
            var response = client.sync(key, store.all(), emptyMap())
            val deadline = System.nanoTime() + 5_000_000_000L
            while (response.proposals.isEmpty() && System.nanoTime() < deadline) { Thread.sleep(100); response = client.sync(key, store.all(), emptyMap()) }
            val pending = response.proposals.single()
            assertEquals(before, pending.proposal.before)
            assertEquals("10", store.get(before.id)!!.lastChapter)
            assertEquals(listOf("Harem"), store.get(before.id)!!.categories)
            assertEquals("20", pending.proposal.after.lastChapter)
            assertEquals(listOf("Harem", "Macera"), pending.proposal.after.categories)
            val saved = store.applyProposal(pending.proposal, pending.token)
            assertEquals("Test notu", saved.notes)
            assertEquals(listOf("Harem", "Macera"), saved.categories)
            assertThrows(IllegalArgumentException::class.java) { store.applyProposal(pending.proposal) }
            assertEquals("20", store.get(before.id)!!.lastChapter)
            val decisions = store.bridgeDecisions()
            assertEquals(mapOf(pending.token to "approved"), decisions)
            val acknowledged = client.sync(key, store.all(), decisions)
            assertEquals(emptyList<BridgePending>(), acknowledged.proposals)
            store.ackBridgeDecisions(decisions)
            assertTrue(store.bridgeDecisions().isEmpty())
            client.disconnect(key)
        }
        context.deleteDatabase(database)
    }
}
