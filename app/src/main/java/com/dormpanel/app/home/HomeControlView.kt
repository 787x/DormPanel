package com.dormpanel.app.home

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dormpanel.app.R
import com.dormpanel.app.appearance.*
import com.dormpanel.app.dashboard.card.CardInteractionScope
import com.dormpanel.app.dashboard.card.LightQuickControls
import com.dormpanel.app.data.Availability
import com.dormpanel.app.ha.*
import com.dormpanel.app.ui.PageInteraction
import kotlin.math.abs

/** Domain presentation is independent of area navigation and device grouping. */
private interface EntityRenderer {
    fun value(context: Context, entity: HomeEntity): String
    fun action(entity: HomeEntity): Int? = null
    val details: Boolean get() = false
}
private class PowerRenderer(override val details: Boolean = false) : EntityRenderer {
    override fun value(context: Context, entity: HomeEntity): String {
        val power = context.getString(if (entity.isOn) R.string.home_on else R.string.home_off)
        return if (entity.light?.capabilities?.brightness == true && entity.isOn)
            context.getString(R.string.home_light_value, power, entity.light.brightness) else power
    }
    override fun action(entity: HomeEntity) = if (entity.isOn) R.string.light_turn_off else R.string.light_turn_on
}
private class SensorRenderer : EntityRenderer {
    override fun value(context: Context, entity: HomeEntity) = if (entity.value in listOf("", "unknown", "unavailable"))
        context.getString(R.string.home_no_value) else listOf(entity.value, entity.unit).filter { it.isNotEmpty() }.joinToString(" ")
}
private class ActionRenderer(private val label: Int) : EntityRenderer {
    override fun value(context: Context, entity: HomeEntity) = context.getString(if (!entity.actionable) R.string.home_requires_input else if (entity.kind == HomeKind.SCRIPT && entity.isOn) R.string.home_running else R.string.home_ready)
    override fun action(entity: HomeEntity) = label
}
private val renderers = mapOf(
    HomeKind.LIGHT to PowerRenderer(true), HomeKind.SWITCH to PowerRenderer(), HomeKind.SENSOR to SensorRenderer(),
    HomeKind.SCENE to ActionRenderer(R.string.home_activate), HomeKind.SCRIPT to ActionRenderer(R.string.home_run),
)
private data class HomeRow(val key: String, val title: String = "", val entity: HomeEntity? = null)

class HomeControlView(context: Context, private val backend: DashboardBackend,
    private val appearance: AppearanceController, private val claimGesture: () -> Unit,
) : LinearLayout(context), PageInteraction, AppearanceAware {
    private val areaList = LinearLayout(context).apply { orientation = VERTICAL }
    private val title = TextView(context).apply { textSize = 28f }
    private val status = TextView(context).apply { textSize = 17f }
    private val list = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context); itemAnimator = null }
    private val rows = HomeAdapter()
    private var areaChoices = emptyList<Pair<String?, String>>()
    private var selected: String? = null
    private var theme = appearance.state
    private var dialog: LightQuickControls? = null
    private val statusListener: (HaStatus) -> Unit = { bind(backend.homeState) }
    private val listener: (HomeControlState) -> Unit = { bind(it) }
    private var downX = 0f
    private var downY = 0f
    private var ownsTouch = false
    private var downInList = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    init {
        orientation = HORIZONTAL
        setPadding(dp(24), dp(20), dp(24), dp(20))
        addView(ScrollView(context).apply { addView(areaList) }, LayoutParams(dp(210), LayoutParams.MATCH_PARENT))
        val content = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(24), 0, 0, 0) }
        addView(content, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(title, LayoutParams(0, dp(56), 1f))
        heading.addView(Button(context).apply { setText(R.string.home_settings); setOnClickListener { HaSettingsDialog(context, backend).show() } })
        content.addView(heading)
        content.addView(status, LayoutParams(LayoutParams.MATCH_PARENT, dp(42)))
        list.adapter = rows
        content.addView(list, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(TextView(context).apply { setText(R.string.home_swipe_hint); textSize = 14f; setPadding(0, dp(8), 0, 0) })
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); backend.addHomeListener(listener); backend.ha.addStatusListener(statusListener) }
    override fun onDetachedFromWindow() { backend.removeHomeListener(listener); backend.ha.removeStatusListener(statusListener); dialog?.dismiss(); super.onDetachedFromWindow() }
    private fun bind(state: HomeControlState) {
        backend.homeSelection.reconcile(state)
        val choice = backend.homeSelection.areaId
        val choices = listOf(null to context.getString(R.string.home_all)) + state.areas.map { it.id to it.name } +
            if (state.entities.any { it.areaId == null }) listOf("" to context.getString(R.string.home_unassigned)) else emptyList()
        if (areaChoices != choices || selected != choice) {
            areaChoices = choices; selected = choice
            areaList.removeAllViews()
            areaList.addView(TextView(context).apply { setText(R.string.home_control_title); textSize = 22f; setPadding(dp(8), 0, 0, dp(16)) })
            choices.forEach { (id, name) -> areaList.addView(Button(context).apply {
                text = name; isAllCaps = false; textSize = 18f; isSelected = id == choice; minHeight = dp(60)
                setOnClickListener { backend.homeSelection.areaId = id; bind(backend.homeState); list.scrollToPosition(0) }
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }
            styleAreas()
        }
        title.text = choices.find { it.first == choice }?.second ?: context.getString(R.string.home_all)
        status.setText(when {
            backend.settings.mode == BackendMode.DEMO -> R.string.home_demo
            backend.ha.status.state == HaConnectionState.NOT_CONFIGURED -> R.string.home_configure
            !state.connected -> R.string.home_stale
            state.entities.isEmpty() -> R.string.home_empty
            else -> R.string.home_live
        })
        val next = buildList {
            state.groups(choice).forEach { group ->
                val groupId = "${group.area?.id.orEmpty()}/${group.device?.id.orEmpty()}"
                val heading = listOfNotNull(group.area?.name ?: context.getString(R.string.home_unassigned), group.device?.name).joinToString(" · ")
                add(HomeRow("header:$groupId", heading))
                group.entities.groupBy { it.kind }.forEach { (kind, entities) ->
                    if (kind == HomeKind.SCENE || kind == HomeKind.SCRIPT) add(HomeRow("section:$groupId:$kind", context.getString(if (kind == HomeKind.SCENE) R.string.home_scenes else R.string.home_scripts)))
                    entities.forEach { add(HomeRow("entity:${it.id}", entity = it)) }
                }
            }
            if (isEmpty()) add(HomeRow("empty", context.getString(R.string.home_empty)))
        }
        rows.submitList(next)
    }
    private fun styleAreas() {
        val palette = PanelPalette.forMode(theme.themeMode)
        for (i in 0 until areaList.childCount) {
            val child = areaList.getChildAt(i)
            applyAppearanceTree(child, theme)
            if (child is Button) child.apply {
            applyAppearanceTree(this, theme)
            setTextColor(if (isSelected) palette.accent else palette.text)
            }
        }
    }
    override fun applyAppearance(state: AppearanceState) {
        theme = state
        setBackgroundColor(PanelPalette.forMode(state.themeMode).background)
        for (i in 0 until childCount) applyAppearanceTree(getChildAt(i), state)
        styleAreas()
        rows.notifyItemRangeChanged(0, rows.itemCount, "appearance")
    }
    private fun contains(view: View, event: MotionEvent): Boolean {
        val point = IntArray(2); view.getLocationOnScreen(point)
        return view.visibility == VISIBLE && event.rawX >= point[0] && event.rawX < point[0] + view.width && event.rawY >= point[1] && event.rawY < point[1] + view.height
    }
    private fun hitsControl(view: View, event: MotionEvent): Boolean {
        if (!contains(view, event)) return false
        if (view is Button) return true
        return view is ViewGroup && (0 until view.childCount).any { hitsControl(view.getChildAt(it), event) }
    }
    override fun shouldObservePageSwipe(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x; downY = event.y; ownsTouch = hitsControl(this, event)
            downInList = contains(list, event) || contains(areaList, event)
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE && downInList && abs(event.y - downY) > slop && abs(event.y - downY) > abs(event.x - downX)) {
            ownsTouch = true; claimGesture()
        }
        return !ownsTouch
    }
    private inner class HomeAdapter : ListAdapter<HomeRow, Holder>(object : DiffUtil.ItemCallback<HomeRow>() {
        override fun areItemsTheSame(oldItem: HomeRow, newItem: HomeRow) = oldItem.key == newItem.key
        override fun areContentsTheSame(oldItem: HomeRow, newItem: HomeRow) = oldItem == newItem
    }) {
        private val identities = mutableMapOf<String, Long>()
        private var nextIdentity = 0L
        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = identities.getOrPut(getItem(position).key) { nextIdentity++ }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder()
        override fun onBindViewHolder(holder: Holder, position: Int) { holder.bind(getItem(position)) }
    }
    private inner class Holder : RecyclerView.ViewHolder(LinearLayout(context)) {
        private val box = itemView as LinearLayout
        private val labels = LinearLayout(context).apply { orientation = VERTICAL }
        private val name = TextView(context).apply { textSize = 21f }
        private val value = TextView(context).apply { textSize = 18f }
        private val action = Button(context)
        private val details = Button(context).apply { setText(R.string.home_details) }
        init {
            box.orientation = HORIZONTAL; box.gravity = Gravity.CENTER_VERTICAL
            box.setPadding(dp(18), dp(12), dp(18), dp(12))
            box.layoutParams = RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            labels.addView(name); labels.addView(value)
            box.addView(labels, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            box.addView(details, LayoutParams(dp(112), dp(56)))
            box.addView(action, LayoutParams(dp(142), dp(56)))
        }
        fun bind(row: HomeRow) {
            val e = row.entity
            name.text = e?.name ?: row.title
            value.visibility = if (e == null) GONE else VISIBLE
            action.visibility = GONE; details.visibility = GONE
            val palette = PanelPalette.forMode(theme.themeMode)
            box.background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(if (e == null) Color.TRANSPARENT else (palette.surface and 0x00ffffff) or ((theme.cardSurfaceOpacity * 255).toInt() shl 24))
            }
            applyAppearanceTree(box, theme)
            if (e == null) return
            val renderer = renderers.getValue(e.kind)
            val state = renderer.value(context, e)
            value.text = when (e.availability) {
                Availability.AVAILABLE -> state
                Availability.STALE -> context.getString(R.string.home_value_stale, state)
                Availability.UNAVAILABLE -> context.getString(R.string.home_unavailable)
            }
            renderer.action(e)?.let { label ->
                action.visibility = VISIBLE; action.setText(label)
                action.isEnabled = e.availability == Availability.AVAILABLE && e.actionable
                action.setOnClickListener {
                    val accepted = backend.activateEntity(e.id)
                    if (e.kind in listOf(HomeKind.SCENE, HomeKind.SCRIPT) || !accepted)
                        Toast.makeText(context, if (accepted) R.string.home_action_sent else R.string.home_action_failed, Toast.LENGTH_SHORT).show()
                }
            }
            if (renderer.details) {
                details.visibility = VISIBLE
                details.setOnClickListener {
                    dialog?.dismiss()
                    dialog = LightQuickControls(context, backend, appearance, e.id, CardInteractionScope(true, claimGesture)) { dialog = null }.also { it.show() }
                }
            }
        }
    }
}
