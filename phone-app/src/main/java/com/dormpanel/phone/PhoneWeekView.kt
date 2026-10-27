package com.dormpanel.phone

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.dormpanel.app.schedule.ClassOccurrence
import com.dormpanel.app.schedule.TermScheduleProfile
import java.time.*

/** Bounded native columns. Only the clock overlay changes on each minute tick. */
internal class PhoneWeekView(context: Context, items: List<ClassOccurrence>, private val monday: LocalDate,
    private val zone: ZoneId, detail: (ClassOccurrence) -> Unit, val dayCount: Int = 7,
    periodMode: Boolean = false, profile: TermScheduleProfile? = null) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val segments = WeekLayout.segments(items, monday, zone)
    private val first = minOf(480, segments.minOfOrNull { it.start } ?: 480) / 60 * 60
    private val last = maxOf(1080, segments.maxOfOrNull { it.end } ?: 1080)
    private val periodAxes = (0 until dayCount).map { WeekAxis.periods(profile, monday.plusDays(it.toLong()), zone, first, last) }
    val usesPeriods = periodMode && periodAxes.all { it != null }
    val axes = (0 until dayCount).map { if (usesPeriods) periodAxes[it]!! else WeekAxis.time(first,last) }
    private val columns = mutableListOf<FrameLayout>()
    private val lines = mutableListOf<View>()
    private val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private var clockActive = true
    internal fun setClockActive(active: Boolean) { clockActive = active; if (active) startClock() else removeCallbacks(tick) }
    private val tick = object : Runnable {
        override fun run() { updateNow(); if (clockActive && windowVisibility == VISIBLE && isShown && java.time.temporal.ChronoUnit.DAYS.between(monday,LocalDate.now(zone)) in 0 until dayCount.toLong()) postDelayed(this, 60_000L - System.currentTimeMillis()%60_000L) }
    }
    fun minuteAt(offset: Int, day: Int = 0) = axes[day.coerceIn(0,dayCount-1)].minute(offset/density)
    fun offsetAt(minute: Float, day: Int = 0) = (axes[day.coerceIn(0,dayCount-1)].y(minute)*density).toInt().coerceAtLeast(0)
    init {
        orientation = HORIZONTAL
        val height = (axes.maxOf { it.height }*density).toInt() + context.dp(24)
        val axis = FrameLayout(context)
        addView(axis, LayoutParams(context.dp(32),height))
        axes.first().labels.forEach { (minute,label) ->
            axis.addView(TextView(context).apply { text = label; textSize = if (usesPeriods) 12f else 9f; includeFontPadding = false },
                FrameLayout.LayoutParams(-1,context.dp(24)).apply { topMargin = offsetAt(minute.toFloat()) })
        }
        repeat(dayCount) { day ->
            val column = FrameLayout(context); columns += column
            addView(column, LayoutParams(0,height,1f))
            axes[day].labels.forEach { (minute,_) -> column.addView(View(context).apply { setBackgroundColor(Color.GRAY); alpha = .25f },
                FrameLayout.LayoutParams(-1,1).apply { topMargin = offsetAt(minute.toFloat(),day) }) }
            // A mid-week phase can have different gaps/numbers. Mark that day's own ticks;
            // the shared left gutter must never imply that Monday's phase applies to every day.
            if (usesPeriods && axes[day].labels.map { it.second to axes[day].y(it.first.toFloat()) } !=
                axes.first().labels.map { it.second to axes.first().y(it.first.toFloat()) }) {
                axes[day].labels.forEach { (minute,label) -> column.addView(TextView(context).apply {
                    text = "${label}节"; textSize = 9f; alpha = .7f; includeFontPadding = false
                },FrameLayout.LayoutParams(-2,context.dp(16)).apply { topMargin = offsetAt(minute.toFloat(),day) }) }
            }
            val groups = WeekLayout.groups(segments.filter { it.day == day })
            fun open(group: List<ClassOccurrence>) {
                if (group.size == 1) detail(group.single()) else AlertDialog.Builder(context).setTitle("重叠课程")
                    .setItems(group.map { "${it.entry.title}\n${context.scheduleTime(it.start)}–${context.scheduleTime(it.end)} · ${it.entry.location}" }.toTypedArray()) { _,i -> detail(group[i]) }
                    .setNegativeButton("关闭",null).show()
            }
            // Short blocks keep exact geometry; an enlarged empty-space hit area offers ALL nearby originals.
            var downY = 0f; var moved = false
            column.setOnTouchListener { _,event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> { downY = event.y; moved = false }
                    android.view.MotionEvent.ACTION_MOVE -> if (kotlin.math.abs(event.y-downY) > android.view.ViewConfiguration.get(context).scaledTouchSlop) moved = true
                    android.view.MotionEvent.ACTION_CANCEL -> moved = true
                    android.view.MotionEvent.ACTION_UP -> if (!moved) {
                        val nearby = groups.filter { event.y >= offsetAt(it.first().start.toFloat(),day)-context.dp(24) && event.y <= offsetAt(it.maxOf { s -> s.end }.toFloat(),day)+context.dp(24) }
                        if (nearby.isNotEmpty()) open(nearby.flatten().map { it.item })
                    }
                }; true
            }
            groups.forEach { group ->
                val top = offsetAt(group.first().start.toFloat(),day)
                val bottom = offsetAt(group.maxOf { it.end }.toFloat(),day)
                column.addView(CourseCard(context,group.map { it.item },zone) { open(group.map { it.item }) },
                    FrameLayout.LayoutParams(-1,maxOf(1,bottom-top)).apply { topMargin = top; marginStart = context.dp(1); marginEnd = context.dp(1) })
            }
            val line = View(context).apply { setBackgroundColor(Color.rgb(224,88,76)); contentDescription = "当前时间"; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
            lines += line; column.addView(line,FrameLayout.LayoutParams(-1,context.dp(2)))
        }
        updateNow()
    }
    internal fun updateNow(now: ZonedDateTime = ZonedDateTime.now(zone)) {
        columns.forEachIndexed { day,column ->
            val today = monday.plusDays(day.toLong()) == now.toLocalDate()
            column.setBackgroundColor(if (today) { if (dark) 0xff172735.toInt() else 0xffe8f1fa.toInt() } else if (dark) Color.BLACK else Color.TRANSPARENT)
            val minute = now.toLocalTime().toSecondOfDay()/60f
            lines[day].visibility = if (today && minute in axes[day].points.first().first..axes[day].points.last().first) VISIBLE else GONE
            (lines[day].layoutParams as FrameLayout.LayoutParams).let { it.topMargin = offsetAt(minute,day); lines[day].layoutParams = it }
        }
    }
    private fun startClock() { removeCallbacks(tick); if (clockActive && isAttachedToWindow && windowVisibility == VISIBLE && isShown) tick.run() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); startClock() }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); if (visibility == VISIBLE) startClock() else removeCallbacks(tick) }
}
