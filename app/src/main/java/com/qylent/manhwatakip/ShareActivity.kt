package com.qylent.manhwatakip

import android.content.Intent
import android.os.Bundle

class ShareActivity : LibraryActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shell("Paylaşılan bağlantı")
        val url = runCatching {
            require(intent.action == Intent.ACTION_SEND && intent.type == "text/plain")
            SharedReadingLink.extract(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: "")
        }.getOrElse { notice(it.message ?: "Tek bir HTTPS okuma bağlantısı paylaşın."); finish(); return }
        io({ store.all().firstOrNull { it.url == url } }) { existing ->
            startActivity(if (existing == null) Intent(this, EditActivity::class.java).putExtra("readingUrl", url)
                else Intent(this, DetailActivity::class.java).putExtra("seriesId", existing.id))
            finish()
        }
    }
}
