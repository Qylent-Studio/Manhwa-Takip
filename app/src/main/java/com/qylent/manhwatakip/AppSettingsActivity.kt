package com.qylent.manhwatakip

import android.app.AlertDialog
import android.os.Bundle

class AppSettingsActivity : LibraryActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shell("Uygulama ayarları")
        val column = Ui.scroll(this, page)
        Ui.label(this, column, "Görünüm")
        column.addView(Ui.button(this, "Tema seç") {
            val modes = listOf("system", "light", "dark")
            AlertDialog.Builder(this).setTitle("Tema").setSingleChoiceItems(arrayOf("Sistem", "Açık", "Karanlık"), modes.indexOf(AppPreferences(this).theme)) { dialog, which ->
                io({ AppPreferences(this).theme = modes[which] }) { dialog.dismiss(); recreate() }
            }.setNegativeButton("Vazgeç", null).show()
        })
        Ui.label(this, column, "Izgaradaki kart sayısını ve uygulamanın yazı boyutunu seçebilirsin. Telefonun yazı boyutu ayarı da korunur.")
        column.addView(Ui.button(this, "Satır başına kart") {
            val preferences = AppPreferences(this)
            AlertDialog.Builder(this).setTitle("Satır başına kart")
                .setSingleChoiceItems(arrayOf("2 kart", "3 kart"), (preferences.gridColumns ?: if (resources.configuration.screenWidthDp >= 600 || resources.configuration.screenWidthDp >= 360 && resources.configuration.fontScale <= 1.15f) 3 else 2) - 2) { dialog, which ->
                    io({ preferences.gridColumns = which + 2 }) { dialog.dismiss(); recreate() }
                }.setNegativeButton("Vazgeç", null).show()
        })
        column.addView(Ui.button(this, "Yazı boyutu") {
            val preferences = AppPreferences(this)
            val sizes = listOf("normal", "large", "extra_large")
            AlertDialog.Builder(this).setTitle("Yazı boyutu")
                .setSingleChoiceItems(arrayOf("Normal", "Büyük", "Çok büyük"), sizes.indexOf(preferences.textSize)) { dialog, which ->
                    io({ preferences.textSize = sizes[which] }) { dialog.dismiss(); recreate() }
                }.setNegativeButton("Vazgeç", null).show()
        })
        column.addView(Ui.button(this, "Kategoriler") { startActivity(android.content.Intent(this, CategoriesActivity::class.java)) })
    }
}
