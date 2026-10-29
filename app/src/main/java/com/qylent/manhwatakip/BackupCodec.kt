package com.qylent.manhwatakip

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.json.JSONException

object BackupCodec {
    private const val MAX_BYTES = 8 * 1024 * 1024

    fun validate(entries: List<Series>): List<Series> {
        require(entries.size <= 10_000) { "Yedek en fazla 10.000 kayıt içerebilir." }
        val normalized = entries.map { it.normalized() }
        require(normalized.map { it.id }.toSet().size == normalized.size &&
            normalized.map { it.duplicateKey() }.toSet().size == normalized.size) { "Yedekte mükerrer kayıt var." }
        return normalized
    }

    fun encode(entries: List<Series>, exportedAt: Long = System.currentTimeMillis()): String {
        require(exportedAt >= 0) { "Yedek tarihi geçersiz." }
        val records = JSONArray()
        validate(entries).forEach { entry ->
            records.put(JSONObject().put("id", entry.id).put("title", entry.title).put("url", entry.url)
                .put("lastChapter", entry.lastChapter).put("status", entry.status.name).put("opinion", entry.opinion.name)
                .put("likedReason", entry.likedReason).put("dislikedReason", entry.dislikedReason)
                .put("dropReason", entry.dropReason).put("notes", entry.notes)
                .put("categories", JSONArray(entry.categories))
                .put("createdAt", entry.createdAt).put("updatedAt", entry.updatedAt))
        }
        val text = JSONObject().put("format", "manhwa-takip").put("version", 2)
            .put("exportedAt", exportedAt).put("records", records).toString(2)
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Yedek dosyası 8 MiB sınırını aşıyor." }
        return text
    }

    fun encodeLibrary(entries: List<Series>, categories: List<CategoryDefinition>): String {
        val definitions = CategoryDefinition.validate(categories)
        val keys = definitions.mapTo(mutableSetOf()) { CategoryNames.key(it.name) }
        require(entries.flatMap { it.categories }.all { CategoryNames.key(it) in keys }) { "Yedekte kategori tanımı eksik." }
        val root = JSONObject(encode(entries)).put("version", 4).put("categoryDefinitions", JSONArray().apply {
            definitions.forEach { put(JSONObject().put("name", it.name).put("colorId", it.colorId).put("position", it.position)) }
        })
        entries.forEachIndexed { index, entry -> root.getJSONArray("records").getJSONObject(index).put("local", localFields(entry)) }
        return root.toString(2).also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Yedek dosyası 8 MiB sınırını aşıyor." } }
    }

    fun decode(text: String): List<Series> = decodeLibrary(text).records
    fun encodeLocal(entries: List<Series>): String = encodeLibrary(entries, entries.flatMap { it.categories }
        .distinctBy(CategoryNames::key).mapIndexed { index, name -> CategoryDefinition(name, position = index) })
    fun decodeLibrary(text: String): LibraryBackup {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Yedek dosyası 8 MiB sınırını aşıyor." }
        try {
            val tokener = JSONTokener(text)
            val root = tokener.nextValue()
            require(root is JSONObject && tokener.nextClean() == '\u0000') { "Geçerli bir JSON yedeği seç." }
            require(root.string("format") == "manhwa-takip" && root.long("version") in 1L..4L) { "Bu yedek biçimi veya sürümü desteklenmiyor." }
            require(root.long("exportedAt") >= 0) { "Yedek tarihi geçersiz." }
            val records = root.get("records")
            require(records is JSONArray && records.length() <= 10_000) { "Yedek kayıt listesi geçersiz veya çok büyük." }
            val entries = (0 until records.length()).map { index ->
                val record = records.get(index)
                require(record is JSONObject) { "Yedekteki kayıt biçimi geçersiz." }
                val statusName = record.string("status")
                val opinionName = record.string("opinion")
                val status = ReadingStatus.entries.firstOrNull { it.name == statusName }
                    ?: throw IllegalArgumentException("Yedekte okuma durumu geçersiz; hiçbir kayıt değiştirilmedi.")
                val opinion = Opinion.entries.firstOrNull { it.name == opinionName }
                    ?: throw IllegalArgumentException("Yedekte beğeni durumu geçersiz; hiçbir kayıt değiştirilmedi.")
                Series(id = record.string("id"), title = record.string("title"), url = record.string("url"),
                    lastChapter = record.string("lastChapter"), status = status,
                    opinion = opinion, likedReason = record.string("likedReason"),
                    dislikedReason = record.string("dislikedReason"), dropReason = record.string("dropReason"),
                    notes = record.string("notes"), createdAt = record.long("createdAt"), updatedAt = record.long("updatedAt"),
                    categories = if (record.has("categories")) categoryList(record.get("categories")) else {
                        require(root.long("version") == 1L) { "Yedekte özel kategoriler eksik." }; emptyList()
                    }).let { if (root.long("version") == 4L) withLocalFields(it, record.getJSONObject("local")) else it }
            }
            val valid = validate(entries)
            val definitions = if (root.long("version") >= 3L) {
                val items = root.get("categoryDefinitions")
                require(items is JSONArray) { "Yedekte kategori tanımları eksik." }
                CategoryDefinition.validate((0 until items.length()).map { index ->
                    val item = items.get(index)
                    require(item is JSONObject) { "Kategori tanımı geçersiz." }
                    val position = item.long("position")
                    require(position in 0..Int.MAX_VALUE.toLong()) { "Kategori sırası geçersiz." }
                    CategoryDefinition(item.string("name"), item.string("colorId"), position.toInt())
                }).also { definitions ->
                    val keys = definitions.mapTo(mutableSetOf()) { CategoryNames.key(it.name) }
                    require(valid.flatMap { it.categories }.all { CategoryNames.key(it) in keys }) { "Yedekte kategori tanımı eksik." }
                }
            } else valid.flatMap { it.categories }.distinctBy(CategoryNames::key).sorted().mapIndexed { index, name -> CategoryDefinition(name, position = index) }
            return LibraryBackup(valid, definitions)
        } catch (_: JSONException) {
            throw IllegalArgumentException("Yedek eksik veya bozuk; hiçbir kayıt değiştirilmedi.")
        }
    }

    fun categoryList(value: Any): List<String> {
        require(value is JSONArray) { "Özel kategori listesi geçersiz." }
        return (0 until value.length()).map { index ->
            val name = value.get(index)
            require(name is String) { "Özel kategori adı geçersiz." }
            CategoryNames.normalize(name)
        }.also { require(it.map(CategoryNames::key).distinct().size == it.size) { "Mükerrer özel kategori var." } }
    }

    fun localFields(entry: Series): JSONObject = JSONObject().put("alternativeTitles", JSONArray(entry.alternativeTitles))
        .put("coverUrl", entry.coverUrl).put("favorite", entry.favorite).put("pinned", entry.pinned)

    fun withLocalFields(entry: Series, data: JSONObject): Series {
        if (data.length() == 0) return entry
        val names = data.get("alternativeTitles")
        require(names is JSONArray && names.length() <= 100) { "Alternatif ad listesi geçersiz." }
        val aliases = (0 until names.length()).map { names.get(it).also { value -> require(value is String) } as String }
        require(data.get("favorite") is Boolean && data.get("pinned") is Boolean) { "Yerel seri alanı geçersiz." }
        return entry.copy(alternativeTitles = aliases, coverUrl = data.string("coverUrl"), favorite = data.getBoolean("favorite"), pinned = data.getBoolean("pinned"))
    }

    fun read(input: InputStream): List<Series> = readLibrary(input).records
    fun readLibrary(input: InputStream): LibraryBackup {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            require(bytes.size() + count <= MAX_BYTES) { "Yedek dosyası 8 MiB sınırını aşıyor." }
            bytes.write(buffer, 0, count)
        }
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("Yedek UTF-8 biçiminde değil.")
        }
        return decodeLibrary(text)
    }

    private fun JSONObject.string(name: String): String {
        val value = get(name)
        require(value is String) { "Yedekte alan türü geçersiz." }
        return value
    }

    private fun JSONObject.long(name: String): Long {
        val value = get(name)
        require(value is Int || value is Long) { "Yedekte sayı alanı geçersiz." }
        return (value as Number).toLong()
    }
}
