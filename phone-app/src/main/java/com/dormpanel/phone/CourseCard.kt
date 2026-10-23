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
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Bounded content hierarchy. Metadata is optional; the original instance is always accessible. */
internal class CourseCard(context: Context, items: List<ClassOccurrence>, zone: ZoneId, click: () -> Unit) : LinearLayout(context) {
    private val item = items.first()
    private val title = field(if (items.size > 1) "${items.size} 门重叠" else item.entry.title, 12f, true)
    private val time = field("${item.start.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))}–${item.end.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))}", 10f)
    private val room = field(item.entry.location, 10f)
    init {
        orientation = VERTICAL; clipChildren = true; clipToPadding = true
        setPadding(context.dp(3), context.dp(3), context.dp(3), context.dp(2))
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val palette = if (dark) intArrayOf(0x294d68,0x48583b,0x654351,0x574b70,0x635334,0x365c58) else intArrayOf(0xdceaf4,0xe4ecd9,0xf1dfe7,0xe9e1f2,0xf0e7d4,0xdaece7)
        val index = Math.floorMod((item.sourceId.orEmpty()+"|"+item.seriesId).hashCode(), palette.size)
        val shape = GradientDrawable().apply { setColor(palette[index] or (0xff shl 24)); cornerRadius = context.dp(4).toFloat() }
        background = RippleDrawable(ColorStateList.valueOf(0x40777777), shape, null)
        val ink = if (dark) Color.WHITE else Color.rgb(30,40,48)
        listOf(title,time,room).forEach { it.setTextColor(ink); addView(it, LayoutParams(-1, -2)) }
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
        val width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val height = MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom
        val line = title.lineHeight.coerceAtLeast(1)
        // Narrow columns use two bounded name lines, never a vertical stream of single characters.
        title.maxLines = if (width >= context.dp(28) && height >= line * 2) 2 else 1
        title.visibility = if (height >= line) VISIBLE else GONE
        val used = title.maxLines * line
        time.visibility = if (width >= context.dp(78) && height >= used + time.lineHeight) VISIBLE else GONE
        room.visibility = if (width >= context.dp(78) && height >= used + time.lineHeight + room.lineHeight) VISIBLE else GONE
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
