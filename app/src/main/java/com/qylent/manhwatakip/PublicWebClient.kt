package com.qylent.manhwatakip

import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.net.Proxy
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Dns
import okhttp3.ConnectionPool

class PublicWebClient(
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val transport: (URI, List<InetAddress>) -> Response = ::openPinned
) {
    data class Response(val status: Int, val mime: String, val length: Long, val location: String?, val body: InputStream) : AutoCloseable {
        override fun close() = body.close()
    }
    fun get(url: String, maxBytes: Int, image: Boolean): ByteArray = get(url, maxBytes, image) { _, _ -> }

    data class Page(val bytes: ByteArray, val url: String)
    fun getPage(url: String, maxBytes: Int = 2 * 1024 * 1024): Page = request(url, maxBytes, false) { _, _ -> }

    internal fun get(url: String, maxBytes: Int, image: Boolean, validateHop: (String, Boolean) -> Unit): ByteArray {
        return request(url, maxBytes, image, validateHop).bytes
    }
    private fun request(url: String, maxBytes: Int, image: Boolean, validateHop: (String, Boolean) -> Unit): Page {
        require(maxBytes in 1..(if (image) 5 * 1024 * 1024 else 2 * 1024 * 1024))
        var current = url
        repeat(4) {
            val uri = validateUrl(current)
            validateHop(current, image)
            val host = uri.host.removeSurrounding("[", "]")
            val addresses = if (host.contains(':') || host.matches(Regex("[0-9.]+"))) listOf(InetAddress.getByName(host)) else resolve(host).toList()
            require(addresses.isNotEmpty() && addresses.all(::isPublic)) { "Public DNS adresi gerekli." }
            transport(uri, addresses).use { response ->
                if (response.status in listOf(301, 302, 303, 307, 308)) {
                    current = uri.resolve(requireNotNull(response.location)).toString()
                } else {
                    require(response.status == 200) { "Site yanıt vermedi (${response.status})." }
                    val mime = response.mime.substringBefore(';').trim().lowercase(Locale.ROOT)
                    require(if (image) mime.startsWith("image/") else mime in listOf("text/html", "application/xhtml+xml")) { "Beklenen içerik türü alınamadı." }
                    require(response.length <= maxBytes) { "Yanıt çok büyük." }
                    val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) {
                        val count = response.body.read(buffer, 0, minOf(buffer.size, maxBytes - output.size() + 1))
                        if (count < 0) break
                        require(count + output.size() <= maxBytes) { "Yanıt çok büyük." }
                        output.write(buffer, 0, count)
                    }
                    return Page(output.toByteArray(), current)
                }
            }
        }
        throw IllegalArgumentException("Çok fazla yönlendirme.")
    }

    companion object {
        internal fun validateUrl(value: String): URI {
            require(value.length <= 2048 && value.none { it <= ' ' || it == '\\' })
            val uri = URI(value)
            require(uri.scheme == "https" && uri.rawUserInfo == null && uri.port in listOf(-1, 443) && !uri.host.isNullOrBlank())
            val host = uri.host.removeSurrounding("[", "]")
            require(!host.contains('%') && !host.contains("::ffff:", ignoreCase = true))
            if (host.contains(':') || host.matches(Regex("[0-9.]+"))) {
                val address = InetAddress.getByName(host)
                // JVM folds mapped IPv6 literals into Inet4Address, including expanded spellings.
                require((!host.contains(':') || address.address.size == 16) && isPublic(address))
            }
            // Reject alternative numeric IPv4 spelling before a client can canonicalize it.
            require(!host.startsWith("0x", ignoreCase = true))
            return uri
        }

        internal fun isPublic(address: InetAddress): Boolean {
            val b = address.address.map { it.toInt() and 255 }
            if (b.size == 4) {
                val a = b[0]; val c = b[1]; val d = b[2]
                return !(a == 0 || a == 10 || a == 127 || a >= 224 || a == 100 && c in 64..127 || a == 169 && c == 254 ||
                    a == 172 && c in 16..31 || a == 192 && (c == 168 || c == 0 && d in listOf(0, 2) || c == 88 && d == 99) ||
                    a == 198 && (c in 18..19 || c == 51 && d == 100) || a == 203 && c == 0 && d == 113)
            }
            // Only global unicast; exclude IETF special use, 6to4 and documentation.
            return b.size == 16 && b[0] in 0x20..0x3f &&
                !(b[0] == 0x20 && b[1] == 1 && (b[2] <= 1 || b[2] == 0x0d && b[3] == 0xb8)) &&
                !(b[0] == 0x20 && b[1] == 2) && !(b[0] == 0x3f && b[1] == 0xff && b[2] < 0x10)
        }

        internal fun pinnedClient(uri: URI, addresses: List<InetAddress>): OkHttpClient {
            val pinned = addresses.toList()
            return OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                .dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> {
                    require(hostname == uri.host.removeSurrounding("[", "]").lowercase(Locale.ROOT))
                    return pinned
                } })
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
        }

        private fun openPinned(uri: URI, addresses: List<InetAddress>): Response {
            // Fresh pool per hop: no proxy, shared connections, cookies or system DNS fallback.
            // URL retains the original hostname: OkHttp's default TLS verifier and SNI remain intact.
            val client = pinnedClient(uri, addresses)
            val wire = client.newCall(Request.Builder().url(uri.toString())
                .header("User-Agent", "Mozilla/5.0 ManhwaTakip/0.9")
                .header("Accept-Encoding", "identity").build()).execute()
            val body = requireNotNull(wire.body)
            return Response(wire.code, wire.header("Content-Type").orEmpty(), body.contentLength(), wire.header("Location"), body.byteStream())
        }
    }
}
