package com.dormpanel.phone

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.dormpanel.app.schedule.ClassOccurrence
import java.time.*

/** Seven bounded native columns in one vertical scroller; no horizontal scrolling or idle work. */
internal class PhoneWeekView(context: Context, items: List<ClassOccurrence>, monday: LocalDate,
    zone: ZoneId, detail: (ClassOccurrence) -> Unit) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        val segments = WeekLayout.segments(items, monday, zone)
        val first = minOf(480, segments.minOfOrNull { it.start } ?: 480) / 60 * 60
        val last = maxOf(1080, segments.maxOfOrNull { it.end } ?: 1080)
        val scale = resources.displayMetrics.density * .8f
        val height = ((last - first) * scale).toInt() + context.dp(24)
        val axis = FrameLayout(context)
        addView(axis, LayoutParams(context.dp(32), height))
        for (minute in first..last step 60) {
            axis.addView(TextView(context).apply { text = "%02d:%02d".format(minute / 60, minute % 60); setSingleLine(true); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 9f * resources.displayMetrics.density) },
                FrameLayout.LayoutParams(-1, context.dp(24)).apply { topMargin = ((minute - first) * scale).toInt() })
        }
        repeat(7) { day ->
            val column = FrameLayout(context)
            addView(column, LayoutParams(0, height, 1f))
            for (minute in first..last step 60) column.addView(android.view.View(context).apply {
                setBackgroundColor(Color.GRAY); alpha = .35f
            }, FrameLayout.LayoutParams(-1, 1).apply { topMargin = ((minute - first) * scale).toInt() })
            column.setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_UP) {
                    val cards = (0 until column.childCount).map { column.getChildAt(it) }.filterIsInstance<CourseCard>()
                    cards.minByOrNull { kotlin.math.abs((it.top + it.bottom) / 2f - event.y) }?.let {
                        if (event.y >= it.top - context.dp(24) && event.y <= it.bottom + context.dp(24)) it.performClick()
                    }
                }
                true
            }
            val today = LocalDate.now(zone)
            if (monday.plusDays(day.toLong()) == today) column.setBackgroundColor(0x105080b0)
            else if (day >= 5) column.setBackgroundColor(0x08777777)
            WeekLayout.groups(segments.filter { it.day == day }).forEach { group ->
                val start = group.first().start
                val end = group.maxOf { it.end }
                val item = group.first().item
                column.addView(CourseCard(context, group.map { it.item }, zone) {
                    if (group.size == 1) detail(item) else AlertDialog.Builder(context).setTitle("重叠课程")
                        .setItems(group.map { "${it.item.entry.title}\n${context.scheduleTime(it.item.start)}–${context.scheduleTime(it.item.end)} · ${it.item.entry.location}" }.toTypedArray()) { _, i -> detail(group[i].item) }
                        .setNegativeButton("关闭", null).show()
                }, FrameLayout.LayoutParams(-1, maxOf(1, ((end - start) * scale).toInt())).apply {
                    topMargin = ((start - first) * scale).toInt(); marginStart = context.dp(1); marginEnd = context.dp(1)
                })
            }
            if (monday.plusDays(day.toLong()) == today) {
                val now = LocalTime.now(zone).toSecondOfDay() / 60
                if (now in first..last) column.addView(android.view.View(context).apply { setBackgroundColor(Color.rgb(205,80,65)); contentDescription = "当前时间" },
                    FrameLayout.LayoutParams(-1, context.dp(2)).apply { topMargin = ((now-first)*scale).toInt() })
            }
        }
    }
}
