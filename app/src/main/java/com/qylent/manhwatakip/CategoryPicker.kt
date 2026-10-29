package com.qylent.manhwatakip

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.*

object CategoryPicker {
    fun show(activity: Activity, definitions: List<CategoryDefinition>, selected: List<String>, onSelected: (List<String>) -> Unit) {
        val choices = selected.toMutableList()
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 12, 20, 12) }
        val query = Ui.input(activity, "Kategori ara", 80)
        content.addView(query)
        val colors = listOf<String?>(null) + definitions.map { it.colorId }.distinct()
        val filter = Spinner(activity).apply {
            contentDescription = "Kategori renk filtresi"
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, colors.map { it?.let(CategoryDefinition::colorLabel) ?: "Tüm renkler" })
        }
        content.addView(filter)
        val summary = Ui.text(activity, "", 14f)
        content.addView(summary)
        val list = ListView(activity).apply { dividerHeight = 0 }
        content.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, with(Ui) { activity.dp(250) }))
        var visible = definitions
        val rows = object : BaseAdapter() {
            override fun getCount() = visible.size
            override fun getItem(position: Int) = visible[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val category = visible[position]
                return CheckBox(activity).apply {
                    text = category.name; setTextColor(Ui.ink); minHeight = with(Ui) { activity.dp(48) }
                    buttonTintList = android.content.res.ColorStateList.valueOf(Ui.categoryColor(category.colorId))
                    isChecked = choices.any { CategoryNames.key(it) == CategoryNames.key(category.name) }
                    setOnCheckedChangeListener { _, checked ->
                        choices.removeAll { CategoryNames.key(it) == CategoryNames.key(category.name) }
                        if (checked) choices.add(category.name)
                        summary.text = "${choices.size} kategori seçili"
                    }
                }
            }
        }
        list.adapter = rows
        fun refresh() {
            val text = query.text.toString().trim().lowercase(java.util.Locale.forLanguageTag("tr-TR"))
            val color = colors.getOrNull(filter.selectedItemPosition)
            visible = definitions.filter { it.name.lowercase(java.util.Locale.forLanguageTag("tr-TR")).contains(text) && (color == null || it.colorId == color) }
            summary.text = "${choices.size} kategori seçili · ${visible.size} sonuç"
            rows.notifyDataSetChanged()
        }
        Ui.watch(query, ::refresh)
        filter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = refresh()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        refresh()
        AlertDialog.Builder(activity).setTitle("Kategoriler").setView(content).setNegativeButton("Vazgeç", null)
            .setPositiveButton("Seçimi uygula") { _, _ -> onSelected(choices.toList()) }.show()
    }
}
