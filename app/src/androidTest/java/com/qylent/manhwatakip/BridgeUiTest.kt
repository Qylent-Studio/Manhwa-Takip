package com.qylent.manhwatakip

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import org.hamcrest.CoreMatchers.not
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import androidx.test.espresso.IdlingRegistry
import java.util.concurrent.FutureTask

class BridgeUiTest {
    private val idle = ScreenIdle()
    @Before fun setup() { TestDeviceSetup.prepare(); IdlingRegistry.getInstance().register(idle) }
    @After fun cleanup() { IdlingRegistry.getInstance().unregister(idle); idle.close() }
    @Test fun inFlightBridgeOperationKeepsNewScreenLocked() {
        val app = ApplicationProvider.getApplicationContext<Context>() as LibraryApplication
        val job = FutureTask<String> { "test complete" }; app.bridgePending = job
        try {
            ActivityScenario.launch(BridgeActivity::class.java).use {
                onView(withText("Bağlantıyı kapat")).check(matches(not(isEnabled())))
            }
            ActivityScenario.launch(BridgeActivity::class.java).use { scenario ->
                onView(withText("Bağlantıyı kapat")).check(matches(not(isEnabled())))
                scenario.recreate()
                onView(withText("Bağlantıyı aç / eşitle")).check(matches(not(isEnabled())))
            }
        } finally { job.cancel(true); app.bridgePending = null }
    }

    @Test fun persistedMcpProposalCanBeOpenedWithoutApplicationCache() {
        val app = ApplicationProvider.getApplicationContext<Context>() as LibraryApplication
        val before = app.store.save(Series(title = "MCP restore ${System.nanoTime()}", notes = "Not korunmalı"))
        val token = java.util.UUID.randomUUID().toString()
        val preferences = BridgePreferences(app)
        preferences.receive(BridgeResponse(1, listOf(BridgePending(token, AiProposal(before, before.copy(lastChapter = "8"))))), emptyMap())
        app.proposals.remove("mcp:$token")
        val intent = Intent(app, EditActivity::class.java).putExtra("proposalId", "mcp:$token")
        ActivityScenario.launch<EditActivity>(intent).use { scenario ->
            onView(withHint("Son okunan bölüm")).check(matches(withText("8")))
            onView(withHint("Son okunan bölüm")).perform(replaceText("9"), closeSoftKeyboard())
            scenario.recreate()
            onView(withHint("Son okunan bölüm")).check(matches(withText("9")))
            onView(withText("Öneriyi reddet")).perform(scrollTo(), click())
            Thread.sleep(300)
            assertEquals("", app.store.get(before.id)!!.lastChapter)
            assertEquals("rejected", app.store.bridgeDecisions()[token])
        }
        app.store.ackBridgeDecisions(mapOf(token to "rejected")); preferences.clearPending()
    }
}
