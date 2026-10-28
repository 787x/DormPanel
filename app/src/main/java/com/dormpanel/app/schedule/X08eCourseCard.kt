package com.dormpanel.app.schedule

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.*
import com.dormpanel.app.appearance.*

internal fun courseBackground(context: Context, item: ClassOccurrence, colored: Boolean, state: AppearanceState): android.graphics.drawable.Drawable {
    val start=if(X08ePreviewTrace.observer!=null) System.nanoTime() else 0L
    val dark = state.themeMode == ThemeMode.DARK
    val colors = if (dark) intArrayOf(0x294454, 0x384831, 0x523743, 0x443b56, 0x51452f, 0x2e4a46)
        else intArrayOf(0xdceaf4, 0xe4ecd9, 0xf1dfe7, 0xe9e1f2, 0xf0e7d4, 0xdaece7)
    val shape = GradientDrawable().apply {
        cornerRadius = context.dp(6).toFloat()
        setColor(if (colored) (item.sharedRgb?.let { rgb ->
            fun channel(shift: Int) = ((rgb ushr shift and 255) * (if (dark) .24 else .30) + (if (dark) 22 else 175)).toInt()
            (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        } ?: colors[X08eWeekGeometry.colorIndex(item)]) or (0xff shl 24) else PanelPalette.forMode(state.themeMode).surface)
    }
    return RippleDrawable(ColorStateList.valueOf(0x40777777), shape, null).also {
        X08ePreviewTrace.observer?.invoke("drawable",start,System.nanoTime())
    }
}

internal fun Context.occurrenceTime(item: ClassOccurrence, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
    val endDate = item.end.atZone(zone).toLocalDate()
    val format = java.time.format.DateTimeFormatter.ofPattern("HH:mm", resources.configuration.locales[0])
    return "${item.start.atZone(zone).format(format)}–${if (endDate != item.date) "${endDate.monthValue}/${endDate.dayOfMonth} " else ""}${item.end.atZone(zone).format(format)}"
}

/** A Button opts out of the global button tint traversal, including on subsequent theme changes. */
internal class X08eClassicCourseButton(context: Context, private val item: ClassOccurrence,
    private val colored: Boolean, label: String, click: () -> Unit) : Button(context), AppearanceAware {
    init {
        text = label; textSize = 16f; maxLines = 7; isAllCaps = false; gravity = Gravity.START
        minWidth = 0; minimumWidth = 0; minHeight = context.dp(48)
        ellipsize = TextUtils.TruncateAt.END; setOnClickListener { click() }
    }
    override fun applyAppearance(state: AppearanceState) {
        setTextColor(PanelPalette.forMode(state.themeMode).text)
        backgroundTintList = null
        if (colored) {
            background = courseBackground(context, item, true, state)
            setPadding(context.dp(8), context.dp(10), context.dp(8), context.dp(10))
        }
        else backgroundTintList = ColorStateList.valueOf(PanelPalette.forMode(state.themeMode).surface)
    }
}

internal interface X08eCourseTile

/** Native bounded fields; metadata disappears before it can overlap another card. Full data stays in details. */
internal class X08eClassicHierarchyCard(context: Context, private val item: ClassOccurrence, private val colored: Boolean,
    titleText: String, timeText: String, periodText: String?, status: String, click: () -> Unit) : LinearLayout(context), AppearanceAware, X08eCourseTile {
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


/** One drawable/text node; font, wrapping and visibility rules match the native field card. */
internal class X08eCourseCard(context: Context, private val item: ClassOccurrence, private val colored: Boolean,
    titleText: String, timeText: String, periodText: String?, status: String, click: () -> Unit) : View(context), AppearanceAware, X08eCourseTile {
    private class Font(context: Context, size: Float, bold: Boolean) {
        val prototype=TextView(context).apply {
            textSize=size; includeFontPadding=false
            if(bold) setTypeface(typeface,Typeface.BOLD)
        }
    }
    private data class Style(val paint: android.text.TextPaint,val line: Int,val extra: Float,val spacing: Float,val breaks: Int,val hyphen: Int)
    private companion object {
        val fonts=java.util.WeakHashMap<Context,Pair<android.content.res.Configuration,List<Style>>>()
        fun styles(c: Context): List<Style> {
            val key=c.resources.configuration
            synchronized(fonts) {
                fonts[c]?.takeIf { it.first==key }?.let { return it.second }
                val values=listOf(18f to true,15f to false,13f to false).map { (size,bold) ->
                    val v=Font(c,size,bold).prototype
                    Style(android.text.TextPaint(v.paint),v.lineHeight,v.lineSpacingExtra,v.lineSpacingMultiplier,v.breakStrategy,v.hyphenationFrequency)
                }
                fonts[c]=android.content.res.Configuration(key) to values
                return values
            }
        }
    }
    private val styles=styles(context).map { it.copy(paint=android.text.TextPaint(it.paint)) }
    private val strings=listOf(titleText,item.entry.location,listOfNotNull(periodText,timeText).joinToString(" · "),status)
    private var layouts=emptyList<android.text.Layout>()
    private var layoutWidth=-1
    private var layoutConfiguration=-1
    init {
        setPadding(context.dp(7),context.dp(6),context.dp(7),context.dp(6))
        minimumHeight=context.dp(48); isClickable=true; isFocusable=true; setOnClickListener { click() }
        contentDescription=listOf(titleText,item.entry.location,timeText,periodText.orEmpty(),status).filter { it.isNotBlank() }.joinToString("，")
    }
    private fun layout(index: Int,width: Int,lines: Int): android.text.Layout {
        val style=styles[minOf(index,2)]; val text=strings[index]
        val boring=android.text.BoringLayout.isBoring(text,style.paint)
        if(boring!=null && boring.width<=width) return android.text.BoringLayout.make(text,style.paint,width,
            android.text.Layout.Alignment.ALIGN_NORMAL,style.spacing,style.extra,boring,false)
        return android.text.StaticLayout.Builder.obtain(text,0,text.length,style.paint,width.coerceAtLeast(1))
            .setIncludePad(false).setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL).setLineSpacing(style.extra,style.spacing)
            .setBreakStrategy(style.breaks).setHyphenationFrequency(style.hyphen).build()
    }
    override fun onMeasure(w: Int,h: Int) {
        val width=(MeasureSpec.getSize(w)-paddingLeft-paddingRight).coerceAtLeast(1)
        val bounded=MeasureSpec.getMode(h)!=MeasureSpec.UNSPECIFIED
        val available=if(bounded) (MeasureSpec.getSize(h)-paddingTop-paddingBottom).coerceAtLeast(0) else Int.MAX_VALUE
        val titleLine=styles[0].line.coerceAtLeast(1); val roomLine=styles[1].line; val timeLine=styles[2].line
        val roomLines=if(strings[1].isNotBlank() && available>=titleLine*2+roomLine+timeLine) 2 else 1
        val showRoom=strings[1].isNotBlank() && available>=titleLine+roomLines*roomLine+timeLine
        val showTime=available>=titleLine+timeLine
        val showMarker=strings[3].isNotBlank() && available>=titleLine*2+(if(showRoom) roomLines*roomLine else 0)+timeLine+timeLine
        val reserved=(if(showRoom) roomLines*roomLine else 0)+(if(showTime) timeLine else 0)+(if(showMarker) timeLine else 0)
        val titleLines=if(bounded) minOf(3,((available-reserved)/titleLine).coerceAtLeast(1)) else 3
        val configuration=titleLines or (roomLines shl 3) or (if(showRoom) 32 else 0) or (if(showTime) 64 else 0) or (if(showMarker) 128 else 0) or (if(available>=titleLine) 256 else 0)
        if(layoutWidth!=width || layoutConfiguration!=configuration) {
            layoutWidth=width; layoutConfiguration=configuration
            layouts=buildList {
            if(available>=titleLine) add(layout(0,width,titleLines))
            if(showRoom) add(layout(1,width,roomLines))
            if(showTime) add(layout(2,width,1))
            if(showMarker) add(layout(3,width,1))
            }
        }
        val natural=paddingTop+paddingBottom+layouts.sumOf { it.height }
        setMeasuredDimension(MeasureSpec.getSize(w),resolveSize(maxOf(minimumHeight,natural),h))
    }
    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas); canvas.save()
        canvas.clipRect(paddingLeft,paddingTop,width-paddingRight,height-paddingBottom)
        canvas.translate(paddingLeft.toFloat(),paddingTop.toFloat())
        layouts.forEach { it.draw(canvas); canvas.translate(0f,it.height.toFloat()) }
        canvas.restore()
    }
    override fun applyAppearance(state: AppearanceState) {
        val palette=PanelPalette.forMode(state.themeMode)
        background=courseBackground(context,item,colored,state)
        styles.forEachIndexed { i,s -> s.paint.color=if(i==0) palette.text else palette.secondary }
        invalidate()
    }
}

internal fun Context.courseView(items: List<ClassOccurrence>, state: ScheduleState, style: X08eScheduleStyle,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    prepared: X08eCourseText? = null,
    click: () -> Unit): View {
    X08ePreviewTrace.cardOverride?.let { factory ->
        if(prepared!=null) return X08ePreviewTrace.section("card-views") {
            factory(this,items.first(),style.colors,prepared.title,prepared.time,prepared.periods,prepared.status,click)
        }
    }
    if(prepared!=null) return X08ePreviewTrace.section("card-views") {
        if(style.hierarchy) { if(style.layout==X08eScheduleLayout.CLASSIC) X08eClassicHierarchyCard(this,items.first(),style.colors,prepared.title,prepared.time,prepared.periods,prepared.status,click) else X08eCourseCard(this,items.first(),style.colors,prepared.title,prepared.time,prepared.periods,prepared.status,click) }
        else X08eClassicCourseButton(this,items.first(),style.colors,prepared.classic,click)
    }
    val textStart=if (X08ePreviewTrace.observer != null) System.nanoTime() else 0L
    val item = items.first()
    val title = if (items.size == 1) item.entry.title else scheduleText("${items.size} 门重叠 · ${item.entry.title}", "${items.size} overlapping · ${item.entry.title}")
    val periods = X08eWeekGeometry.currentPeriods(item, ScheduleProjection.profile(state, item))?.let {
        val range = if (it.first == it.last) "${it.first}" else "${it.first}–${it.last}"
        scheduleText("第${range}节", "Period $range")
    }
    val status = listOfNotNull(if (item.thisWeekEdited || item.allWeeksEdited) scheduleText("已修改", "Edited") else null,
        if (item.copiedFrom != null) scheduleText("补课", "Makeup") else null).joinToString(" · ")
    val time = occurrenceTime(item, zone)
    X08ePreviewTrace.observer?.invoke("card-text",textStart,System.nanoTime())
    return X08ePreviewTrace.section("card-views") { if (style.hierarchy) {
        if(style.layout==X08eScheduleLayout.CLASSIC) X08eClassicHierarchyCard(this,item,style.colors,title,time,periods,status,click)
        else X08eCourseCard(this, item, style.colors, title, time, periods, status, click)
    }
    else X08eClassicCourseButton(this, item, style.colors,
        listOf(title, time.replace("–", "\n– "), item.entry.location, listOfNotNull(periods, status.takeIf { it.isNotBlank() }).joinToString(" · "))
            .filter { it.isNotBlank() }.joinToString("\n"), click) }
}
