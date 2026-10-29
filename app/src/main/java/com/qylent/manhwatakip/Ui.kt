package com.qylent.manhwatakip

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.*
import androidx.activity.ComponentActivity

/** Small shared native view helpers; library behavior lives outside the screens. */
object Ui {
    var dark = false
    val ink get() = Color.parseColor(if (dark) "#EDF3F8" else "#162438")
    val muted get() = Color.parseColor(if (dark) "#A9B9C6" else "#4C6077")
    val accent get() = Color.parseColor(if (dark) "#4CC7DF" else "#0563C1")
    val green get() = accent // Compatibility for existing secondary screens.
    val rule get() = Color.parseColor(if (dark) "#34424D" else "#B7C5D6")
    val paper get() = Color.parseColor(if (dark) "#0D1216" else "#EDF2F8")
    val panel get() = Color.parseColor(if (dark) "#192128" else "#DEE6F1")
    val userBubble get() = Color.parseColor(if (dark) "#253541" else "#CADBED")
    fun categoryColor(id: String): Int = when (id) {
        "blue" -> if (dark) Color.rgb(145, 192, 238) else Color.rgb(38, 85, 132)
        "amber" -> if (dark) Color.rgb(231, 194, 119) else Color.rgb(121, 80, 16)
        "purple" -> if (dark) Color.rgb(197, 173, 233) else Color.rgb(105, 67, 143)
        "rose" -> if (dark) Color.rgb(234, 166, 183) else Color.rgb(152, 55, 80)
        "gray" -> muted
        "red" -> if (dark) Color.rgb(255, 160, 160) else Color.rgb(175, 35, 40)
        "orange" -> if (dark) Color.rgb(255, 183, 110) else Color.rgb(150, 70, 10)
        "yellow" -> if (dark) Color.rgb(240, 218, 120) else Color.rgb(105, 88, 5)
        "teal" -> if (dark) Color.rgb(128, 218, 195) else Color.rgb(0, 100, 80)
        "cyan" -> if (dark) Color.rgb(122, 215, 235) else Color.rgb(0, 98, 120)
        "pink" -> if (dark) Color.rgb(245, 166, 211) else Color.rgb(148, 46, 104)
        "brown" -> if (dark) Color.rgb(202, 175, 154) else Color.rgb(100, 68, 48)
        else -> if (id.startsWith("#") && CategoryDefinition.validColor(id)) Color.parseColor(id) else green
    }
    fun categoryLabel(activity: Activity, name: String, colorId: String) = text(activity, name, 14f, true).apply {
        setTextColor(ink); background = surface()
        setCompoundDrawablesWithIntrinsicBounds(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(categoryColor(colorId)); setSize(activity.dp(12), activity.dp(12)) }, null, null, null)
        compoundDrawablePadding = activity.dp(8)
        setPadding(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(8))
    }
    fun Activity.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    fun watch(input: EditText, changed: () -> Unit) {
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = changed()
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
    }

    fun page(activity: Activity): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(paper)
        setPadding(activity.dp(20), activity.dp(12), activity.dp(20), activity.dp(12))
        setOnApplyWindowInsetsListener { view, insets ->
            val left: Int; val right: Int; val top: Int; val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                left = bars.left; right = bars.right; top = bars.top
                bottom = maxOf(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom)
            } else {
                @Suppress("DEPRECATION")
                left = insets.systemWindowInsetLeft
                @Suppress("DEPRECATION")
                right = insets.systemWindowInsetRight
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            view.setPadding(activity.dp(20) + left, activity.dp(12) + top, activity.dp(20) + right, activity.dp(12) + bottom)
            val keyboard = if (Build.VERSION.SDK_INT >= 30) insets.isVisible(WindowInsets.Type.ime()) else bottom > activity.dp(120)
            (view as ViewGroup).findViewWithTag<View>("libraryNavigation")?.let { nav ->
                nav.visibility = if (keyboard || nav.getTag(R.id.navigation_suppressed) == true) View.GONE
                    else if (view.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) View.INVISIBLE else View.VISIBLE
            }
            insets
        }
    }

    fun text(activity: Activity, value: String, size: Float = 16f, bold: Boolean = false) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(ink)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    fun button(activity: Activity, value: String, primary: Boolean = false, action: () -> Unit) = Button(activity).apply {
        text = value; isAllCaps = false; textSize = 15f
        minHeight = activity.dp(48)
        setPadding(activity.dp(16), activity.dp(10), activity.dp(16), activity.dp(10))
        setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(muted, if (primary) (if (dark) paper else Color.WHITE) else accent)))
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(userBubble),
            android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(-android.R.attr.state_enabled), surface(panel))
                addState(intArrayOf(), surface(if (primary) accent else panel))
            }, null)
        setOnClickListener { action() }
    }

    fun iconButton(activity: Activity, icon: Int, description: String, action: () -> Unit) = ImageButton(activity).apply {
        contentDescription = description
        setImageResource(icon)
        imageTintList = android.content.res.ColorStateList.valueOf(green)
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(userBubble), surface(), null)
        setPadding(activity.dp(12), activity.dp(12), activity.dp(12), activity.dp(12))
        minimumWidth = activity.dp(48); minimumHeight = activity.dp(48)
        setOnClickListener { action() }
    }

    fun surface(color: Int = panel) = GradientDrawable().apply {
        setColor(color); cornerRadius = 20f
        setStroke(1, rule)
    }

    fun input(activity: Activity, hintText: String, limit: Int, multiline: Boolean = false) = EditText(activity).apply {
        hint = hintText; textSize = 16f; setTextColor(ink); setHintTextColor(muted)
        isSingleLine = !multiline
        if (multiline) { minLines = 2; gravity = Gravity.TOP or Gravity.START }
        filters = arrayOf(InputFilter.LengthFilter(limit))
        setPadding(activity.dp(12), activity.dp(12), activity.dp(12), activity.dp(12))
        background = surface()
    }

    fun gap(activity: Activity, parent: LinearLayout, height: Int = 12) {
        parent.addView(View(activity), LinearLayout.LayoutParams(1, activity.dp(height)))
    }

    fun scroll(activity: Activity, parent: LinearLayout): LinearLayout {
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(ScrollView(activity).apply {
            isFillViewport = true
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return column
    }

    fun label(activity: Activity, column: LinearLayout, name: String) {
        gap(activity, column)
        column.addView(text(activity, name, 13f, true).apply { setTextColor(muted) })
        gap(activity, column, 6)
    }
}

abstract class LibraryActivity : ComponentActivity() {
    private var createdDark = false
    private var createdTextSize = "normal"
    override fun attachBaseContext(newBase: android.content.Context) {
        val preferences = AppPreferences(newBase)
        createdTextSize = preferences.textSize
        val configuration = android.content.res.Configuration(newBase.resources.configuration)
        configuration.fontScale *= preferences.textScale
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        createdDark = AppPreferences(this).isDark(this)
        Ui.dark = createdDark
        setTheme(if (createdDark) R.style.AppThemeDark else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (createdDark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
    }
    override fun onResume() {
        super.onResume()
        val preferences = AppPreferences(this)
        if (preferences.isDark(this) != createdDark || preferences.textSize != createdTextSize) recreate()
    }
    protected lateinit var page: LinearLayout
    private lateinit var loading: ProgressBar
    private var pending = 0
    protected val busy get() = pending > 0
    protected val actionButtons = mutableListOf<Button>()
    protected val store get() = (application as LibraryApplication).store

    protected fun shell(title: String, back: Boolean = true) {
        page = Ui.page(this)
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        if (back) heading.addView(Ui.iconButton(this, R.drawable.ic_back, "Geri") { onBackPressedDispatcher.onBackPressed() }, LinearLayout.LayoutParams(with(Ui) { dp(48) }, with(Ui) { dp(48) }))
        heading.addView(Ui.text(this, title, 25f, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (back) marginStart = with(Ui) { dp(12) }
        })
        loading = ProgressBar(this).apply {
            id = R.id.loading; contentDescription = "İşlem sürüyor"
            visibility = if (busy) View.VISIBLE else View.GONE
        }
        heading.addView(loading, LinearLayout.LayoutParams(with(Ui) { dp(28) }, with(Ui) { dp(28) }))
        page.addView(heading)
        Ui.gap(this, page, 16)
        setContentView(page)
        page.requestApplyInsets()
    }

    protected fun <T> io(work: () -> T, completed: (T) -> Unit) {
        pending++
        updateBusy()
        (application as LibraryApplication).executor.execute {
            val result = runCatching(work)
            runOnUiThread {
                if (!isDestroyed && !isFinishing) {
                    pending--
                    updateBusy()
                    result.fold(completed) { error ->
                        val message = if (error is IllegalArgumentException) error.message ?: "Giriş geçersiz." else "İşlem tamamlanamadı. Kayıtların korunuyor; tekrar deneyebilirsin."
                        notice(message)
                    }
                }
            }
        }
    }

    private fun updateBusy() {
        if (::loading.isInitialized) loading.visibility = if (busy) View.VISIBLE else View.GONE
        actionButtons.forEach { it.isEnabled = !busy }
        onBusyChanged(busy)
    }

    protected open fun onBusyChanged(active: Boolean) = Unit

    protected fun notice(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
}
