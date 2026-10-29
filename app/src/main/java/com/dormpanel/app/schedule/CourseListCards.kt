package com.dormpanel.app.schedule

import android.annotation.SuppressLint
import android.content.Context
import android.view.*
import android.widget.*
import androidx.core.view.doOnLayout
import com.dormpanel.app.R
import com.dormpanel.app.appearance.*
import com.dormpanel.app.dashboard.card.*
import com.dormpanel.app.dashboard.model.*
import java.time.*
import java.time.format.TextStyle
import kotlin.math.abs

class CourseListCardProvider(private val recent: Boolean, private val source: ScheduleSource,
    private val appearance: AppearanceController) : DashboardCardProvider {
    override val typeKey = if (recent) "schedule_recent" else "schedule_today"
    override val displayMetadata = CardDisplayMetadata("", "", if (recent) R.string.schedule_recent else R.string.schedule_today, R.string.productivity_local)
    override val sizePolicy = RangeCardSizePolicy(CardSize(2, 1), CardSize(8, 6))
    override val defaultSize = if (recent) CardSize(4, 3) else CardSize(3, 2)
    override fun createView(context: Context): View = CourseListCardView(context, appearance, source, recent)
    override fun bind(view: View, card: PlacedCard, interactions: CardInteractionScope) = (view as CourseListCardView).bind(card, interactions)
}

/** One bounded cache per shared source; resizing and sibling cards never reproject the same week. */
internal object CourseCardDays {
    private data class Cache(val state: ScheduleState, val zone: ZoneId, val date: LocalDate, val days: List<CourseDayProjection.Day>)
    private val cache = java.util.WeakHashMap<ScheduleSource, Cache>()
    fun get(source: ScheduleSource): List<CourseDayProjection.Day> {
        val now = source.clock.instant(); val zone = source.clock.zone(); val date = now.atZone(zone).toLocalDate()
        val old = cache[source]
        val days = if (old != null && old.state === source.state && old.zone == zone && old.date == date) old.days else {
            (0L..1L).map { CourseDayProjection.projectDate(source.state, now, zone, date.plusDays(it)) }.also {
                cache[source] = Cache(source.state, zone, date, it)
            }
        }
        return days.map { it.copy(remaining = it.all.filter { item -> item.end > now }) }
    }
}

@SuppressLint("ViewConstructor")
internal class CourseListCardView(context: Context, appearance: AppearanceController,
    private val source: ScheduleSource, private val recent: Boolean) : DashboardCardView(context, appearance) {
    private val editors = ScheduleEditors(context, source, appearance) { interactions.enabled }
    private val display = ScheduleDisplay(this, source, ::refresh) {
        val now=source.clock.instant()
        (CourseCardDays.get(source).flatMap { day -> day.all.flatMap { listOf(it.start,it.end) } } + source.clock.today().plusDays(1).atStartOfDay(source.clock.zone()).toInstant()).filter { it>now }.minOrNull()!!
    }
    private val resize = Runnable { refresh() }
    private var visible = false
    private val offsets = mutableMapOf<String, Int>()
    private var basis: LocalDate? = null
    private val scrollers = mutableListOf<Pair<String, ScrollView>>()
    private fun tr(zh: String, en: String) = context.scheduleText(zh, en)
    init { gravity = Gravity.TOP; setPadding(context.dp(10), context.dp(5), context.dp(10), context.dp(5)) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); display.visibility(isShown) }
    override fun onDetachedFromWindow() { removeCallbacks(resize); display.visibility(false); editors.close(); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); visible = isVisible; display.visibility(isVisible) }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); display.visibility(visible && visibility == VISIBLE) }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w,h,oldw,oldh); if (w != oldw || h != oldh) { removeCallbacks(resize); post(resize) } }
    private fun open(date: LocalDate) {
        if (!interactions.enabled) return
        interactions.claimGesture()
        var c = context
        while (c is android.content.ContextWrapper) {
            if (c is com.dormpanel.app.MainActivity) { c.openScheduleDate(date); return }
            c = c.baseContext
        }
    }
    private fun text(value: String, size: Float, secondary: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; includeFontPadding = false
        setTextColor(if (secondary) PanelPalette.forMode(appearance.state.themeMode).secondary else PanelPalette.forMode(appearance.state.themeMode).text)
    }
    override fun render() {
        scrollers.forEach { (key, scroll) -> offsets[key] = scroll.scrollY }; scrollers.clear()
        val today = source.clock.today()
        if (basis != today) { offsets.clear(); basis = today }
        removeAllViews()
        val compact = height < context.dp(160)
        val wide = recent && width - paddingLeft - paddingRight >= context.dp(470)
        val heading = text(context.getString(if (recent) R.string.schedule_recent else R.string.schedule_today), if (compact) 14f else 19f)
        heading.append(" · ${today.monthValue}/${today.dayOfMonth} ${today.dayOfWeek.getDisplayName(TextStyle.SHORT, source.clock.locale())}")
        heading.maxLines = 1; heading.ellipsize = android.text.TextUtils.TruncateAt.END
        heading.setOnClickListener { open(today) }; heading.isEnabled = interactions.enabled
        addView(heading, LayoutParams(-1,-2))
        if (!interactions.enabled) editors.close()
        val problem = when {
            source.error -> tr("读取课表失败", "Could not read schedule")
            !source.ready -> tr("正在读取课表…", "Loading schedule…")
            source.state.invalidRecords > 0 -> tr("存在无效课程记录，请检查课表", "Invalid schedule records; check schedule")
            else -> null
        }
        if (problem != null) { addView(text(problem,14f)); return }
        val days = CourseCardDays.get(source)
        val week = CourseDayHeaders.recentWeekLabel(source.state, today, source.clock.zone(), days[0] to days[1])
        if (week.isNotBlank() && !compact) heading.append(" · $week")
        val body = LinearLayout(context).apply { orientation = if (wide) HORIZONTAL else VERTICAL }
        addView(body, LayoutParams(-1,0,1f))
        fun group(index: Int, parent: LinearLayout) {
            val day = days[index]
            val title = "${day.date.monthValue}/${day.date.dayOfMonth} ${day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, source.clock.locale())}"
            parent.addView(text(title, if (compact) 12f else 15f, true).apply {
                setOnClickListener { open(day.date) }; isEnabled = interactions.enabled
            })
            val items = if (index == 0) day.remaining else day.all
            if (items.isEmpty()) parent.addView(text(when {
                source.state.entries.isEmpty() && source.state.sources.isEmpty() && source.state.courseSlots.isEmpty() -> tr("未配置课表", "No schedule configured")
                index == 0 && day.all.isNotEmpty() -> tr("今天课程已结束", "Today’s courses finished")
                else -> tr("当天无课", "No courses")
            }, 14f, true))
            items.forEach { item ->
                val row = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(0,context.dp(4),0,context.dp(4)) }
                row.addView(View(context).apply { background = courseBackground(context,item,true,appearance.state) }, LayoutParams(context.dp(4),-1))
                val fields = LinearLayout(context).apply { orientation = VERTICAL; setPadding(context.dp(6),0,context.dp(4),0) }
                val horizontalFields = !recent && width >= context.dp(700) && !compact
                if (horizontalFields) fields.orientation = HORIZONTAL
                fun field(value: String, size: Float, secondary: Boolean = false, weight: Float = 1f) {
                    val label = text(value,size,secondary).apply {
                        maxLines=if(compact) 1 else 3
                        ellipsize=android.text.TextUtils.TruncateAt.END
                        setPadding(0,0,context.dp(6),0)
                    }
                    fields.addView(label, if(horizontalFields) LayoutParams(0,-2,weight) else LayoutParams(-1,-2))
                }
                field(item.entry.title, if(compact) 15f else if(horizontalFields) 22f else 19f, weight=2f)
                field(context.occurrenceTime(item, source.clock.zone()), if(compact) 12f else 14f,true)
                if (!compact) listOf(item.entry.location,item.teacher,if(item.start<=source.clock.instant()) tr("正在上课","In progress") else "").filter { it.isNotBlank() }.forEach { field(it,15f,true) }
                row.addView(fields, LayoutParams(0,-2,1f)); row.minimumHeight=context.dp(48)
                row.isEnabled=interactions.enabled; row.setOnClickListener {
                    if(interactions.enabled && source.ready && !source.error && !source.applyingSnapshot) {
                        val current=CourseCardDays.get(source).flatMap { it.all }.firstOrNull { it.sourceId==item.sourceId && it.seriesId==item.seriesId && it.anchorDate==item.anchorDate && it.entry.id==item.entry.id && it.start==item.start }
                        if(current!=null) { interactions.claimGesture(); editors.classDetail(current) }
                    }
                }
                parent.addView(row,LayoutParams(-1,-2))
            }
        }
        fun scrolling(key: String, content: LinearLayout) = object : ScrollView(context) {
            private var x=0f; private var y=0f; private var claimed=false; private var pageSwipe=false
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if(!interactions.enabled) return false
                if(event.actionMasked==MotionEvent.ACTION_DOWN) { x=event.x; y=event.y; claimed=false; pageSwipe=false }
                if(event.actionMasked==MotionEvent.ACTION_MOVE && !claimed && !pageSwipe && maxOf(abs(event.x-x),abs(event.y-y))>ViewConfiguration.get(context).scaledTouchSlop && (abs(event.x-x)>abs(event.y-y) || (!canScrollVertically(1) && !canScrollVertically(-1)))) {
                    pageSwipe=true
                    val cancel=MotionEvent.obtain(event).apply { action=MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel); cancel.recycle()
                }
                if(pageSwipe) return true
                if(event.actionMasked==MotionEvent.ACTION_MOVE && !claimed && abs(event.y-y)>ViewConfiguration.get(context).scaledTouchSlop && abs(event.y-y)>abs(event.x-x)) {
                    claimed=true; interactions.claimGesture(); parent?.requestDisallowInterceptTouchEvent(true)
                }
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            addView(content); isVerticalScrollBarEnabled=true
            scrollers += key to this
            doOnLayout { scrollTo(0,offsets[key] ?: 0) }
        }
        if(wide) (0..1).forEach { index ->
            if(index==1) body.addView(View(context).apply { setBackgroundColor(0x22777777) },LayoutParams(context.dp(1),-1))
            val col=LinearLayout(context).apply { orientation=VERTICAL }; group(index,col)
            body.addView(scrolling("$index",col),LayoutParams(0,-1,1f))
        } else {
            val col=LinearLayout(context).apply { orientation=VERTICAL }; group(0,col); if(recent) group(1,col)
            body.addView(scrolling("single",col),LayoutParams(-1,-1))
        }
    }
    override fun applyAppearance(state: AppearanceState) { super.applyAppearance(state); if(isBound) render() }
}
