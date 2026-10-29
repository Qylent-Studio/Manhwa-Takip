package com.qylent.manhwatakip

import java.net.URI

object SharedReadingLink {
    fun extract(text: String): String {
        require(text.length in 1..8192 && !text.any { it == '\u0000' }) { "Paylaşılan metin geçersiz veya çok uzun." }
        val links = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE).findAll(text).map { it.value.trimEnd('.', ',', ')', ']') }.distinct().toList()
        require(links.size == 1) { "Tek bir okuma bağlantısı paylaşın." }
        val url = links.single()
        val uri = URI(url)
        require(url.length <= 2048 && uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null) { "Geçerli bir HTTPS okuma bağlantısı gerekiyor." }
        return url
    }
}
