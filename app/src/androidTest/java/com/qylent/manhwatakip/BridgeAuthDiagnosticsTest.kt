package com.qylent.manhwatakip

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class BridgeAuthDiagnosticsTest {
    @Test fun savedPhoneKeyAuthenticatesWithoutChangingBridgeState() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("bridgeAuthDiagnostics") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = BridgePreferences(context).key()
        require(key.matches(Regex("[A-Za-z0-9_-]{32,128}"))) { "PHONE_BRIDGE_KEY_NOT_CONFIGURED" }
        val connection = URL("http://127.0.0.1:8765/sync").openConnection() as HttpURLConnection
        val status = try {
            connection.connectTimeout = 5000
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer $key")
            // Invalid input tests authentication before payload validation; it cannot replace a snapshot.
            connection.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
            connection.responseCode
        } catch (error: Exception) {
            throw AssertionError("PHONE_BRIDGE_AUTH_TRANSPORT_ERROR: ${error.javaClass.simpleName}")
        } finally { connection.disconnect() }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "PHONE_BRIDGE_AUTH_HTTP_$status\n")
        })
        assertEquals("Authenticated invalid input must be rejected without changing bridge state", 400, status)
    }
}
