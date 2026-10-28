package com.dormpanel.app.schedule
import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.View
import android.widget.*
import com.dormpanel.app.appearance.*
/** Native bounded fields; metadata disappears before it can overlap another card. Full data stays in details. */
internal class X08eLegacyCourseCard(context: Context, private val item: ClassOccurrence, private val colored: Boolean,
    titleText: String, timeText: String, periodText: String?, status: String, click: () -> Unit) : LinearLayout(context), AppearanceAware {
    private fun field(value: String, size: Float, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; includeFontPadding = false; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        if (bold) setTypeface(typeface, Typeface.BOLD)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val title = field(titleText, 18f, true)
    private val room = field(item.entry.location, 15f)
    private val time = field(listOfNotNull(periodText, timeText).joinToString(" · "), 13f)
    private val marker = field(status, 13f)
    init {
        orientation = VERTICAL; setPadding(context.dp(7), context.dp(6), context.dp(7), context.dp(6))
        clipChildren = true; clipToPadding = true
        listOf(title, room, time, marker).forEach { addView(it, LayoutParams(-1, -2)) }
        isClickable = true; isFocusable = true; minimumHeight = context.dp(48); setOnClickListener { click() }
        contentDescription = listOf(titleText, item.entry.location, timeText, periodText.orEmpty(), status).filter { it.isNotBlank() }.joinToString("，")
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val bounded = MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED
        val available = if (bounded) (MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom).coerceAtLeast(0) else Int.MAX_VALUE
        val titleLine = title.lineHeight.coerceAtLeast(1)
        val roomLines = if (room.text.isNotBlank() && available >= titleLine * 2 + room.lineHeight + time.lineHeight) 2 else 1
        room.maxLines = roomLines
        val showRoom = room.text.isNotBlank() && available >= titleLine + roomLines * room.lineHeight + time.lineHeight
        val showTime = available >= titleLine + time.lineHeight
        val showMarker = marker.text.isNotBlank() && available >= titleLine * 2 + (if (showRoom) roomLines * room.lineHeight else 0) + time.lineHeight + marker.lineHeight
        val reserved = (if (showRoom) roomLines * room.lineHeight else 0) + (if (showTime) time.lineHeight else 0) + (if (showMarker) marker.lineHeight else 0)
        title.maxLines = if (bounded) minOf(3, ((available - reserved) / titleLine).coerceAtLeast(1)) else 3
        title.visibility = if (available >= titleLine) VISIBLE else GONE
        room.visibility = if (showRoom) VISIBLE else GONE; time.visibility = if (showTime) VISIBLE else GONE
        marker.visibility = if (showMarker) VISIBLE else GONE
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    override fun applyAppearance(state: AppearanceState) {
        val palette = PanelPalette.forMode(state.themeMode)
        background = courseBackground(context, item, colored, state)
        title.setTextColor(palette.text); listOf(room, time, marker).forEach { it.setTextColor(palette.secondary) }
    }
}
