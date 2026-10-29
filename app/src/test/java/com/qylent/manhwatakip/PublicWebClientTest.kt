package com.qylent.manhwatakip

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.SocketTimeoutException

class PublicWebClientTest {
    private fun ip(value: String): InetAddress = if (value.startsWith("::ffff:")) {
        val bytes = ByteArray(16); bytes[10] = -1; bytes[11] = -1
        InetAddress.getByName(value.substringAfter("::ffff:")).address.copyInto(bytes, 12)
        java.net.Inet6Address.getByAddress(null, bytes, -1)
    } else InetAddress.getByName(value)
    private fun response(status: Int = 200, mime: String = "text/html", body: ByteArray = "ok".toByteArray(), location: String? = null, length: Long = -1) =
        PublicWebClient.Response(status, mime, length, location, body.inputStream())

    @Test fun forbiddenAndMixedDnsNeverConnect() {
        val forbidden = listOf("0.0.0.0", "10.0.0.1", "100.64.0.1", "127.0.0.1", "169.254.1.1", "172.16.0.1", "192.168.1.1", "192.0.2.1", "198.18.0.1", "224.0.0.1", "240.0.0.1", "::1", "::", "fc00::1", "fe80::1", "2001:db8::1", "::ffff:127.0.0.1", "::ffff:8.8.8.8", "64:ff9b::808:808")
        for (value in forbidden) {
            var opened = 0
            val client = PublicWebClient(resolve = { listOf(ip("8.8.8.8"), ip(value)) }, transport = { _, _ -> opened++; response() })
            assertThrows("DNS $value", IllegalArgumentException::class.java) { client.get("https://public.example/a", 100, false) }
            assertEquals(0, opened)
        }
    }
    @Test fun unsafeUrlsNeverResolveOrConnect() {
        val client = PublicWebClient(resolve = { fail("DNS forbidden"); emptyList() }, transport = { _, _ -> fail("connect forbidden"); response() })
        for (url in listOf("http://public.example", "https://u:p@public.example", "https://public.example:444", "https://[::ffff:8.8.8.8]/", "https://[0:0:0:0:0:ffff:808:808]/", "https://127.0.0.1/"))
            assertThrows(IllegalArgumentException::class.java) { client.get(url, 100, false) }
    }
    @Test fun redirectsRevalidateAndRebindingCannotChangePinnedAddresses() {
        var resolved = 0; var opened = 0
        val client = PublicWebClient(resolve = { if (++resolved == 1) listOf(ip("8.8.8.8")) else listOf(ip("127.0.0.1")) }, transport = { _, addresses ->
            opened++; assertEquals(listOf(ip("8.8.8.8")), addresses)
            response(302, location = "https://cdn.example/cover")
        })
        assertThrows(IllegalArgumentException::class.java) { client.get("https://public.example/", 100, true) }
        assertEquals(1, opened); assertEquals(2, resolved)
    }
    @Test fun mimeSizeTimeoutAndFourRequestLimit() {
        fun client(reply: () -> PublicWebClient.Response) = PublicWebClient(resolve = { listOf(ip("8.8.8.8")) }, transport = { _, _ -> reply() })
        assertArrayEquals("ok".toByteArray(), client { response() }.get("https://public.example/", 2, false))
        for (reply in listOf({ response(mime = "application/json") }, { response(length = 3) }, { response(body = ByteArray(3)) }))
            assertThrows(IllegalArgumentException::class.java) { client(reply).get("https://public.example/", 2, false) }
        assertThrows(IllegalArgumentException::class.java) { client { response() }.get("https://public.example/", 2, true) }
        assertArrayEquals("ok".toByteArray(), client { response(mime = "image/avif") }.get("https://public.example/", 2, true))
        assertThrows(SocketTimeoutException::class.java) { client { throw SocketTimeoutException("fixture") }.get("https://public.example/", 2, false) }
        var opened = 0
        assertThrows(IllegalArgumentException::class.java) { client { opened++; response(302, location = "/next") }.get("https://public.example/", 2, false) }
        assertEquals(4, opened)
    }
    @Test fun actualOkHttpRouteUsesPinnedAddressWithoutSystemDnsAndKeepsTlsDefaults() {
        val uri = java.net.URI("https://does-not-resolve.invalid/a")
        val base = PublicWebClient.pinnedClient(uri, listOf(ip("8.8.8.8")))
        assertEquals(10000, base.connectTimeoutMillis); assertEquals(15000, base.readTimeoutMillis)
        assertEquals(java.net.Proxy.NO_PROXY, base.proxy)
        assertFalse(base.followRedirects); assertFalse(base.retryOnConnectionFailure)
        assertSame(okhttp3.OkHttpClient().hostnameVerifier, base.hostnameVerifier)
        assertEquals(listOf(ip("8.8.8.8")), base.dns.lookup(uri.host))
        assertThrows(IllegalArgumentException::class.java) { base.dns.lookup("other.invalid") }
        val targets = mutableListOf<java.net.SocketAddress>()
        val factory = object : javax.net.SocketFactory() {
            override fun createSocket(): java.net.Socket = object : java.net.Socket() {
                override fun connect(endpoint: java.net.SocketAddress, timeout: Int) {
                    targets.add(endpoint); assertEquals(10000, timeout)
                    throw SocketTimeoutException("synthetic; no network")
                }
            }
            override fun createSocket(h: String, p: Int) = error("uncontrolled DNS")
            override fun createSocket(h: String, p: Int, l: InetAddress, lp: Int) = error("uncontrolled DNS")
            override fun createSocket(h: InetAddress, p: Int) = error("unexpected")
            override fun createSocket(h: InetAddress, p: Int, l: InetAddress, lp: Int) = error("unexpected")
        }
        val client = base.newBuilder().socketFactory(factory).build()
        assertThrows(SocketTimeoutException::class.java) { client.newCall(okhttp3.Request.Builder().url(uri.toString()).build()).execute() }
        assertEquals(listOf(java.net.InetSocketAddress(ip("8.8.8.8"), 443)), targets)
    }
    @Test fun publicIpv6RelativeRedirectAndResponseClosure() {
        var closed = 0; var opened = 0
        val client = PublicWebClient(resolve = { listOf(ip("2606:4700:4700::1111")) }, transport = { uri, _ ->
            opened++
            if (opened == 2) assertEquals("https://public.example/cover", uri.toString())
            PublicWebClient.Response(if (opened == 1) 302 else 200, "image/webp; q=1", -1, "/cover",
                object : java.io.ByteArrayInputStream(byteArrayOf(1)) { override fun close() { closed++ } })
        })
        assertArrayEquals(byteArrayOf(1), client.get("https://public.example/start", 5 * 1024 * 1024, true))
        assertEquals(2, closed)
        assertThrows(IllegalArgumentException::class.java) { client.get("https://public.example/", 2 * 1024 * 1024 + 1, false) }
        assertThrows(IllegalArgumentException::class.java) { client.get("https://public.example/", 5 * 1024 * 1024 + 1, true) }
    }
    @Test fun unsafeRedirectTargetsAndEmptyDnsNeverConnect() {
        for (target in listOf("http://public.example/a", "https://127.0.0.1/a", "https://[::ffff:127.0.0.1]/a", "https://u:p@public.example/a", "https://public.example:444/a")) {
            var opened = 0
            val client = PublicWebClient(resolve = { listOf(ip("8.8.8.8")) }, transport = { _, _ -> opened++; response(302, location = target) })
            assertThrows(IllegalArgumentException::class.java) { client.get("https://public.example/a", 100, false) }
            assertEquals(1, opened)
        }
        var opened = 0
        val empty = PublicWebClient(resolve = { emptyList() }, transport = { _, _ -> opened++; response() })
        assertThrows(IllegalArgumentException::class.java) { empty.get("https://public.example/", 100, false) }
        assertEquals(0, opened)
        var lookups = 0
        val rebound = PublicWebClient(resolve = { if (++lookups == 1) listOf(ip("8.8.8.8")) else listOf(ip("127.0.0.1")) }, transport = { _, _ -> opened++; response(302, location = "/same-host") })
        assertThrows(IllegalArgumentException::class.java) { rebound.get("https://public.example/", 100, false) }
        assertEquals(1, opened); assertEquals(2, lookups)
    }
}
