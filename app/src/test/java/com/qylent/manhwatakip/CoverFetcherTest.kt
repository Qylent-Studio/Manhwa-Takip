package com.qylent.manhwatakip

import org.junit.Assert.*
import org.junit.Test

class CoverFetcherTest {
    @Test fun allFiveSourceFixturesStillFetchThroughGuardedTransport() {
        for (host in listOf("golgebahcesi.com", "eldermanga.com", "uzaymanga.com", "okutoon.com", "trmanga.com")) {
            val path = when (host) { "okutoon.com" -> "/test"; "trmanga.com" -> "/webtoon/test"; else -> "/manga/test" }
            val page = "https://$host$path"
            val client = PublicWebClient(resolve = { listOf(java.net.InetAddress.getByName("8.8.8.8")) }, transport = { uri, _ ->
                val image = uri.path == "/cover.jpg"
                PublicWebClient.Response(200, if (image) "image/jpeg" else "text/html", -1, null,
                    (if (image) "jpeg" else "<meta property='og:image' content='/cover.jpg'>").toByteArray().inputStream())
            })
            val result = CoverFetcher(client, {}).fetch(page + "/chapter-1")
            assertEquals(page, result.seriesUrl)
            assertArrayEquals("jpeg".toByteArray(), result.bytes)
            assertEquals("https://$host/cover.jpg", result.imageUrl)
        }
    }
    private fun fixture(factory: (java.net.URL) -> java.net.HttpURLConnection) = CoverFetcher(PublicWebClient(
        resolve = { listOf(java.net.InetAddress.getByName("8.8.8.8")) },
        transport = { uri, _ ->
            val wire = factory(uri.toURL())
            PublicWebClient.Response(wire.responseCode, wire.contentType.orEmpty(), wire.contentLengthLong,
                wire.getHeaderField("Location"), if (wire.responseCode == 200 && wire.contentLengthLong <= 2 * 1024 * 1024) wire.inputStream else byteArrayOf().inputStream())
        }
    ), {})
    @Test fun unsafeRedirectOversizedHtmlAndTimeoutNeverReachAnImageRequest() {
        val url = "https://okutoon.com/test"
        fun wire(status: Int = 200, length: Long = -1, location: String? = null, input: () -> java.io.InputStream = { byteArrayOf().inputStream() }) =
            object : java.net.HttpURLConnection(java.net.URL(url)) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = status
                override fun getContentLengthLong() = length
                override fun getContentType() = "text/html"
                override fun getHeaderField(name: String?) = if (name == "Location") location else null
                override fun getInputStream() = input()
            }
        var opened = 0
        assertThrows(IllegalArgumentException::class.java) {
            fixture { opened++; wire(302, location = "https://127.0.0.1/private") }.fetch(url)
        }
        assertEquals(1, opened)
        assertThrows(IllegalArgumentException::class.java) {
            fixture { wire(length = 2 * 1024 * 1024 + 1L, input = { fail("Büyük yanıt okunmamalı"); byteArrayOf().inputStream() }) }.fetch(url)
        }
        assertThrows(IllegalArgumentException::class.java) {
            fixture { wire(input = { ByteArray(2 * 1024 * 1024 + 1).inputStream() }) }.fetch(url)
        }
        assertThrows(java.net.SocketTimeoutException::class.java) {
            fixture { wire(input = { throw java.net.SocketTimeoutException("synthetic timeout") }) }.fetch(url)
        }
    }
    @Test fun chapterLinksResolveToTheirOwnSeriesAndCoverBeatsLogo() {
        assertEquals("https://golgebahcesi.com/manga/test", CoverFetcher.seriesUrl("https://golgebahcesi.com/manga/test/bolum/chapter-1"))
        assertEquals("https://eldermanga.com/manga/test", CoverFetcher.seriesUrl("https://eldermanga.com/manga/test/0-bolum-oku"))
        assertEquals("https://okutoon.com/test", CoverFetcher.seriesUrl("https://okutoon.com/test/bolum-12"))
        val html = """<img alt="Logo" src="/logo.png"><meta content="https://om-cdn.okutoon.com/covers/a.webp?x=1&amp;y=2" property="og:image">"""
        assertEquals("https://om-cdn.okutoon.com/covers/a.webp?x=1&y=2", CoverFetcher.extractImageUrl(html, "https://okutoon.com/test"))
        val elder = """<meta property='og:image' content='/thumbnail/missing.avif'><img src='/upload/thumbnail/correct.avif?w=512' alt='Seri'>"""
        assertEquals("https://eldermanga.com/upload/thumbnail/correct.avif?w=512", CoverFetcher.extractImageUrl(elder, "https://eldermanga.com/manga/test"))
    }
    @Test fun unsafeOrMissingCoverIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { CoverFetcher.seriesUrl("http://127.0.0.1/private") }
        assertThrows(IllegalArgumentException::class.java) { CoverFetcher.seriesUrl("https://okutoon.com.evil.invalid/test") }
        assertThrows(IllegalArgumentException::class.java) { CoverFetcher.extractImageUrl("<meta property='og:image' content='file:///secret'>", "https://okutoon.com/test") }
        assertThrows(IllegalArgumentException::class.java) { CoverFetcher.extractImageUrl("<img src='/logo.png' alt='Logo'>", "https://okutoon.com/test") }
    }
    @Test fun trmangaSeriesAndChapterResolveToValidatedSourceCover() {
        val page = "https://trmanga.com/webtoon/sword-or-blood"
        assertEquals(page, CoverFetcher.seriesUrl(page + "/100"))
        assertEquals("https://cdn.trmanga.com/webtoon/cover/61/cover.jpg", CoverFetcher.extractImageUrl(
            "<meta property='og:image' content='https://cdn.trmanga.com/webtoon/cover/61/cover.jpg'>", page))
        assertThrows(IllegalArgumentException::class.java) { CoverFetcher.seriesUrl("https://trmanga.com/webtoon-listesi?q=test") }
        assertThrows(IllegalArgumentException::class.java) { CoverFetcher.extractImageUrl("<meta property='og:image' content='https://cdn.trmanga.com.evil.invalid/a.jpg'>", page) }
    }
    @Test fun nameSearchIncludesTrmangaAndSerializedElderResultsWhenOneSourceFails() {
        val opened = mutableListOf<String>()
        val finder = fixture { url ->
            opened.add(url.toString())
            object : java.net.HttpURLConnection(url) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = if (url.host == "okutoon.com") 503 else 200
                override fun getContentType() = "text/html"
                override fun getInputStream() = when (url.host) {
                    "trmanga.com" -> "<a class='wl-card' href='/webtoon/sword-or-blood' title='Sword or Blood'><img alt='Sword or Blood kapak'></a><a class='wl-card' href='https://evil.invalid/a' title='Sword or Blood'></a>"
                    "eldermanga.com" -> "data:{series:[{migrate_id:1,id:1,name:\"Kılıç ve Kan\",description:\"İçerik { test }\",slug:\"kilic-ve-kan\",SeriesEpisode:[{order:1}]}],totalCount:1}"
                    else -> "<html></html>"
                }.toByteArray().inputStream()
            }
        }
        val candidates = finder.search("Sword or Blood")
        assertTrue(candidates.contains(CoverCandidate("Sword or Blood", "https://trmanga.com/webtoon/sword-or-blood")))
        assertTrue(candidates.contains(CoverCandidate("Kılıç ve Kan", "https://eldermanga.com/manga/kilic-ve-kan")))
        assertTrue(opened.any { it.startsWith("https://trmanga.com/webtoon-listesi?q=") })
        assertTrue(opened.any { it.startsWith("https://eldermanga.com/manga?search=") })
    }

}
