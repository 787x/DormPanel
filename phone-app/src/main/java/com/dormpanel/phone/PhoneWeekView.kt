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
        val last = maxOf(1080, segments.maxOfOrNull { maxOf(it.end, it.start + 24) } ?: 1080)
        val scale = resources.displayMetrics.density * 2f
        val height = ((last - first) * scale).toInt() + context.dp(24)
        val axis = FrameLayout(context)
        addView(axis, LayoutParams(context.dp(32), height))
        for (minute in first..last step 60) {
            axis.addView(TextView(context).apply { text = "%02d:%02d".format(minute / 60, minute % 60); textSize = 9f },
                FrameLayout.LayoutParams(-1, context.dp(24)).apply { topMargin = ((minute - first) * scale).toInt() })
        }
        repeat(7) { day ->
            val column = FrameLayout(context)
            addView(column, LayoutParams(0, height, 1f))
            for (minute in first..last step 60) column.addView(android.view.View(context).apply {
                setBackgroundColor(Color.GRAY); alpha = .35f
            }, FrameLayout.LayoutParams(-1, 1).apply { topMargin = ((minute - first) * scale).toInt() })
            WeekLayout.groups(segments.filter { it.day == day }).forEach { group ->
                val start = group.first().start
                val end = group.maxOf { maxOf(it.end, it.start + 24) }
                val item = group.first().item
                val label = if (group.size > 1) "${group.size} 门重叠\n" + group.joinToString(" / ") { it.item.entry.title }
                    else "${item.entry.title}\n${item.start.atZone(zone).toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))}\n–${item.end.atZone(zone).toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))}\n${item.entry.location}${if (item.thisWeekEdited || item.allWeeksEdited) " · 已修改" else ""}"
                column.addView(context.scheduleButton(label) {
                    if (group.size == 1) detail(item) else AlertDialog.Builder(context).setTitle("重叠课程")
                        .setItems(group.map { "${it.item.entry.title}\n${context.scheduleTime(it.item.start)}–${context.scheduleTime(it.item.end)} · ${it.item.entry.location}" }.toTypedArray()) { _, i -> detail(group[i].item) }
                        .setNegativeButton("关闭", null).show()
                }.apply {
                    textSize = 10f; minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                    setPadding(context.dp(2), 0, context.dp(2), 0)
                    gravity = Gravity.TOP or Gravity.START
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(Color.WHITE); backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(45, 79, 130))
                    contentDescription = group.joinToString("；") { "${it.item.entry.title}，${it.item.start.atZone(zone)} 至 ${it.item.end.atZone(zone)}，${it.item.entry.location}，${it.item.teacher}，${it.item.periodLabel.orEmpty()}${if (it.item.thisWeekEdited || it.item.allWeeksEdited) "，已修改" else ""}" }
                }, FrameLayout.LayoutParams(-1, ((end - start) * scale).toInt()).apply {
                    topMargin = ((start - first) * scale).toInt(); marginStart = context.dp(1); marginEnd = context.dp(1)
                })
            }
        }
    }
}
