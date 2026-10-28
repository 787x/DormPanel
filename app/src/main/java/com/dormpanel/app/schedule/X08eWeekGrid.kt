package com.dormpanel.app.schedule

import android.content.Context
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.dormpanel.app.appearance.*
import java.time.*
import kotlin.math.roundToInt

/** One scroll surface for all seven days; the parent keeps date headers outside it. */
internal class X08eWeekGrid(context: Context, items: List<ClassOccurrence>, private val week: LocalDate,
    private val source: ScheduleSource, private val style: X08eScheduleStyle,
    private val appearance: AppearanceController, private val detail: (ClassOccurrence) -> Unit,
    private val interactive: () -> Boolean = { true },
    private val prepared: X08eWeekDisplay? = null, private val deferred: Boolean = false) : LinearLayout(context), AppearanceAware {
    private val segments = prepared?.segments ?: X08ePreviewTrace.section("geometry") { X08eWeekGeometry.segments(items, week, source.clock.zone()) }
    private val range = prepared?.range ?: X08ePreviewTrace.section("geometry") { X08eWeekGeometry.range(segments) }
    private val periodAxis = if(prepared!=null) prepared.axis.takeIf { prepared.usesPeriods } else X08ePreviewTrace.section("geometry") { if (style.layout == X08eScheduleLayout.PERIODS)
        X08eWeekGeometry.periods(source.state, items, week, source.clock.zone(), range.first, range.second) else null }
    val usesPeriods get() = periodAxis != null
    val axis = prepared?.axis ?: X08ePreviewTrace.section("geometry") { periodAxis ?: X08eWeekGeometry.time(range.first, range.second) }
    private val columns = mutableListOf<RuleColumn>()
    val preparations = mutableListOf<Pair<View, () -> Unit>>()
    private val built = BooleanArray(7)
    private val lines = mutableListOf<View>()
    private val cards = mutableListOf<View>()
    private val labels = mutableListOf<TextView>()
    private var chooser: AlertDialog? = null
    private var active = false
    var clockAllowed = true
        set(value) { field = value; setClockActive(value && isShown && isAttachedToWindow) }
    private val tick = object : Runnable {
        override fun run() {
            updateNow()
            if (active && isAttachedToWindow && isShown && windowVisibility == VISIBLE && source.clock.today() in week..week.plusDays(6))
                postDelayed(this, 60_000L - Math.floorMod(source.clock.instant().toEpochMilli(), 60_000L))
        }
    }
    fun setClockActive(value: Boolean) { active = value && clockAllowed; removeCallbacks(tick); if (active) tick.run() }
    fun offset(minute: Float) = (axis.y(minute) * resources.displayMetrics.density).roundToInt()
    fun minuteAt(offset: Int) = axis.minute(offset / resources.displayMetrics.density)
    init {
        orientation = HORIZONTAL
        val height = context.dp(axis.height.toInt() + 48)
        val gutter = FrameLayout(context)
        addView(gutter, LayoutParams(context.dp(76), height))
        axis.labels.forEach { (minute, text) ->
            val label = context.scheduleLabel(if (usesPeriods) context.scheduleText("第${text.substringBefore('\n')}节\n${text.substringAfter('\n')}", "P$text") else text, if (usesPeriods) 13f else 14f)
            label.setPadding(context.dp(4), 0, 0, 0); labels += label
            gutter.addView(label, FrameLayout.LayoutParams(-1, context.dp(if (usesPeriods) 64 else 28)).apply { topMargin = offset(minute) })
        }
        repeat(7) { day ->
            val column = RuleColumn(); columns += column
            addView(column, LayoutParams(0, height, 1f))
            val line = View(context).apply { setBackgroundColor(0xffbb655e.toInt()); contentDescription = context.scheduleText("当前时间", "Current time"); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
            lines += line; column.addView(line, FrameLayout.LayoutParams(-1, context.dp(2)))
            val prepareDay = {
                if(!built[day]) {
                    built[day]=true
                    val groups=prepared?.lanes?.get(day) ?: X08ePreviewTrace.section("geometry") {
                        X08eWeekGeometry.groups(segments.filter { it.day==day }).map(X08eWeekGeometry::lanes)
                    }
                    groups.forEach { lanes ->
                        val group=lanes.flatten()
                        val top=offset(group.minOf { it.start }); val bottom=offset(group.maxOf { it.end })
                        val row=LinearLayout(context)
                        lanes.forEach { lane ->
                            val laneView=FrameLayout(context)
                            lane.forEach { segment ->
                                val card=context.courseView(listOf(segment.item),source.state,style,source.clock.zone(),prepared?.texts?.get(segment.item)) { detail(segment.item) }
                                cards+=card
                                if(deferred) applyAppearanceTree(card,appearance.state)
                                laneView.addView(card,FrameLayout.LayoutParams(-1,maxOf(1,offset(segment.end)-offset(segment.start))).apply {
                                    topMargin=offset(segment.start)-top; marginStart=context.dp(2); marginEnd=context.dp(2)
                                })
                            }
                            row.addView(laneView,LayoutParams(0,-1,1f))
                        }
                        column.addView(row,column.childCount-1,FrameLayout.LayoutParams(-1,maxOf(1,bottom-top)).apply { topMargin=top })
                    }
                }
            }
            if(deferred) preparations += column to prepareDay else prepareDay()
        }
        applyAppearance(appearance.state)
    }
    /** Draw the same rules below children without allocating 7 x tick-count Views. */
    private inner class RuleColumn : FrameLayout(context) {
        var ruleColor=if(appearance.state.themeMode==ThemeMode.DARK) 0xff303030.toInt() else 0xffd0d7d4.toInt()
        private val paint=android.graphics.Paint()
        private val positions=axis.labels.map { offset(it.first).toFloat() }
        init { setWillNotDraw(false) }
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            paint.color=ruleColor
            val height=context.dp(1).coerceAtLeast(1).toFloat()
            positions.forEach { y -> canvas.drawRect(0f,y,width.toFloat(),y+height,paint) }
        }
    }
    /** Always available from the fixed day footer, even for one-minute or heavily overlapping courses. */
    fun openDay(day: Int) = open(segments.filter { it.day == day }.map { it.item })
    fun dayCount(day: Int) = segments.count { it.day == day }
    private fun open(items: List<ClassOccurrence>) {
        if (!interactive()) return
        if (items.isEmpty()) return
        if (items.size == 1) { detail(items.single()); return }
        chooser?.dismiss()
        chooser = AlertDialog.Builder(context).setTitle(context.scheduleText("当天课程", "Day courses"))
            .setItems(items.map { "${it.entry.title}\n${context.occurrenceTime(it, source.clock.zone())} · ${it.entry.location}" }.toTypedArray()) { _, i -> detail(items[i]) }
            .setNegativeButton(context.scheduleText("关闭", "Close"), null).create().also {
                it.show(); it.matchActivityBrightness(); applyAppearanceTree(it.window!!.decorView, appearance.state)
            }
    }
    internal fun updateNow() {
        val now = source.clock.instant().atZone(source.clock.zone())
        val palette = PanelPalette.forMode(appearance.state.themeMode)
        columns.forEachIndexed { day, column ->
            val today = week.plusDays(day.toLong()) == now.toLocalDate()
            column.setBackgroundColor(if (today) { if (appearance.state.themeMode == ThemeMode.DARK) 0xff101a22.toInt() else 0xffe4ecf4.toInt() } else palette.background)
            val minute = now.toLocalTime().toSecondOfDay() / 60f
            lines[day].visibility = if (today && minute in axis.points.first().first..axis.points.last().first) VISIBLE else GONE
            (lines[day].layoutParams as FrameLayout.LayoutParams).let { it.topMargin = offset(minute); lines[day].layoutParams = it }
        }
    }
    override fun applyAppearance(state: AppearanceState) = X08ePreviewTrace.section("theme") {
        labels.forEach { it.setTextColor(PanelPalette.forMode(state.themeMode).secondary) }
        columns.forEach { it.ruleColor=if(state.themeMode==ThemeMode.DARK) 0xff303030.toInt() else 0xffd0d7d4.toInt(); it.invalidate() }
        cards.forEach { applyAppearanceTree(it, state) }; updateNow()
        chooser?.window?.decorView?.let { applyAppearanceTree(it, state) }
        Unit
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); setClockActive(isShown) }
    override fun onDetachedFromWindow() { setClockActive(false); chooser?.dismiss(); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); setClockActive(isVisible) }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); setClockActive(visibility == VISIBLE && isShown) }
}
