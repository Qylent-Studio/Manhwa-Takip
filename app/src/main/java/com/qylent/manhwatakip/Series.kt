package com.qylent.manhwatakip

import java.util.UUID
import java.util.Locale
import java.net.URI
import java.math.BigDecimal
import java.text.Normalizer

enum class ReadingStatus(val label: String) {
    PLANNED("Okunacak"), READING("Okunuyor"), PAUSED("Ara verildi"), DROPPED("Bırakıldı"), COMPLETED("Tamamlandı")
}
enum class Opinion(val label: String) { UNSET("Belirtilmedi"), LIKED("Sevdim"), DISLIKED("Sevmedim") }

data class Series(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String = "",
    val lastChapter: String = "",
    val status: ReadingStatus = ReadingStatus.PLANNED,
    val opinion: Opinion = Opinion.UNSET,
    val likedReason: String = "",
    val dislikedReason: String = "",
    val dropReason: String = "",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val categories: List<String> = emptyList(),
    val alternativeTitles: List<String> = emptyList(),
    val coverUrl: String = "",
    val favorite: Boolean = false,
    val pinned: Boolean = false,
) {
    fun normalized(): Series {
        require(id.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) { "Kayıt kimliği geçersiz." }
        val name = title.trim()
        require(name.isNotEmpty() && name.length <= 200 && name.none { it.isISOControl() }) { "Seri adı boş olamaz ve en fazla 200 karakter olabilir." }
        val link = url.trim()
        require(link.length <= 2048) { "Okuma bağlantısı çok uzun." }
        if (link.isNotEmpty()) {
            val uri = try { URI(link) } catch (_: Exception) { throw IllegalArgumentException("Geçerli bir http veya https bağlantısı gir.") }
            require(uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrEmpty() && uri.userInfo == null) {
                "Geçerli bir http veya https bağlantısı gir; bağlantıda parola bulunmamalı."
            }
        }
        val chapter = lastChapter.trim().replace(',', '.')
        require(chapter.isEmpty() || (chapter.length <= 20 && chapter.matches(Regex("[0-9]+(\\.[0-9]+)?")))) {
            "Son bölüm boş veya negatif olmayan bir sayı olmalı (ör. 48,5)."
        }
        require(listOf(likedReason, dislikedReason, dropReason).all { it.length <= 2000 && '\u0000' !in it }) { "Neden alanları en fazla 2000 karakter olabilir." }
        require(notes.length <= 4000 && '\u0000' !in notes) { "Not en fazla 4000 karakter olabilir." }
        require(createdAt >= 0 && updatedAt >= createdAt) { "Kayıt tarihleri geçersiz." }
        val labels = categories.map(CategoryNames::normalize)
        require(labels.map(CategoryNames::key).distinct().size == labels.size) { "Aynı özel kategori iki kez seçilemez." }
        require(alternativeTitles.size <= 100) { "En fazla 100 alternatif ad eklenebilir." }
        val aliases = alternativeTitles.map { value ->
            require(value.none { it.isISOControl() }) { "Alternatif ad kontrol karakteri içeremez." }
            value.trim().also { require(it.isNotEmpty() && it.length <= 200) { "Alternatif ad 1–200 karakter olmalı." } }
        }
        require(aliases.map(::fold).distinct().size == aliases.size) { "Aynı alternatif ad iki kez eklenemez." }
        require(coverUrl.length <= 2048) { "Kapak bağlantısı çok uzun." }
        if (coverUrl.isNotEmpty()) {
            val cover = runCatching { URI(coverUrl) }.getOrNull()
            require(cover?.scheme?.lowercase(Locale.ROOT) == "https" && !cover.host.isNullOrEmpty() && cover.userInfo == null) { "Kapak için geçerli bir HTTPS bağlantısı gerekir." }
        }
        return copy(id = id.lowercase(Locale.ROOT), title = name, url = link,
            categories = labels, alternativeTitles = aliases,
            lastChapter = if (chapter.isEmpty()) "" else BigDecimal(chapter).stripTrailingZeros().toPlainString())
    }

    fun duplicateKey(): String = fold(title.trim().replace(Regex("\\s+"), " ")) + "\u0000" + url.trim()
    fun matches(query: String): Boolean = (listOf(title) + alternativeTitles).any { fold(it).contains(fold(query.trim())) }

    fun wireProjection(): Series = copy(alternativeTitles = emptyList(), coverUrl = "", favorite = false, pinned = false)
    fun keepLocalFields(local: Series): Series = copy(alternativeTitles = local.alternativeTitles, coverUrl = local.coverUrl, favorite = local.favorite, pinned = local.pinned)

    private fun fold(value: String) = Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.forLanguageTag("tr-TR"))
}

object CategoryNames {
    fun normalize(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFC).also {
        require(it.isNotEmpty() && it.length <= 80 && it.none { c -> c.isISOControl() }) { "Kategori adı 1–80 karakter olmalı." }
    }
    fun key(value: String) = normalize(value).lowercase(Locale.forLanguageTag("tr-TR"))
}
