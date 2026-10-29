package com.qylent.manhwatakip

import org.junit.Assert.*
import org.junit.Test

class AiCoverFinderTest {
    private val page = "https://okutoon.com/test-serisi"
    private val cover = CoverResult(page, "https://om-cdn.okutoon.com/covers/test.webp", byteArrayOf(1))

    @Test fun alternativeSearchSkipsPreviousSourceAndDuplicateImage() {
        val other = "https://eldermanga.com/manga/test-serisi"
        val final = "https://trmanga.com/webtoon/test-serisi"
        val fetched = mutableListOf<String>()
        val finder = AiCoverFinder(null, { source ->
            fetched.add(source)
            if (source == other) cover.copy(seriesUrl = other) else cover.copy(seriesUrl = final, imageUrl = "https://cdn.trmanga.com/new.jpg")
        }, { listOf(CoverCandidate("Test Serisi", page), CoverCandidate("Test Serisi", other), CoverCandidate("Test Serisi", final)) })
        assertEquals(final, finder.find("Test Serisi", page, emptyList(), setOf(page, cover.imageUrl)).seriesUrl)
        assertEquals(listOf(other, final), fetched)
    }

    @Test fun readingSourceCoverDoesNotNeedTurkishLetteringOrModel() {
        val finder = AiCoverFinder({ error("Bağlantıdaki kapak modele gönderilmemeli") },
            { cover }, { error("Okuma bağlantısı varken arama gerekmemeli") })
        assertEquals(cover, finder.find("Test Serisi", page, emptyList()))
    }
    @Test fun titleAloneFindsExactSourceCandidateWithoutModelOrReadingLink() {
        val finder = AiCoverFinder(null, { cover },
            { listOf(CoverCandidate("Test Serisi", page)) })
        assertEquals(cover, finder.find("Test Serisi", "", emptyList()))
    }
    @Test fun unavailableExactCoverContinuesToOtherSource() {
        val second = cover.copy(seriesUrl = "https://eldermanga.com/manga/test-serisi")
        val fetched = mutableListOf<String>()
        val finder = AiCoverFinder({ error("Tam ad eşleşmesinde AI gerekmemeli") }, {
            fetched.add(it); if (it == page) throw IllegalArgumentException("Kaynak kapalı") else second
        }, { listOf(CoverCandidate("Test Serisi", page), CoverCandidate("Test Serisi", second.seriesUrl)) })
        assertEquals(second, finder.find("Test Serisi", "", emptyList()))
        assertEquals(listOf(page, second.seriesUrl), fetched)
    }
    @Test fun modelChoosesOnlyActualCandidateAndAcceptsFencedJson() {
        var calls = 0
        val finder = AiCoverFinder({ messages ->
            calls++; assertFalse(messages.toString().contains("image_url"))
            "```json\n{\"indices\":[0]}\n```"
        }, { cover }, { listOf(CoverCandidate("Test Serisi", page)) })
        assertEquals(cover, finder.find("Test", "", emptyList()))
        assertEquals(1, calls)
    }
    @Test fun unknownTitleUsesModelAlternativeThenExactSourceMatch() {
        val searches = mutableListOf<String>()
        val finder = AiCoverFinder({ "```json\n{\"queries\":[\"Test Serisi\"]}\n```" }, { cover }, {
            searches.add(it); if (it == "Test Serisi") listOf(CoverCandidate("Test Serisi", page)) else emptyList()
        })
        assertEquals(cover, finder.find("Test", "", emptyList()))
        assertEquals(listOf("Test", "Test Serisi"), searches)
    }
    @Test fun inventedUrlsIndicesAndInvalidModelAnswersNeverFetch() {
        for (answer in listOf("User Safety: safe", "{\"indices\":[999]}", "{\"indices\":[\"0\"]}", "Açıklama\n```json\n{\"indices\":[0]}\n```", "```text\n{\"indices\":[0]}\n```")) {
            assertThrows(IllegalArgumentException::class.java) {
                AiCoverFinder({ answer }, { fail("Geçersiz seçim indirilmemeli"); cover },
                    { listOf(CoverCandidate("Test Serisi", page)) }).find("Test", "", emptyList())
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AiCoverFinder({ "{\"indices\":[0]}" }, { fail("Harici model URL'si indirilmemeli"); cover },
                { listOf(CoverCandidate("Test", "https://evil.invalid/secret")) }).find("Test", "", emptyList())
        }
    }
    @Test fun searchHtmlKeepsOnlyRealSeriesCards() {
        val html = """<a href='/giris'>Giriş</a><a class='series-card' href='/test-serisi'><img alt='Test &amp; Seri' src='/cover.webp'></a>
            <a class='series-card' href='https://evil.invalid/x'><img alt='Yabancı'></a>
            <a class='series-card' href='/test-serisi/bolum-1'><img alt='Bölüm'></a>"""
        assertEquals(listOf(CoverCandidate("Test & Seri", page)), CoverFetcher.extractCandidates(html, "https://okutoon.com/tur?q=test"))
    }
}
