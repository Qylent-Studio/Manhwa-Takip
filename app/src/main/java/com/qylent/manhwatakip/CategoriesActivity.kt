package com.qylent.manhwatakip

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ArrayAdapter

class CategoriesActivity : LibraryActivity() {
    private lateinit var list: LinearLayout
    private lateinit var search: android.widget.EditText
    private lateinit var filter: Spinner
    private var filterColors = listOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shell("Kategoriler")
        val column = Ui.scroll(this, page)
        column.addView(Ui.text(this, "Kütüphaneni kendi kategorilerinle düzenle. Renkleri seç, sırayı değiştir; her seriye birden fazla kategori ekleyebilirsin.", 14f).apply { setTextColor(Ui.muted) })
        Ui.gap(this, column, 16)
        val create = Ui.button(this, "Kategori oluştur", true) { edit(null) }
        column.addView(create); actionButtons.add(create)
        search = Ui.input(this, "Kategori ara", 80)
        column.addView(search); Ui.watch(search) { reload() }
        filter = Spinner(this).apply { contentDescription = "Kategori renk filtresi" }
        column.addView(filter)
        filter.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) { reload() }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        Ui.gap(this, column, 20)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(list)
    }
    override fun onResume() { super.onResume(); reload() }
    private fun reload(): Unit = io({ store.categoryDefinitions() to store.all() }) { (definitions, records) ->
        val colors = listOf<String?>(null) + definitions.map { it.colorId }.distinct()
        if (colors != filterColors || filter.adapter == null) {
            val old = filterColors.getOrNull(filter.selectedItemPosition)
            filterColors = colors
            filter.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, colors.map { it?.let(CategoryDefinition::colorLabel) ?: "Tüm renkler" })
            filter.setSelection(colors.indexOf(old).coerceAtLeast(0))
        }
        list.removeAllViews()
        while (actionButtons.size > 1) actionButtons.removeAt(actionButtons.lastIndex)
        if (definitions.isEmpty()) list.addView(Ui.text(this, "Henüz kategori yok. İlk kategorini oluştur.", 16f))
        val counts = records.flatMap { it.categories }.groupingBy(CategoryNames::key).eachCount()
        definitions.forEachIndexed { index, category ->
            val query = search.text.toString().trim().lowercase(java.util.Locale.forLanguageTag("tr-TR"))
            val selectedColor = filterColors.getOrNull(filter.selectedItemPosition)
            if (!category.name.lowercase(java.util.Locale.forLanguageTag("tr-TR")).contains(query) || selectedColor != null && selectedColor != category.colorId) return@forEachIndexed
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; background = Ui.surface()
                setPadding(with(Ui) { dp(16) }, with(Ui) { dp(14) }, with(Ui) { dp(16) }, with(Ui) { dp(14) })
            }
            val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            heading.addView(Ui.text(this, "●", 24f).apply { setTextColor(Ui.categoryColor(category.colorId)); importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                LinearLayout.LayoutParams(with(Ui) { dp(32) }, ViewGroup.LayoutParams.WRAP_CONTENT))
            heading.addView(Ui.text(this, category.name, 18f, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(heading)
            val count = counts[CategoryNames.key(category.name)] ?: 0
            val colorName = CategoryDefinition.colorLabel(category.colorId)
            row.addView(Ui.text(this, "$count seri · $colorName", 13f).apply { setTextColor(Ui.muted) })
            Ui.gap(this, row, 10)
            val manage = Ui.button(this, "Düzenle") {
                val actions = mutableListOf("Ad ve renk")
                if (index > 0) actions.add("Yukarı taşı")
                if (index < definitions.lastIndex) actions.add("Aşağı taşı")
                actions.add("Kategoriyi kaldır")
                AlertDialog.Builder(this).setTitle(category.name).setItems(actions.toTypedArray()) { _, which ->
                    when (actions[which]) {
                        "Ad ve renk" -> edit(category)
                        "Yukarı taşı" -> io({ store.moveCategory(category.name, -1) }) { reload() }
                        "Aşağı taşı" -> io({ store.moveCategory(category.name, 1) }) { reload() }
                        else -> AlertDialog.Builder(this).setTitle("Kategori kaldırılsın mı?")
                            .setMessage("${category.name} serilerden kaldırılır. Seriler, okuma durumları ve notları korunur.")
                            .setNegativeButton("Vazgeç", null).setPositiveButton("Kaldır") { _, _ -> io({ store.changeCategory(category.name, null) }) { reload() } }.show()
                    }
                }.show()
            }.apply { contentDescription = "Kategoriyi yönet: ${category.name}" }
            row.addView(manage); actionButtons.add(manage)
            list.addView(row); Ui.gap(this, list, 12)
        }
    }
    private fun edit(category: CategoryDefinition?) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(with(Ui) { dp(20) }, with(Ui) { dp(8) }, with(Ui) { dp(20) }, with(Ui) { dp(8) })
        }
        Ui.label(this, content, "Kategori adı (1–80 karakter)")
        val name = Ui.input(this, "Kategori adı", 80).apply { setText(category?.name ?: "") }
        content.addView(name)
        Ui.label(this, content, "Renk")
        var shownColors = CategoryDefinition.colors + listOfNotNull(category?.colorId?.takeIf { it.startsWith("#") })
        val colorSearch = Ui.input(this, "Renk ara (ad veya kod)", 80)
        content.addView(colorSearch)
        val color = Spinner(this).apply {
            contentDescription = "Kategori rengi"; minimumHeight = with(Ui) { dp(48) }
            adapter = ArrayAdapter(this@CategoriesActivity, android.R.layout.simple_spinner_dropdown_item, shownColors.map(CategoryDefinition::colorLabel))
            setSelection(shownColors.indexOf(category?.colorId ?: "green").coerceAtLeast(0))
        }
        content.addView(color)
        Ui.watch(colorSearch) {
            val query = colorSearch.text.toString().trim().lowercase(java.util.Locale.forLanguageTag("tr-TR"))
            val old = shownColors.getOrNull(color.selectedItemPosition)
            shownColors = CategoryDefinition.colors.filter { CategoryDefinition.colorLabel(it).lowercase(java.util.Locale.forLanguageTag("tr-TR")).contains(query) || it.contains(query) }
            if (query.startsWith("#") && CategoryDefinition.validColor(query)) shownColors = listOf(query.uppercase(java.util.Locale.ROOT))
            color.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, shownColors.map(CategoryDefinition::colorLabel))
            color.setSelection(shownColors.indexOf(old).coerceAtLeast(0))
        }
        Ui.label(this, content, "Özel renk (isteğe bağlı)")
        val custom = Ui.input(this, "#RRGGBB", 7).apply { setText(category?.colorId?.takeIf { it.startsWith("#") } ?: "") }
        content.addView(custom)
        val swatch = Ui.text(this, "● Renk önizlemesi", 20f)
        content.addView(swatch)
        fun preview() { val value = custom.text.toString().ifBlank { shownColors.getOrNull(color.selectedItemPosition) ?: "green" }; if (CategoryDefinition.validColor(value)) swatch.setTextColor(Ui.categoryColor(value)) }
        Ui.watch(custom, ::preview)
        color.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                shownColors.getOrNull(position)?.let { custom.setText(if (it.startsWith("#")) it else "") }; preview()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        preview()
        val dialog = AlertDialog.Builder(this).setTitle(if (category == null) "Yeni kategori" else "Kategoriyi düzenle")
            .setView(content).setNegativeButton("Vazgeç", null).setPositiveButton("Kaydet", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (busy) return@setOnClickListener
                val normalized = runCatching { CategoryNames.normalize(name.text.toString()) }.getOrElse { name.error = it.message; return@setOnClickListener }
                val colorId = custom.text.toString().trim().uppercase(java.util.Locale.ROOT).ifBlank { shownColors.getOrNull(color.selectedItemPosition) ?: "" }
                if (!CategoryDefinition.validColor(colorId)) { custom.error = "Geçerli #RRGGBB rengi gir veya listeden renk seç."; return@setOnClickListener }
                io({
                    if (category == null) {
                        require(store.categories().none { CategoryNames.key(it) == CategoryNames.key(normalized) }) { "Bu kategori zaten var." }
                        store.addCategory(normalized, colorId)
                    } else store.changeCategory(category.name, normalized, colorId)
                }) { dialog.dismiss(); reload() }
            }
        }
        dialog.show()
    }
}
