package com.qylent.manhwatakip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import java.io.File
import java.nio.ByteBuffer
import org.aomedia.avif.android.AvifDecoder

class CoverStore(context: Context) {
    private val directory = File(context.filesDir, "covers").apply { mkdirs() }
    private val cache = object : android.util.LruCache<String, Bitmap>(8192) { override fun sizeOf(key: String, value: Bitmap) = (value.byteCount / 1024).coerceAtLeast(1) }
    private fun file(id: String): File {
        require(id.matches(Regex("[0-9a-fA-F-]{36}"))) { "Kapak kimliği geçersiz." }
        return File(directory, "$id.jpg")
    }
    fun bytes(id: String): ByteArray? = file(id).takeIf { it.isFile }?.let { require(it.length() <= 5 * 1024 * 1024); it.readBytes() }
    fun contains(id: String): Boolean = file(id).let { it.isFile && it.length() > 0 }
    fun remove(id: String) { android.util.AtomicFile(file(id)).delete(); cache.remove(id) }
    fun load(id: String): Bitmap? = cache.get(id) ?: bytes(id)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.also { cache.put(id, it) }
    fun bind(activity: android.app.Activity, view: android.widget.ImageView, id: String) {
        view.tag = id; view.setImageDrawable(null)
        val cached = cache.get(id)
        if (cached != null) { view.setImageBitmap(cached); return }
        (activity.application as LibraryApplication).networkExecutor.execute {
            val bitmap = runCatching { load(id) }.getOrNull()
            activity.runOnUiThread { if (!activity.isDestroyed && view.tag == id) view.setImageBitmap(bitmap) }
        }
    }
    fun save(id: String, bytes: ByteArray) {
        val bitmap = decode(bytes)
        try { write(id) { output -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)) } }
        finally { bitmap.recycle() }
    }
    internal fun restore(id: String, bytes: ByteArray?) {
        if (bytes == null) remove(id) else write(id) { it.write(bytes) }
    }
    private fun write(id: String, block: (java.io.OutputStream) -> Unit) {
        val atomic = AtomicFile(file(id)); val output = atomic.startWrite()
        try { block(output); atomic.finishWrite(output); cache.remove(id) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
    }
    companion object {
        fun decode(bytes: ByteArray): Bitmap {
            require(bytes.isNotEmpty() && bytes.size <= 5 * 1024 * 1024) { "Kapak dosyası geçersiz veya çok büyük." }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth > 0) {
                require(bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000) { "Kapak çözünürlüğü çok büyük." }
                val options = BitmapFactory.Options().apply { inSampleSize = 1 }
                while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 1024) options.inSampleSize *= 2
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw IllegalArgumentException("Kapak görseli açılamadı.")
            }
            val buffer = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); position(0) }
            val info = AvifDecoder.Info()
            require(AvifDecoder.getInfo(buffer, bytes.size, info) && info.width > 0 && info.height > 0 && info.width.toLong() * info.height <= 16_000_000) { "Kapak görseli desteklenmiyor veya çok büyük." }
            val scale = minOf(1.0, 768.0 / maxOf(info.width, info.height))
            val bitmap = Bitmap.createBitmap((info.width * scale).toInt().coerceAtLeast(1), (info.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            if (!AvifDecoder.decode(buffer, bytes.size, bitmap, 1)) { bitmap.recycle(); throw IllegalArgumentException("AVIF kapak açılamadı.") }
            return bitmap
        }
    }
}
