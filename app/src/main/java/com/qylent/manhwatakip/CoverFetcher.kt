package com.qylent.manhwatakip

import java.net.URI
import java.util.Locale

data class CoverResult(val seriesUrl: String, val imageUrl: String, val bytes: ByteArray)
data class CoverCandidate(val title: String, val url: String)

class CoverFetcher(private val web: PublicWebClient = PublicWebClient(), private val validateImage: (ByteArray) -> Unit = { CoverStore.decode(it).recycle() }) {
    fun search(title: String): List<CoverCandidate> {
        require(title.isNotBlank() && title.length <= 300)
        val query = java.net.URLEncoder.encode(title, "UTF-8")
        return listOf("https://trmanga.com/webtoon-listesi?q=$query", "https://eldermanga.com/manga?search=$query",
            "https://uzaymanga.com/manga?search=$query", "https://okutoon.com/tur?q=$query").flatMap { page ->
            runCatching { extractCandidates(String(read(page, 2 * 1024 * 1024, false), Charsets.UTF_8), page) }.getOrDefault(emptyList())
        }.distinctBy { it.url }.take(20)
    }
    fun fetch(readingUrl: String): CoverResult {
        return SeriesMetadataFetcher(web, validateImage).fetch(readingUrl).cover
            ?: throw IllegalArgumentException("Bu sayfada geçerli seri kapağı bulunamadı; adla aramayı deneyebilirsin.")
    }
    private fun read(start: String, limit: Int, image: Boolean): ByteArray {
        return web.get(start, limit, image) { value, isImage -> validate(value, isImage) }
    }
    companion object {
        private val sites = setOf("golgebahcesi.com", "eldermanga.com", "uzaymanga.com", "okutoon.com", "trmanga.com")
        private fun validate(value: String, image: Boolean): URI {
            val uri = runCatching { URI(value) }.getOrNull()
            val host = uri?.host?.lowercase(Locale.ROOT).orEmpty()
            val allowed = sites.any { host == it || host == "www.$it" } || image && (host == "skycdn.online" || host.endsWith(".skycdn.online") || host.endsWith(".okutoon.com") || host == "cdn.trmanga.com")
            require(value.length <= 2048 && uri?.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) && allowed) { "Kapak kaynağı desteklenen Türkçe manga sitelerinden birinin HTTPS bağlantısı olmalı." }
            return uri
        }
        fun seriesUrl(value: String): String {
            val uri = validate(value.trim(), false)
            val parts = uri.path.split('/').filter { it.isNotEmpty() }
            val host = uri.host.lowercase(Locale.ROOT).removePrefix("www.")
            val path = if (host == "okutoon.com") {
                require(parts.isNotEmpty() && parts.first() !in listOf("tur", "sayfa", "giris", "arama")) { "Seri veya bölüm bağlantısını gir." }
                "/${parts.first()}"
            } else {
                val prefix = if (host == "trmanga.com") "webtoon" else "manga"
                require(parts.size >= 2 && parts[0] == prefix) { "Seri veya bölüm bağlantısını gir." }
                "/$prefix/${parts[1]}"
            }
            return URI(uri.scheme, null, uri.host, -1, path, null, null).toASCIIString()
        }
        private fun attributes(tag: String): Map<String, String> = Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""").findAll(tag).associate {
            it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues.drop(2).firstOrNull { value -> value.isNotEmpty() }.orEmpty()
                .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        }
        fun extractCandidates(html: String, page: String): List<CoverCandidate> {
            fun candidate(title: String, href: String): CoverCandidate? {
                val url = runCatching { URI(page).resolve(href).toASCIIString() }.getOrNull() ?: return null
                if (title.isBlank() || title.length > 300 || runCatching { seriesUrl(url) }.getOrNull() != url.trimEnd('/')) return null
                return CoverCandidate(title, url.trimEnd('/'))
            }
            val links = Regex("<a\\b[^>]*>[\\s\\S]*?</a>", RegexOption.IGNORE_CASE).findAll(html).mapNotNull { match ->
                val attrs = attributes(match.value.substringBefore('>'))
                if (attrs["class"].orEmpty().split(' ').none { it in setOf("series-card", "wl-card", "sw-card") }) return@mapNotNull null
                val href = attrs["href"] ?: return@mapNotNull null
                val image = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE).find(match.value) ?: return@mapNotNull null
                candidate(attrs["title"]?.trim() ?: attributes(image.value)["alt"].orEmpty().trim(), href)
            }.toList()
            val host = URI(page).host.removePrefix("www.")
            val serialized = if (host in listOf("eldermanga.com", "uzaymanga.com")) {
                // These sites put their search results in Svelte data; never execute the page's scripts.
                val data = Regex("data:\\{series:\\[([\\s\\S]*?)\\],totalCount:").find(html)?.groupValues?.get(1).orEmpty()
                Regex("(?:^|,)\\{migrate_id:[\\s\\S]*?(?=,\\{migrate_id:|$)").findAll(data).mapNotNull { record ->
                    fun field(name: String): String? = runCatching {
                        val literal = Regex("\\b$name:(\"(?:\\\\.|[^\"\\\\])*\")").find(record.value)?.groupValues?.get(1) ?: return@runCatching null
                        org.json.JSONArray("[$literal]").getString(0)
                    }.getOrNull()
                    val title = field("name") ?: return@mapNotNull null
                    val slug = field("slug") ?: return@mapNotNull null
                    candidate(title, "/manga/$slug")
                }.toList()
            } else emptyList()
            return (links + serialized).distinctBy { it.url }.take(20)
        }
        fun extractImageUrl(html: String, page: String): String {
            val source = URI(page).host.removePrefix("www.")
            // Elder/Uzay currently publish an OG path without /upload; their actual cover img is authoritative.
            val thumbnail = if (source in listOf("eldermanga.com", "uzaymanga.com")) Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html)
                .map { attributes(it.value) }.firstOrNull { it["src"].orEmpty().contains("/upload/") && it["src"].orEmpty().contains("thumbnail") && !it["alt"].isNullOrBlank() }?.get("src") else null
            val meta = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).map { attributes(it.value) }
                .firstOrNull { it["property"] == "og:image" || it["name"] == "twitter:image" }?.get("content")
            val image = thumbnail ?: meta ?: throw IllegalArgumentException("Bu sayfada seri kapağı bulunamadı.")
            require(!image.lowercase(Locale.ROOT).contains("logo")) { "Seri kapağı bulunamadı; site logosu kullanılmadı." }
            return URI(page).resolve(image).toASCIIString().also { validate(it, true) }
        }
    }
}
