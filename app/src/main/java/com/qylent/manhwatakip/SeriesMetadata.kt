package com.qylent.manhwatakip

import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class SeriesMetadata(val title: String?, val seriesUrl: String, val cover: CoverResult?, val inferredTitle: Boolean)
internal data class MetadataPage(val title: String?, val seriesUrl: String, val images: List<String>, val inferredTitle: Boolean)

class SeriesMetadataFetcher(private val web: PublicWebClient = PublicWebClient(), private val validateImage: (ByteArray) -> Unit = { CoverStore.decode(it).recycle() }) {
    fun fetch(url: String): SeriesMetadata {
        var page = startUrl(url)
        var response = web.getPage(page); page = response.url
        var parsed = parse(String(response.bytes, Charsets.UTF_8), page)
        if (parsed.seriesUrl != page) {
            // At most one declared series page; never chase arbitrary graph links.
            page = parsed.seriesUrl
            response = web.getPage(page); page = response.url
            parsed = parse(String(response.bytes, Charsets.UTF_8), page)
        }
        val cover = parsed.images.take(3).firstNotNullOfOrNull { image ->
            runCatching {
                val bytes = web.get(image, 5 * 1024 * 1024, true)
                validateImage(bytes)
                CoverResult(page, image, bytes)
            }.getOrNull()
        }
        return SeriesMetadata(parsed.title, page, cover, parsed.inferredTitle)
    }
    companion object {
        fun startUrl(value: String): String {
            val uri = PublicWebClient.validateUrl(value.trim())
            val prefix = when (uri.host.lowercase(Locale.ROOT).removePrefix("www.")) {
                "mangawow.org", "turkmanga.org" -> "manga"
                "webtoonhatti.club" -> "webtoon"
                else -> null
            }
            // Domains and paths observed in public pages/search; never apply to lookalike hosts.
            val parts = uri.rawPath.split('/').filter(String::isNotEmpty)
            if (prefix != null && parts.size >= 2 && parts[0] == prefix) {
                val suffix = if (uri.host.removePrefix("www.") == "turkmanga.org") "" else "/"
                return "https://${uri.rawAuthority}/$prefix/${parts[1]}$suffix"
            }
            return runCatching { CoverFetcher.seriesUrl(value) }.getOrElse { uri.toASCIIString().substringBefore('#') }
        }
        private fun entities(value: String): String = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[A-Za-z]+);").replace(value) { match ->
            val name = match.groupValues[1]
            val number = runCatching { when { name.startsWith("#x") -> name.drop(2).toInt(16); name.startsWith('#') -> name.drop(1).toInt(); else -> null } }.getOrNull()
            if (number != null && Character.isValidCodePoint(number) && number !in 0xD800..0xDFFF) String(Character.toChars(number))
            else mapOf("amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">", "nbsp" to " ", "ouml" to "ö", "Ouml" to "Ö", "uuml" to "ü", "Uuml" to "Ü", "ccedil" to "ç", "Ccedil" to "Ç")[name] ?: match.value
        }
        internal fun attributes(tag: String): Map<String, String> = Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""").findAll(tag).associate {
            it.groupValues[1].lowercase(Locale.ROOT) to entities(it.groupValues.drop(2).firstOrNull(String::isNotEmpty).orEmpty())
        }
        private fun text(value: String): String = entities(value.replace(Regex("<[^>]*>"), " ")).replace(Regex("\\s+"), " ").trim()
        private fun title(value: String?): String? = value?.let(::text)?.takeIf { it.length in 1..200 }
        private fun safeLink(value: String?, page: String): String? = value?.takeIf(String::isNotBlank)?.let { runCatching { URI(page).resolve(it.trim()).toASCIIString().also(PublicWebClient::validateUrl) }.getOrNull() }
        internal fun parse(html: String, page: String): MetadataPage {
            require(html.length <= 2 * 1024 * 1024)
            PublicWebClient.validateUrl(page)
            val visible = html.replace(Regex("<!--[\\s\\S]*?-->|<(script|style)\\b[^>]*>[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE), "")
            val heading = Regex("<h1\\b[^>]*>([\\s\\S]*?)</h1>", RegexOption.IGNORE_CASE).find(visible)?.groupValues?.get(1)
            val document = Regex("<title\\b[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(visible)?.groupValues?.get(1)
            require(listOfNotNull(heading, document).none { text(it).lowercase(Locale.ROOT).let { t -> t.contains("just a moment") || t.contains("access denied") || t.contains("attention required") || t.contains("verify you are human") } }) { "Kaynak erişimi engelledi; elle düzenleyebilirsin." }
            val meta = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(visible).map { attributes(it.value) }.toList()
            fun meta(key: String) = meta.firstOrNull { it["property"]?.lowercase(Locale.ROOT) == key || it["name"]?.lowercase(Locale.ROOT) == key }?.get("content")
            val records = mutableListOf<JSONObject>()
            fun walk(value: Any?, depth: Int) {
                if (depth > 8 || records.size >= 128) return
                when (value) {
                    is JSONObject -> { records.add(value); value.keys().forEach { walk(value.opt(it), depth + 1) } }
                    is JSONArray -> (0 until minOf(value.length(), 128)).forEach { walk(value.opt(it), depth + 1) }
                }
            }
            Regex("<script\\b[^>]*>[\\s\\S]*?</script\\s*>", RegexOption.IGNORE_CASE).findAll(html).take(32).forEach { script ->
                if (attributes(script.value.substringBefore('>'))["type"]?.lowercase(Locale.ROOT) == "application/ld+json") {
                    val json = script.value.substringAfter('>').substringBeforeLast('<').trim()
                    if (json.length <= 256 * 1024) runCatching { walk(if (json.startsWith('[')) JSONArray(json) else JSONObject(json), 0) }
                }
            }
            fun types(record: JSONObject) = record.opt("@type").let { if (it is JSONArray) (0 until it.length()).map(it::optString) else listOf(it?.toString().orEmpty()) }
            val series = records.firstOrNull { types(it).any { type -> type in listOf("ComicSeries", "Book", "Series", "CreativeWorkSeries", "Manga") } }
            val structuredTitle = title(series?.optString("name"))
            val canonical = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(visible).map { attributes(it.value) }
                .firstOrNull { it["rel"].orEmpty().split(' ').any { rel -> rel.equals("canonical", true) } }?.get("href")
            fun sameSite(value: String?) = safeLink(value, page)?.takeIf { URI(it).host.equals(URI(page).host, true) && URI(it).path.trim('/').isNotEmpty() }
            val normalized = startUrl(page)
            val seriesUrl = if (normalized != page) normalized else sameSite(series?.optString("url")) ?: sameSite(canonical) ?: page
            val rawTitle = structuredTitle ?: title(meta("og:title")) ?: title(heading) ?: title(document)
            val chosenTitle = if (URI(page).host.removePrefix("www.") == "mangawow.org") rawTitle?.removeSuffix(" - MangaWOW") else rawTitle
            val slug = runCatching { URLDecoder.decode(URI(seriesUrl).rawPath.trimEnd('/').substringAfterLast('/').replace("+", "%2B"), "UTF-8").replace('-', ' ').replace('_', ' ') }.getOrNull()
            val images = mutableListOf<String>()
            fun image(value: Any?) {
                when (value) {
                    is String -> images.add(value)
                    is JSONObject -> image(value.opt("url") ?: value.opt("contentUrl"))
                    is JSONArray -> (0 until minOf(value.length(), 3)).forEach { image(value.opt(it)) }
                }
            }
            // Preserve the existing Elder/Uzay authoritative /upload/ thumbnail preference.
            val tags = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(visible).map { attributes(it.value) }.toList()
            if (URI(page).host.removePrefix("www.") in listOf("eldermanga.com", "uzaymanga.com")) tags.firstOrNull { it["src"].orEmpty().contains("/upload/") && it["src"].orEmpty().contains("thumbnail") && !it["alt"].isNullOrBlank() }?.get("src")?.let(images::add)
            image(series?.opt("image")); image(meta("og:image")); image(meta("twitter:image"))
            tags.filter { tag ->
                val label = listOf(tag["class"], tag["id"], tag["alt"]).joinToString(" ").lowercase(Locale.ROOT)
                (listOf(tag["class"], tag["id"]).joinToString(" ").lowercase(Locale.ROOT).split(Regex("\\s+")).any { it in setOf("series-cover", "manga-cover", "cover", "cover-image") } || chosenTitle != null && tag["alt"] == chosenTitle) && !badImage(label)
            }.forEach { image(it["data-src"] ?: it["data-lazy-src"] ?: it["src"]) }
            val validImages = images.filterNot { badImage(it.lowercase(Locale.ROOT)) }.mapNotNull { safeLink(it, page) }.distinct().take(3)
            return MetadataPage(chosenTitle ?: title(slug), seriesUrl, validImages, chosenTitle == null)
        }
        private fun badImage(value: String) = Regex("logo|advert|banner|placeholder|favicon|og-default|(?:^|[/_-])ads?(?:[/_.-]|$)").containsMatchIn(value)
    }
}
