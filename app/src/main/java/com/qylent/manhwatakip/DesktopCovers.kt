package com.qylent.manhwatakip

import org.json.JSONArray
import java.net.URI
import java.util.Base64
import java.util.Locale

data class DesktopCover(val proposal: AiProposal, val imageUrl: String, val jpeg: String, val replaceExisting: Boolean = false)
class PartialCoverTransfer(val applied: Int) : IllegalArgumentException("Kapak aktarımı tamamlanamadı. $applied kapak eklendi; kalan taslaklar bilgisayarda korunur.")

object DesktopCovers {
    fun preview(changes: JSONArray, current: List<Series>, manual: Boolean = false): List<DesktopCover> {
        require(changes.length() in 1..10_000) { "Kapak listesi boş veya çok büyük." }
        val byId = current.associateBy { it.id }; val seen = mutableSetOf<String>()
        return (0 until changes.length()).map { index ->
            val item = changes.getJSONObject(index)
            val keys = item.keys().asSequence().toSet()
            val required = if (manual) setOf("id", "expectedUpdatedAt", "jpeg") else setOf("id", "expectedUpdatedAt", "imageUrl", "jpeg")
            require(keys == required || !manual && keys == required + "replaceExisting") { "Kapak paketi geçersiz." }
            require(!item.has("replaceExisting") || item.get("replaceExisting") is Boolean) { "Kapak değiştirme onayı geçersiz." }
            val id = item.get("id"); require(id is String && seen.add(id)) { "Kapak kimliği geçersiz veya mükerrer." }
            val before = requireNotNull(byId[id]) { "Seri silinmiş veya bulunamadı. Bilgisayardan yenile." }
            val expected = item.get("expectedUpdatedAt")
            require((expected is Long || expected is Int) && (expected as Number).toLong() == before.updatedAt) { "Telefon kaydı değişmiş. Bilgisayardan yenile ve kapağı tekrar incele." }
            val jpeg = item.get("jpeg")
            require(jpeg is String && jpeg.length in 1..(7 * 1024 * 1024)) { "Kapak alanları geçersiz." }
            // Keep backup compatibility; this reserved local marker is never a network source.
            val url = if (manual) "https://manhwa-takip.invalid/local-cover/" + java.security.MessageDigest.getInstance("SHA-256")
                .digest(Base64.getDecoder().decode(jpeg)).joinToString("") { "%02x".format(it.toInt() and 255) } + ".jpg" else item.get("imageUrl")
            require(url is String) { "Kapak alanları geçersiz." }
            val uri = URI(url); val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
            val source = host.removePrefix("www.") in setOf("golgebahcesi.com", "eldermanga.com", "uzaymanga.com", "okutoon.com", "trmanga.com") ||
                host == "uploads.mangadex.org" || host == "cdn.trmanga.com" || host == "skycdn.online" || host.endsWith(".skycdn.online") || host.endsWith(".okutoon.com")
            require(manual || uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) && source) { "Kapak kaynağı desteklenmiyor." }
            DesktopCover(AiProposal(before, before.copy(coverUrl = url).normalized()), url, jpeg, manual || item.optBoolean("replaceExisting", false))
        }
    }

    fun apply(changes: List<DesktopCover>, store: LibraryStore, covers: CoverStore): Int {
        var size = store.all().sumOf { covers.bytes(it.id)?.size ?: 0 }
        val images = changes.map { cover ->
            require(store.get(cover.proposal.after.id) == cover.proposal.before) { "Kayıt onaydan sonra değişti; kapaklar uygulanmadı." }
            val previous = covers.bytes(cover.proposal.after.id)
            require(previous == null || cover.replaceExisting) { "Telefonda kapak zaten var; mevcut kapak korunur. Bilgisayardan yenile." }
            val bytes = Base64.getDecoder().decode(cover.jpeg)
            size += bytes.size - (previous?.size ?: 0); require(size <= 20 * 1024 * 1024) { "Kapak paketi 20 MB sınırını aşıyor." }
            require(bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()) { "Kapak JPEG olmalı." }
            CoverStore.decode(bytes).recycle(); bytes
        }
        var applied = 0
        changes.zip(images).forEach { (cover, bytes) ->
            try {
                val previous = covers.bytes(cover.proposal.after.id)
                // shortcut: files and SQLite are separate; restore the image if the record write fails.
                covers.save(cover.proposal.after.id, bytes)
                try { store.applyProposal(cover.proposal) }
                catch (error: Exception) { covers.restore(cover.proposal.after.id, previous); throw error }
                applied++
            } catch (_: Exception) { throw PartialCoverTransfer(applied) }
        }
        return applied
    }
}
