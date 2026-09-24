package com.dormpanel.app.schedule

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.*
import com.dormpanel.app.appearance.*
import com.dormpanel.app.ui.PageInteraction
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

@SuppressLint("ViewConstructor")
class SchedulePageView(context: Context, private val source: ScheduleSource, private val session: ScheduleSession,
    private val appearance: AppearanceController, private val returnHome: () -> Unit,
    private val importTimetable: () -> Unit = {}, private val receiveTimetable: () -> Unit = {},
    private val manageTimetables: () -> Unit = {}) : LinearLayout(context), PageInteraction, AppearanceAware {
    private val editors = ScheduleEditors(context, source, appearance)
    private val display = ScheduleDisplay(this, source, ::render)
    private var visible = false
    private val accents = mutableListOf<() -> Unit>()
    init { orientation = VERTICAL; setPadding(context.dp(20), context.dp(10), context.dp(20), context.dp(10)); render() }
    override fun shouldObservePageSwipe(event: MotionEvent) = false
    override fun onAttachedToWindow() { super.onAttachedToWindow(); display.visibility(isShown) }
    override fun onDetachedFromWindow() { display.visibility(false); editors.close(); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); visible = isVisible; display.visibility(isVisible) }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); display.visibility(visible && visibility == VISIBLE) }
    private fun button(text: String, action: () -> Unit) = context.scheduleButton(text, action)
    private fun render() {
        removeAllViews(); accents.clear()
        val header = LinearLayout(context)
        header.addView(context.scheduleLabel("Schedule", 26f), LayoutParams(0, context.dp(56), 1f))
        ScheduleMode.entries.forEach { mode ->
            val button = button(if (mode == ScheduleMode.CALENDAR) "Calendar" else "Timetable") { session.mode = mode; render() }
            header.addView(button)
            accents += { if (session.mode == mode) button.setTextColor(PanelPalette.forMode(appearance.state.themeMode).accent) }
        }
        header.addView(button("Today") { session.today(source.clock); render() })
        header.addView(button("Home", returnHome))
        addView(header)
        if (!source.ready || source.error || source.state.invalidRecords > 0) addView(context.scheduleLabel(
            if (source.error) "Schedule storage error. Some changes may not be saved."
            else if (!source.ready) "Loading local schedule…" else "Some invalid stored records are hidden and preserved.", 16f))
        if (session.mode == ScheduleMode.CALENDAR) calendar() else timetable()
        applyAppearance(appearance.state)
    }
    private fun calendar() {
        val body = LinearLayout(context)
        val month = context.scheduleColumn()
        val navigation = LinearLayout(context)
        navigation.addView(button("‹") { session.moveMonth(-1); render() }.apply { contentDescription = "Previous month" })
        navigation.addView(context.scheduleLabel(session.month.format(DateTimeFormatter.ofPattern("LLLL yyyy", source.clock.locale())), 24f), LayoutParams(0, context.dp(56), 1f))
        navigation.addView(button("›") { session.moveMonth(1); render() }.apply { contentDescription = "Next month" })
        month.addView(navigation)
        val weekdays = LinearLayout(context)
        ScheduleProjection.weekDays(source.clock.locale()).forEach { weekdays.addView(context.scheduleLabel(it.getDisplayName(TextStyle.SHORT, source.clock.locale()), 16f), LayoutParams(0, context.dp(40), 1f)) }
        month.addView(weekdays)
        ScheduleProjection.monthCells(session.month, source.clock.locale()).chunked(7).forEach { week ->
            val row = LinearLayout(context)
            week.forEach { date ->
                val day = button(date.dayOfMonth.toString()) { session.select(date); render() }.apply {
                    minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0)
                    contentDescription = "Select $date"
                }
                row.addView(day, LayoutParams(0, 0, 1f).apply { height = context.dp(48) })
                accents += {
                    val palette = PanelPalette.forMode(appearance.state.themeMode)
                    day.alpha = if (YearMonth.from(date) == session.month) 1f else .5f
                    day.backgroundTintList = null
                    day.background = GradientDrawable().apply {
                        cornerRadius = context.dp(8).toFloat()
                        setColor(if (date == session.selectedDate) android.graphics.Color.argb(45, android.graphics.Color.red(palette.accent), android.graphics.Color.green(palette.accent), android.graphics.Color.blue(palette.accent)) else android.graphics.Color.TRANSPARENT)
                        if (date == source.clock.today()) setStroke(context.dp(1), palette.accent)
                    }
                }
            }
            month.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
        body.addView(month, LayoutParams(0, LayoutParams.MATCH_PARENT, 1.05f))
        val agenda = context.scheduleColumn().apply { setPadding(context.dp(20), 0, 0, 0) }
        agenda.addView(context.scheduleLabel(scheduleDate(session.selectedDate), 24f))
        agenda.addView(button("+ Add event") { editors.event(session.selectedDate) }.apply { isEnabled = source.ready })
        val events = context.scheduleColumn()
        val selected = ScheduleProjection.eventsOn(source.state, session.selectedDate, source.clock.zone())
        if (selected.isEmpty()) events.addView(context.scheduleLabel("No events on this day", 18f))
        selected.forEach { event ->
            val start = Instant.ofEpochMilli(event.start).atZone(source.clock.zone())
            val end = Instant.ofEpochMilli(event.end).atZone(source.clock.zone())
            val range = (if (start.toLocalDate() != session.selectedDate) "${scheduleDate(start.toLocalDate())} " else "") + context.scheduleTime(start.toInstant()) + " – " +
                (if (end.toLocalDate() != session.selectedDate) "${scheduleDate(end.toLocalDate())} " else "") + context.scheduleTime(end.toInstant())
            events.addView(button("$range\n${event.title}${if (event.note.isBlank()) "" else "\n${event.note}"}") { editors.event(session.selectedDate, event) }.apply {
                gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL; maxLines = 4; textSize = 18f
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        agenda.addView(ScrollView(context).apply { addView(events) }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        body.addView(agenda, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }
    private fun timetable() {
        val actions = LinearLayout(context)
        actions.addView(button("+ Add class") { editors.entry() }.apply { isEnabled = source.ready })
        actions.addView(button("Import ICS", importTimetable).apply { isEnabled = source.ready })
        actions.addView(button("Receive from phone/computer", receiveTimetable).apply { isEnabled = source.ready })
        actions.addView(button("Sources", manageTimetables).apply { isEnabled = source.ready })
        actions.addView(button("‹") { session.weekStart = session.weekStart.minusWeeks(1); render() }.apply { contentDescription = "Previous week" })
        actions.addView(context.scheduleLabel("${session.weekStart} – ${session.weekStart.plusDays(6)}", 18f), LayoutParams(0, context.dp(52), 1f))
        actions.addView(button("›") { session.weekStart = session.weekStart.plusWeeks(1); render() }.apply { contentDescription = "Next week" })
        actions.addView(button("This week") { session.today(source.clock); render() })
        addView(actions)
        val occurrences = ScheduleProjection.week(source.state, session.weekStart, source.clock.zone())
        val week = LinearLayout(context)
        (0L..6L).forEach { offset ->
            val date = session.weekStart.plusDays(offset)
            val day = date.dayOfWeek
            val column = context.scheduleColumn().apply { setPadding(context.dp(3), 0, context.dp(3), 0) }
            val headingText = "${day.getDisplayName(TextStyle.SHORT, source.clock.locale())} ${date.monthValue}/${date.dayOfMonth}"
            val heading = context.scheduleLabel(headingText, 19f)
            column.addView(heading)
            accents += { if (date == source.clock.today()) { heading.setTextColor(PanelPalette.forMode(appearance.state.themeMode).accent); heading.text = "• $headingText" } }
            val classes = context.scheduleColumn()
            val entries = occurrences.filter { it.date == date || (offset == 0L && it.date < date) }
            if (entries.isEmpty()) classes.addView(context.scheduleLabel("—", 18f))
            entries.forEach { occurrence ->
                val entry = occurrence.entry
                val imported = occurrence.imported
                val extra = imported?.let { "\n${it.periodLabel?.let { label -> "$label · " }.orEmpty()}Imported" }.orEmpty()
                classes.addView(button("${entry.title}\n${context.scheduleTime(entry.startMinute)}\n– ${context.scheduleTime(entry.endMinute)}${if (entry.location.isBlank()) "" else "\n${entry.location}"}$extra") {
                    if (imported == null) editors.entry(entry) else editors.imported(imported)
                }.apply {
                    textSize = 16f; maxLines = 7; isAllCaps = false; gravity = android.view.Gravity.START; ellipsize = android.text.TextUtils.TruncateAt.END
                }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
            column.addView(ScrollView(context).apply { addView(classes) }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            week.addView(column, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        addView(week, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }
    override fun applyAppearance(state: AppearanceState) {
        setBackgroundColor(PanelPalette.forMode(state.themeMode).background)
        for (index in 0 until childCount) applyAppearanceTree(getChildAt(index), state)
        accents.forEach { it() }
    }
}
