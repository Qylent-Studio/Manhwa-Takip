package com.qylent.manhwatakip

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout

/** Lightweight native navigation; active targets never recreate a conversation. */
object LibraryNavigation {
    enum class Destination(val label: String, val description: String, val icon: Int, val target: Class<*>) {
        LIBRARY("Kütüphane", "Kütüphane", R.drawable.ic_library, MainActivity::class.java),
        HISTORY("Geçmiş", "Okuma geçmişi", R.drawable.ic_history, HistoryActivity::class.java),
        CHAT("AI", "AI sohbeti", R.drawable.ic_chat, AiActivity::class.java),
        SETTINGS("Ayarlar", "Ayarlar", R.drawable.ic_settings, SettingsActivity::class.java)
    }
    fun create(activity: LibraryActivity, current: Destination, canNavigate: () -> Boolean): View = LinearLayout(activity).apply {
        tag = "libraryNavigation"
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = with(Ui) { activity.dp(64) }
        Destination.entries.forEach { destination ->
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                minimumHeight = with(Ui) { activity.dp(64) }
                contentDescription = "Gezinme: ${destination.description}"
                isFocusable = true; isSelected = destination == current
                background = RippleDrawable(ColorStateList.valueOf(Ui.userBubble), android.graphics.drawable.ColorDrawable(Ui.paper), null)
                addView(ImageView(activity).apply {
                    setImageResource(destination.icon)
                    imageTintList = ColorStateList.valueOf(if (destination == current) Ui.accent else Ui.muted)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(with(Ui) { activity.dp(20) }, with(Ui) { activity.dp(20) }))
                addView(Ui.text(activity, destination.label, 10f, destination == current).apply {
                    gravity = Gravity.CENTER
                    setTextColor(if (destination == current) Ui.accent else Ui.muted)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setPadding(0, with(Ui) { activity.dp(4) }, 0, 0)
                })
                setOnClickListener {
                    if (destination != current && canNavigate()) {
                        val intent = Intent(activity, destination.target)
                        if (destination == Destination.LIBRARY) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        activity.startActivity(intent)
                    }
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }
}
