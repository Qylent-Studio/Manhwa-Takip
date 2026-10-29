package com.qylent.manhwatakip

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class SeriesMetadataTest {
    private fun fixture(name: String) = javaClass.getResource("/metadata/$name.html")!!.readText()
    @Test fun structuredBeatsOgHeadingAndSlugWithEntities() {
        val parsed = SeriesMetadataFetcher.parse(fixture("structured"), "https://public.example/books/not-the-title")
        assertEquals("Kılıç & Gölge İ", parsed.title)
        assertFalse(parsed.inferredTitle)
        assertEquals("https://cdn.example/cover.jpg", parsed.images.first())
    }
    @Test fun genericDoesNotInventFirstPathAndStructuredParentBeatsChapterCanonical() {
        val chapter = SeriesMetadataFetcher.parse(fixture("chapter"), "https://public.example/books/kilic/12")
        assertEquals("https://public.example/books/kilic", chapter.seriesUrl)
        assertEquals("Kılıç", chapter.title)
        val generic = SeriesMetadataFetcher.parse("", "https://public.example/books/a-real-title")
        assertEquals("https://public.example/books/a-real-title", generic.seriesUrl)
        assertEquals("a real title", generic.title)
        assertTrue(generic.inferredTitle)
    }
    @Test fun ogHeadingAndDocumentTitlePrecedeSlugAndLazyCoverRejectsLogoAds() {
        val parsed = SeriesMetadataFetcher.parse(fixture("lazy"), "https://public.example/books/golge")
        assertEquals("Gölge & Işık", parsed.title)
        assertEquals(listOf("https://public.example/covers/golge.jpg"), parsed.images)
        assertEquals("Türkçe İsim", SeriesMetadataFetcher.parse("<h1>Türkçe <span>İsim</span></h1><title>Wrong</title>", "https://public.example/a").title)
        assertEquals("Title", SeriesMetadataFetcher.parse("<title>Title</title>", "https://public.example/a").title)
        assertThrows(IllegalArgumentException::class.java) { SeriesMetadataFetcher.parse(fixture("blocked"), "https://public.example/a") }
    }
    @Test fun publicClientUsedForPageAndCdnAndDecodeFailureDoesNotLoseTitle() {
        val opened = mutableListOf<String>()
        val client = PublicWebClient(resolve = { listOf(InetAddress.getByName("8.8.8.8")) }, transport = { uri, _ ->
            opened.add(uri.toString())
            PublicWebClient.Response(200, if (uri.host == "cdn.example") "image/jpeg" else "text/html", -1, null,
                (if (uri.host == "cdn.example") "bad image" else fixture("structured")).byteInputStream())
        })
        val result = SeriesMetadataFetcher(client, { throw IllegalArgumentException("decode") }).fetch("https://public.example/books/test")
        assertEquals("Kılıç & Gölge İ", result.title); assertNull(result.cover)
        assertEquals(listOf("https://public.example/books/test", "https://cdn.example/cover.jpg"), opened)
    }
    @Test fun maliciousCanonicalAndImagesCannotBecomePrivateRequests() {
        val parsed = SeriesMetadataFetcher.parse("<link rel='canonical' href='https://127.0.0.1/x'><meta property='og:image' content='http://local/a'>", "https://public.example/books/test")
        assertEquals("https://public.example/books/test", parsed.seriesUrl); assertTrue(parsed.images.isEmpty())
    }
    @Test fun verifiedNamedPathsNormalizeButLookalikeAndGenericDoNot() {
        assertEquals("https://mangawow.org/manga/my-wife-is-a-demon-queen/", SeriesMetadataFetcher.startUrl("https://mangawow.org/manga/my-wife-is-a-demon-queen/bolum-1/"))
        assertEquals("https://webtoonhatti.club/webtoon/i-am-the-fated-villain/", SeriesMetadataFetcher.startUrl("https://webtoonhatti.club/webtoon/i-am-the-fated-villain/bolum-284/"))
        assertEquals("https://other.example/manga/a/bolum-1", SeriesMetadataFetcher.startUrl("https://other.example/manga/a/bolum-1"))
        assertEquals("My Wife is a Demon Queen", SeriesMetadataFetcher.parse("<meta property='og:title' content='My Wife is a Demon Queen - MangaWOW'>", "https://mangawow.org/manga/my-wife-is-a-demon-queen/").title)
    }
    @Test fun unrelatedThumbnailCannotReplaceMissingCoverAndRedirectUsesFinalPageForRelativeImage() {
        assertTrue(SeriesMetadataFetcher.parse("<h1>Mine</h1><meta property='og:image' content='/og-default.png'><img class='thumbnail' src='/other.jpg' alt='Other'><img class='object-cover' src='/recommended.jpg' alt='Other'>", "https://public.example/books/mine").images.isEmpty())
        val opened = mutableListOf<String>()
        val client = PublicWebClient(resolve = { listOf(InetAddress.getByName("8.8.8.8")) }, transport = { uri, _ ->
            opened.add(uri.toString())
            when (uri.path) {
                "/old" -> PublicWebClient.Response(302, "text/html", -1, "/books/new/", byteArrayOf().inputStream())
                "/books/new/" -> PublicWebClient.Response(200, "text/html", -1, null, "<h1>Mine</h1><meta property='og:image' content='cover.jpg'>".byteInputStream())
                else -> PublicWebClient.Response(200, "image/jpeg", -1, null, "jpeg".byteInputStream())
            }
        })
        val result = SeriesMetadataFetcher(client, {}).fetch("https://public.example/old")
        assertEquals("https://public.example/books/new/cover.jpg", result.cover?.imageUrl)
        assertEquals("https://public.example/books/new/", result.seriesUrl)
        assertEquals(3, opened.size)
    }
}
