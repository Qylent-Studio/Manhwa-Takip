package com.qylent.manhwatakip

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in real provider check. Only synthetic records and a transient in-memory key. */
class AiLiveAcceptanceTest {
    @Test fun realProviderCandidatesPreferencesAndApproval() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveAiAcceptance") == "true")
        assumeTrue(Build.HARDWARE == "ranchu" || Build.FINGERPRINT.contains("generic"))
        val nonce = args.getString("fixtureToken") ?: error("Yerel test oturumu eksik.")
        fun fixture(stage: String? = null): String {
            val connection = URL("http://127.0.0.1:8766/${if (stage == null) "key" else "stage"}").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 5000; connection.readTimeout = 5000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Authorization", "Bearer $nonce")
                if (stage != null) {
                    connection.requestMethod = "POST"; connection.doOutput = true
                    connection.outputStream.use { it.write(stage.toByteArray(Charsets.UTF_8)) }
                }
                require(connection.responseCode == 200) { "Yerel test oturumu açılamadı." }
                return connection.inputStream.use {
                    val bytes = it.readBytes()
                    require(bytes.size <= 8192)
                    String(bytes, Charsets.UTF_8)
                }
            } finally { connection.disconnect() }
        }
        val key = JSONObject(fixture()).getString("apiKey")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = "live-ai-${System.nanoTime()}.db"
        val settings = AiSettings(AiSettings.LOCAL_BASE_URL, "Privite")
        val client = AiClient()
        try {
            LibraryStore(context, database).use { store ->
                val original = store.save(Series(title = "TEST Kule", lastChapter = "10", opinion = Opinion.LIKED,
                    likedReason = "Test tercihi: karakter gelişimi ve dünya inşası", dislikedReason = "Test tercihi: aşırı tekrar",
                    notes = "Uydurma kabul kaydı; kişisel veri değildir."))
                val update = client.chat(settings, key, "TEST Kule kaydımda son okunan bölümü 20 yap. Başka hiçbir alanı değiştirme.", store.all()).proposals.single()
                assertEquals(original, update.before)
                assertEquals("20", update.after.lastChapter)
                assertEquals(original, store.get(original.id)) // Receiving a real AI answer never writes.
                val approved = store.applyProposal(update)
                assertEquals("20", approved.lastChapter)
                assertEquals(original.notes, approved.notes)
                assertEquals(original.likedReason, approved.likedReason)
                assertThrows(IllegalArgumentException::class.java) { store.applyProposal(update) }
                fixture("AI_UPDATE_APPROVAL_OK")

                val rejected = client.chat(settings, key, "TEST Kule için yalnızca bölüm 30 önerisini hazırla; diğer alanları değiştirme.", store.all()).proposals.single()
                assertEquals(approved, rejected.before)
                assertEquals("30", rejected.after.lastChapter)
                assertEquals(approved, store.get(original.id)) // Discard the proposal; no approval call.
                fixture("AI_REJECT_NO_WRITE_OK")

                val candidates = client.chat(settings, key, "Yeni seri ekleme için iki ayrı doğrulanmamış aday hazırla: Solo Leveling ve Solo Leveling: Ragnarok. Her aday ayrı yeni kayıt olsun; bilgilerinin kesin olmadığını açıkla.", store.all())
                assertEquals(2, candidates.proposals.size)
                assertTrue(candidates.proposals.all { it.before == null && it.after.title.isNotBlank() })
                assertNotEquals(candidates.proposals[0].after.id, candidates.proposals[1].after.id)
                assertEquals(listOf(approved), store.all())
                fixture("AI_METADATA_CANDIDATES_OK")

                val link = client.chat(settings, key, "Bu uydurma test bağlantısından seri adı adayı hazırla: https://example.com/solo-leveling-test . Siteye eriştiğini veya doğruladığını iddia etme. Yeni bir aday ver; mevcut kaydı değiştirme.", store.all())
                assertTrue(link.proposals.isNotEmpty() && link.proposals.all { it.before == null })
                assertEquals(listOf(approved), store.all())
                fixture("AI_LINK_CANDIDATE_OK")

                val recommendation = client.chat(settings, key, "Test kütüphanemdeki sevme/sevmeme nedenlerine göre iki kişisel manhwa önerisi ver. Neden uyduklarını açıkla; karakter gelişimi, dünya inşası ve tekrar tercihlerimi değerlendir. Kayıt değişikliği hazırlama, changes boş olsun.", store.all())
                assertTrue(recommendation.message.isNotBlank())
                assertTrue(recommendation.proposals.isEmpty())
                val message = recommendation.message.lowercase(java.util.Locale.forLanguageTag("tr-TR"))
                assertTrue("Öneri test tercihlerini açıklamalı", listOf("karakter", "gelişim", "dünya", "tekrar").any { it in message })
                assertEquals(listOf(approved), store.all())
                fixture("AI_PERSONAL_RECOMMENDATION_OK")
            }
        } finally { context.deleteDatabase(database) }
    }
}
