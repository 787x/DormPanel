package com.dormpanel.app.dashboard.ui

import android.app.Dialog
import com.dormpanel.app.ui.hidePanelSystemBars
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.dormpanel.app.R
import com.dormpanel.app.appearance.*
import com.dormpanel.app.dashboard.catalog.*
import androidx.core.graphics.drawable.toDrawable

/** Landscape instance picker. It consumes catalog metadata, never device protocols or provider defaults. */
class CardPickerDialog(context: Context, private val catalog: CardCatalog,
    private val appearance: AppearanceController, private val onAdd: (CardAddCandidate) -> Boolean,
) : Dialog(context) {
    private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(24.dp, 20.dp, 24.dp, 20.dp) }
    private val navigation = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val candidates = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val tiles = mutableListOf<View>()
    private val categoryButtons = mutableMapOf<CardCategory, Button>()
    private var selected = CardCategory.INFORMATION
    private var current = emptyList<CardAddCandidate>()
    private val catalogListener: (List<CardAddCandidate>) -> Unit = { next ->
        current = next
        if (next.none { it.category == selected }) selected = next.firstOrNull()?.category ?: CardCategory.INFORMATION
        render()
    }
    private val appearanceListener: (AppearanceState) -> Unit = { style(it) }
    init {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(context).apply { setText(R.string.dashboard_add_card); textSize = 32f }, LinearLayout.LayoutParams(0, 64.dp, 1f))
        header.addView(Button(context).apply { setText(android.R.string.cancel); setOnClickListener { dismiss() } }, LinearLayout.LayoutParams(120.dp, 56.dp))
        root.addView(header)
        val body = LinearLayout(context)
        body.addView(navigation, LinearLayout.LayoutParams(220.dp, ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = 24.dp })
        body.addView(ScrollView(context).apply { isFillViewport = true; addView(candidates) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        setOnDismissListener { catalog.removeListener(catalogListener); appearance.removeListener(appearanceListener) }
    }
    override fun show() {
        super.show()
        window?.hidePanelSystemBars()
        window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        val metrics = context.resources.displayMetrics
        window?.setLayout(minOf(1120.dp, metrics.widthPixels - 64.dp), minOf(600.dp, metrics.heightPixels - 64.dp))
        catalog.addListener(catalogListener)
        appearance.addListener(appearanceListener)
    }
    private fun render() {
        navigation.removeAllViews(); categoryButtons.clear(); candidates.removeAllViews(); tiles.clear()
        current.map { it.category }.distinct().forEach { category ->
            val button = Button(context).apply {
                text = context.getString(when (category) {
                    CardCategory.INFORMATION -> R.string.category_information
                    CardCategory.HOME -> R.string.category_home
                    CardCategory.PRODUCTIVITY -> R.string.category_productivity
                    CardCategory.SYSTEM -> R.string.category_system
                    CardCategory.APPS -> R.string.category_apps
                })
                textSize = 22f; isAllCaps = false; isSelected = category == selected
                setOnClickListener { selected = category; render() }
            }
            categoryButtons[category] = button
            navigation.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 64.dp).apply { bottomMargin = 12.dp })
        }
        current.filter { it.category == selected }.chunked(2).forEach { pair ->
            val row = LinearLayout(context)
            pair.forEach { candidate ->
                val tile = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(20.dp, 12.dp, 20.dp, 12.dp)
                    isClickable = true; isFocusable = true
                    contentDescription = context.getString(R.string.picker_add_description, candidate.displayName)
                    addView(TextView(context).apply { text = candidate.displayName; textSize = 24f })
                    addView(TextView(context).apply { text = candidate.description; textSize = 20f })
                    setOnClickListener { if (onAdd(candidate)) dismiss() }
                }
                tiles += tile
                row.addView(tile, LinearLayout.LayoutParams(0, 120.dp, 1f).apply { marginEnd = 12.dp })
            }
            if (pair.size == 1) row.addView(View(context), LinearLayout.LayoutParams(0, 120.dp, 1f))
            candidates.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12.dp })
        }
        if (current.isEmpty()) candidates.addView(TextView(context).apply { setText(R.string.picker_empty); textSize = 24f })
        style(appearance.state)
    }
    private fun style(state: AppearanceState) {
        val palette = PanelPalette.forMode(state.themeMode)
        root.setBackgroundColor(palette.background)
        applyAppearanceTree(root, state)
        tiles.forEach { tile ->
            tile.background = GradientDrawable().apply { cornerRadius = 16.dp.toFloat(); setColor(palette.surface) }
            (tile as ViewGroup).getChildAt(1).let { (it as TextView).setTextColor(palette.secondary) }
        }
        categoryButtons.forEach { (category, button) ->
            button.setTextColor(if (category == selected) palette.accent else palette.secondary)
            button.isSelected = category == selected
        }
    }
    private val Int.dp get() = (this * context.resources.displayMetrics.density).toInt()
}
