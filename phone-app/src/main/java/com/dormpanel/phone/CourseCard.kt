package com.dormpanel.phone

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.dormpanel.app.schedule.ClassOccurrence
import com.dormpanel.app.schedule.ScheduleProjection
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Bounded content hierarchy. Metadata is optional; the original instance is always accessible. */
internal class CourseCard(context: Context, items: List<ClassOccurrence>, zone: ZoneId, click: () -> Unit) : LinearLayout(context) {
    private val item = items.first()
    private val title = field(if (items.size > 1) "${items.size} 门重叠" else item.entry.title, 12f, true)
    private val startLabel = item.start.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
    private val endLabel = item.end.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
    private val time = field("$startLabel–$endLabel",10f)
    private val room = field(item.entry.location, 10f)
    private val periods = field(ScheduleProjection.periods(item.periodLabel)?.let { if (it.first == it.second) "${it.first}节" else "${it.first}–${it.second}节" }.orEmpty(),10f)
    private val teacher = field(item.teacher, 10f)
    init {
        orientation = VERTICAL; clipChildren = true; clipToPadding = true
        setPadding(context.dp(3), context.dp(3), context.dp(3), context.dp(2))
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val palette = if (dark) intArrayOf(0x294d68,0x48583b,0x654351,0x574b70,0x635334,0x365c58) else intArrayOf(0xdceaf4,0xe4ecd9,0xf1dfe7,0xe9e1f2,0xf0e7d4,0xdaece7)
        val index = Math.floorMod((item.sourceId.orEmpty()+"|"+item.seriesId).hashCode(), palette.size)
        val shape = GradientDrawable().apply { setColor(palette[index] or (0xff shl 24)); cornerRadius = context.dp(4).toFloat() }
        background = RippleDrawable(ColorStateList.valueOf(0x40777777), shape, null)
        val ink = if (dark) Color.WHITE else Color.rgb(30,40,48)
        listOf(title,room,time,periods,teacher).forEach { it.setTextColor(ink); addView(it, LayoutParams(-1, -2)) }
        time.alpha = .85f; room.alpha = .8f
        isClickable = true; isFocusable = true; setOnClickListener { click() }
        contentDescription = items.joinToString("；") { "${it.entry.title}，${it.start.atZone(zone)} 至 ${it.end.atZone(zone)}，${it.entry.location}，${it.teacher}，${it.periodLabel.orEmpty()}${if(it.thisWeekEdited || it.allWeeksEdited) "，已修改" else ""}" }
        if (item.thisWeekEdited || item.allWeeksEdited) title.text = "• ${title.text}"
    }
    private fun field(value: String, size: Float, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; includeFontPadding = false; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        if (bold) setTypeface(typeface, Typeface.BOLD)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(1)
        val height = (MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom).coerceAtLeast(0)
        val line = title.lineHeight.coerceAtLeast(1)
        // Reserve location before optional timing/teacher; measure wrapping with the real font scale.
        val titleLines = android.text.StaticLayout.Builder.obtain(title.text, 0, title.text.length, title.paint, width)
            .setIncludePad(false).setMaxLines(8).build().lineCount.coerceAtLeast(1)
        val timeLines = if (time.paint.measureText("$startLabel–$endLabel") > width) 2 else 1
        time.text = if (timeLines == 2) "$startLabel\n$endLabel" else "$startLabel–$endLabel"
        var remaining = height
        title.visibility = if (remaining >= line) VISIBLE else GONE
        val roomLines = if (room.text.isNotBlank() && remaining >= line*2 + room.lineHeight*2) 2 else if (room.text.isNotBlank() && remaining >= line + room.lineHeight) 1 else 0
        val timingLines = if (remaining >= line*2 + roomLines*room.lineHeight + time.lineHeight*timeLines) timeLines else 0
        title.maxLines = minOf(titleLines, ((remaining-roomLines*room.lineHeight-timingLines*time.lineHeight)/line).coerceAtLeast(1), 8)
        if (title.visibility == VISIBLE) remaining -= title.maxLines*line
        listOf(room,time,periods,teacher).forEach { field ->
            val needed = android.text.StaticLayout.Builder.obtain(field.text,0,field.text.length,field.paint,width)
                .setIncludePad(false).setMaxLines(3).build().lineCount
            field.maxLines = when (field) {
                room -> minOf(needed,3,((remaining-timingLines*time.lineHeight)/field.lineHeight).coerceAtLeast(1))
                time -> timeLines
                else -> 1
            }
            field.visibility = if (field.text.isNotBlank() && remaining >= field.lineHeight*field.maxLines) VISIBLE else GONE
            if (field.visibility == VISIBLE) remaining -= field.lineHeight*field.maxLines
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
