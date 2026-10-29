package com.qylent.manhwatakip

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class BridgePairingSetupTest {
    @Test fun storeExistingPcPairingKeyThroughExplicitUsbSetup() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("bridgePairingSetup") == "true")
        val preferences = BridgePreferences(ApplicationProvider.getApplicationContext<Context>())
        check(preferences.key().isEmpty()) { "RESET_LOCAL_BRIDGE_SETTINGS_ON_PHONE_FIRST" }
        val nonce = args.getString("fixtureToken") ?: error("PAIRING_SETUP_SESSION_MISSING")
        val connection = URL("http://127.0.0.1:8766/key").openConnection() as HttpURLConnection
        val payload = try {
            connection.connectTimeout = 5000; connection.readTimeout = 5000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $nonce")
            check(connection.responseCode == 200) { "PAIRING_SETUP_SESSION_REJECTED" }
            connection.inputStream.use { input ->
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(bytes.size() + count <= 8192) { "PAIRING_SETUP_RESPONSE_TOO_LARGE" }
                    bytes.write(buffer, 0, count)
                }
                bytes.toString("UTF-8")
            }
        } finally { connection.disconnect() }
        val key = JSONObject(payload).getString("pairingKey")
        preferences.saveKey(key) // Same production validation/encrypted storage; never clears existing pairing.
        check(preferences.key() == key) { "PAIRING_STORE_READBACK_FAILED" }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "PAIRING_SAVED_ENCRYPTED_OK\n")
        })
    }
}
