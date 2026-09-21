package com.dormpanel.app.apps

import android.annotation.SuppressLint
import android.content.Context
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dormpanel.app.R
import com.dormpanel.app.appearance.*
import com.dormpanel.app.ui.PageInteraction

@SuppressLint("ViewConstructor")
class AppsPageView(context: Context, private val source: InstalledAppSource,
    private val icons: AppIcons, private val appearance: AppearanceController,
    onReturnHome: () -> Unit,
) : LinearLayout(context), PageInteraction, AppearanceAware {
    private val heading = TextView(context).apply { setText(R.string.apps_title); textSize = 30f }
    private val hint = TextView(context).apply { setText(R.string.apps_navigation); textSize = 18f }
    private val home = Button(context).apply {
        id = R.id.apps_home; setText(R.string.apps_home); textSize = 22f; isAllCaps = false
        setOnClickListener { onReturnHome() }
    }
    private val header = LinearLayout(context).apply {
        id = R.id.apps_header; orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(24.dp, 0, 24.dp, 0)
        addView(LinearLayout(context).apply { orientation = VERTICAL; addView(heading); addView(hint) }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(home, LayoutParams(144.dp, 56.dp))
    }
    private val adapter = AppsAdapter()
    private val grid = RecyclerView(context).apply {
        id = R.id.apps_grid; layoutManager = GridLayoutManager(context, 6); adapter = this@AppsPageView.adapter
        itemAnimator = null; setPadding(16.dp, 0, 16.dp, 16.dp); clipToPadding = false
    }
    private val listener: (List<InstalledApp>) -> Unit = { adapter.items = it; adapter.notifyDataSetChanged() }
    private var dialog: AlertDialog? = null
    init {
        orientation = VERTICAL
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, 100.dp))
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        (grid.layoutManager as GridLayoutManager).spanCount = (w / 170.dp).coerceIn(3, 8)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); source.addListener(listener) }
    override fun onDetachedFromWindow() { source.removeListener(listener); dialog?.dismiss(); super.onDetachedFromWindow() }
    override fun shouldObservePageSwipe(event: MotionEvent): Boolean = false
    override fun applyAppearance(state: AppearanceState) {
        val palette = PanelPalette.forMode(state.themeMode)
        setBackgroundColor(palette.background); heading.setTextColor(palette.text); hint.setTextColor(palette.secondary); adapter.notifyDataSetChanged()
        home.setTextColor(palette.accent)
        home.backgroundTintList = android.content.res.ColorStateList.valueOf(palette.surface)
    }
    private inner class Holder(val cell: LinearLayout, val icon: ImageView, val label: TextView) : RecyclerView.ViewHolder(cell)
    private inner class AppsAdapter : RecyclerView.Adapter<Holder>() {
        var items = emptyList<InstalledApp>()
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val icon = ImageView(context)
            val label = TextView(context).apply { textSize = 21f; maxLines = 2; gravity = Gravity.CENTER; ellipsize = android.text.TextUtils.TruncateAt.END }
            val cell = LinearLayout(context).apply {
                orientation = VERTICAL; gravity = Gravity.CENTER; isFocusable = true
                setPadding(12.dp, 12.dp, 12.dp, 12.dp)
                layoutParams = RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, 156.dp)
                addView(icon, LayoutParams(56.dp, 56.dp)); addView(label, LayoutParams(LayoutParams.MATCH_PARENT, 64.dp))
            }
            return Holder(cell, icon, label)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = items[position]
            holder.label.text = if (source.isFavorite(app.component)) "★ ${app.label}" else app.label
            holder.label.setTextColor(PanelPalette.forMode(appearance.state.themeMode).text)
            holder.cell.contentDescription = app.label
            icons.bind(holder.icon, app.component)
            holder.cell.setOnClickListener { launchApp(context, source, app.component) }
            holder.cell.setOnLongClickListener {
                dialog = AlertDialog.Builder(context).setTitle(app.label)
                    .setItems(arrayOf(context.getString(if (source.isFavorite(app.component)) R.string.apps_unpin else R.string.apps_pin),
                        context.getString(R.string.apps_settings))) { _, action ->
                        if (action == 0) source.toggleFavorite(app.component) else openAppSettings(context, source, app.component)
                    }
                    .show()
                true
            }
        }
        override fun onViewRecycled(holder: Holder) { icons.cancel(holder.icon); holder.icon.setImageDrawable(null) }
    }
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
}

internal fun launchApp(context: Context, source: InstalledAppSource, component: String) {
    if (!source.launch(component)) Toast.makeText(context, R.string.apps_launch_failed, Toast.LENGTH_SHORT).show()
}

internal fun openAppSettings(context: Context, source: InstalledAppSource, component: String) {
    if (!source.openAppSettings(component)) Toast.makeText(context, R.string.apps_settings_failed, Toast.LENGTH_SHORT).show()
}
