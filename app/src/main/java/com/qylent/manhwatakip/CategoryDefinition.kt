package com.qylent.manhwatakip

data class CategoryDefinition(val name: String, val colorId: String = "green", val position: Int = 0) {
    fun normalized(): CategoryDefinition {
        require(validColor(colorId) && position >= 0) { "Kategori rengi veya sırası geçersiz." }
        return copy(name = CategoryNames.normalize(name), colorId = if (colorId.startsWith("#")) colorId.uppercase(java.util.Locale.ROOT) else colorId)
    }
    companion object {
        val colors = listOf("green", "blue", "amber", "purple", "rose", "gray", "red", "orange", "yellow", "teal", "cyan", "pink", "brown")
        val colorLabels = listOf("Yeşil", "Mavi", "Amber", "Mor", "Gül", "Gri", "Kırmızı", "Turuncu", "Sarı", "Turkuaz", "Camgöbeği", "Pembe", "Kahverengi")
        fun validColor(value: String) = value in colors || value.matches(Regex("#[0-9a-fA-F]{6}"))
        fun colorLabel(value: String) = colors.indexOf(value).let { if (it >= 0) colorLabels[it] else value }
        fun validate(items: List<CategoryDefinition>): List<CategoryDefinition> = items.map { it.normalized() }.also {
            require(it.map { item -> CategoryNames.key(item.name) }.distinct().size == it.size &&
                it.map { item -> item.position }.distinct().size == it.size) { "Yedekte mükerrer kategori veya sıra var." }
        }.sortedBy { it.position }
    }
}

data class LibraryBackup(val records: List<Series>, val categories: List<CategoryDefinition>)
