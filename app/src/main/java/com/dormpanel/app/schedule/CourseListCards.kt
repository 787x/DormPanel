package com.dormpanel.app.schedule

import android.annotation.SuppressLint
import android.content.Context
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import android.os.Parcelable
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

/** A single display operation never reads the clock again after capture. */
internal data class CourseCardSnapshot(val state: ScheduleState, val now: Instant, val zone: ZoneId,
    val today: LocalDate, val locale: java.util.Locale) {
    companion object {
        fun capture(source: ScheduleSource): CourseCardSnapshot {
            val now = source.clock.instant()
            val zone = source.clock.zone()
            return CourseCardSnapshot(source.state, now, zone, now.atZone(zone).toLocalDate(), source.clock.locale())
        }
    }
}

/** At most two projected days per shared source; remaining rows use the caller's time basis. */
internal object CourseCardDays {
    private data class Cache(val state: ScheduleState, val zone: ZoneId, val date: LocalDate, val days: List<CourseDayProjection.Day>)
    private val cache = java.util.WeakHashMap<ScheduleSource, Cache>()
    fun get(source: ScheduleSource, snapshot: CourseCardSnapshot = CourseCardSnapshot.capture(source)): List<CourseDayProjection.Day> {
        val (state, now, zone, date) = snapshot
        val old = cache[source]
        val days = if (old != null && old.state === state && old.zone == zone && old.date == date) old.days else {
            (0L..1L).map { CourseDayProjection.projectDate(state, now, zone, date.plusDays(it)) }.also {
                cache[source] = Cache(state, zone, date, it)
            }
        }
        return days.map { it.copy(remaining = it.all.filter { item -> item.end > now }) }
    }
    fun boundary(source: ScheduleSource, snapshot: CourseCardSnapshot): Instant =
        (get(source,snapshot).flatMap { day -> day.all.flatMap { listOf(it.start,it.end) } } +
            snapshot.today.plusDays(1).atStartOfDay(snapshot.zone).toInstant()).filter { it > snapshot.now }.minOrNull()!!
}

/** Ownership is selected once, using the direction at touch slop, then retained through UP/CANCEL. */
@SuppressLint("ViewConstructor")
internal class CourseCardList(context: Context, private val scope: () -> CardInteractionScope) : RecyclerView(context) {
    private var x=0f; private var y=0f
    private var owner=0 // undecided, list, page
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!scope().enabled) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { x=event.x; y=event.y; owner=0; stopScroll() }
        if (event.actionMasked == MotionEvent.ACTION_MOVE && owner==0) {
            val dx=event.x-x; val dy=event.y-y
            if (maxOf(abs(dx),abs(dy)) > ViewConfiguration.get(context).scaledTouchSlop) {
                val direction=if(dy<0) 1 else -1
                owner=if(abs(dy)>abs(dx) && canScrollVertically(direction)) 1 else 2
                if(owner==1) { scope().claimGesture(); parent?.requestDisallowInterceptTouchEvent(true) }
                else {
                    val cancel=MotionEvent.obtain(event).apply { action=MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel); cancel.recycle()
                }
            }
        }
        return if(owner==2) true else super.dispatchTouchEvent(event)
    }
}

@SuppressLint("ViewConstructor")
internal class CourseListCardView(context: Context, appearance: AppearanceController,
    private val source: ScheduleSource, private val recent: Boolean) : DashboardCardView(context, appearance) {
    private val editors = ScheduleEditors(context, source, appearance) { interactions.enabled }
    private val display = ScheduleDisplay(this, source, ::refresh) {
        nextRender ?: CourseCardDays.boundary(source,CourseCardSnapshot.capture(source))
    }
    private val resize = Runnable { refresh() }
    private var visible = false
    private val positions = mutableMapOf<String, Parcelable?>()
    private val lists = mutableListOf<Pair<String, CourseCardList>>()
    private var basis: Pair<LocalDate,ZoneId>? = null
    private var lastState: ScheduleState? = null
    private var lastKey: List<Any>? = null
    private var lastNow: Instant? = null
    private var nextRender: Instant? = null
    internal var fullRenderCount=0; private set
    internal var holdersCreated=0; private set
    private fun tr(zh: String, en: String) = context.scheduleText(zh, en)
    init { gravity = Gravity.TOP; setPadding(context.dp(10), context.dp(5), context.dp(10), context.dp(5)) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); display.visibility(isShown) }
    override fun onDetachedFromWindow() { removeCallbacks(resize); display.visibility(false); editors.close(); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); visible=isVisible; display.visibility(isVisible) }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); display.visibility(visible && visibility==VISIBLE) }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        if(w!=oldw || h!=oldh) { removeCallbacks(resize); post(resize) }
    }
    private fun open(date: LocalDate) {
        if (!interactions.enabled) return
        interactions.claimGesture()
        var c=context
        while(c is android.content.ContextWrapper) {
            if(c is com.dormpanel.app.MainActivity) { c.openScheduleDate(date); return }
            c=c.baseContext
        }
    }
    private fun text(value: String,size: Float,secondary: Boolean=false) = object : TextView(context), AppearanceAware {
        override fun applyAppearance(state: AppearanceState) {
            val palette=PanelPalette.forMode(state.themeMode)
            setTextColor(if(secondary) palette.secondary else palette.text)
        }
    }.apply { text=value; textSize=size; includeFontPadding=false; applyAppearance(appearance.state) }
    private data class Row(val date: LocalDate, val item: ClassOccurrence?=null, val message: String?=null)
    override fun render() {
        val snapshot=CourseCardSnapshot.capture(source)
        val state=snapshot.state
        val key=listOf(width,height,interactions.enabled,source.ready,source.error,source.applyingSnapshot,snapshot.today,snapshot.zone,snapshot.locale)
        // Grid bind/appearance/visibility can repeat in one layout pass. Rebuild only for
        // changed data/geometry or a presentation boundary, never merely for a later timestamp.
        if(state===lastState && key==lastKey && lastNow?.let { snapshot.now>=it }==true && nextRender?.let { snapshot.now<it }==true) return
        fullRenderCount++
        lists.forEach { (key,list) -> positions[key]=list.layoutManager?.onSaveInstanceState() }; lists.clear()
        val dateBasis=snapshot.today to snapshot.zone
        if(basis!=dateBasis) { positions.clear(); basis=dateBasis }
        lastState=state; lastKey=key; lastNow=snapshot.now
        val days=CourseCardDays.get(source,snapshot)
        nextRender=CourseCardDays.boundary(source,snapshot)
        removeAllViews()
        val compact=height<context.dp(160)
        val wide=recent && width-paddingLeft-paddingRight>=context.dp(470)
        fun dateLabel(date: LocalDate)="${date.monthValue}/${date.dayOfMonth} ${date.dayOfWeek.getDisplayName(TextStyle.SHORT,snapshot.locale)}"
        val heading=text(context.getString(if(recent) R.string.schedule_recent else R.string.schedule_today)+" · "+dateLabel(snapshot.today),if(compact) 14f else 19f)
        heading.maxLines=1; heading.ellipsize=android.text.TextUtils.TruncateAt.END
        heading.setOnClickListener { open(snapshot.today) }; heading.isEnabled=interactions.enabled
        addView(heading,LayoutParams(-1,-2))
        if(!interactions.enabled) editors.close()
        val problem=when {
            source.error -> tr("读取课表失败","Could not read schedule")
            !source.ready -> tr("正在读取课表…","Loading schedule…")
            state.invalidRecords>0 -> tr("存在无效课程记录，请检查课表","Invalid schedule records; check schedule")
            else -> null
        }
        if(problem!=null) { addView(text(problem,14f)); return }
        val week=CourseDayHeaders.recentWeekLabel(state,snapshot.today,snapshot.zone,days[0] to days[1])
        if(week.isNotBlank() && !compact) heading.append(" · $week")
        fun dateHeading(date: LocalDate)=text(dateLabel(date),if(compact) 12f else 15f,true).apply {
            setOnClickListener { open(date) }; isEnabled=interactions.enabled
        }
        fun rows(index: Int): List<Row> {
            val day=days[index]; val items=if(index==0) day.remaining else day.all
            if(items.isNotEmpty()) return items.map { Row(day.date,it) }
            return listOf(Row(day.date,message=when {
                state.entries.isEmpty() && state.sources.isEmpty() && state.courseSlots.isEmpty() -> tr("未配置课表","No schedule configured")
                index==0 && day.all.isNotEmpty() -> tr("今天课程已结束","Today’s courses finished")
                else -> tr("当天无课","No courses")
            }))
        }
        fun list(key: String,rows: List<Row>): CourseCardList = CourseCardList(context) { interactions }.apply {
            layoutManager=LinearLayoutManager(context); itemAnimator=null
            adapter=object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount()=rows.size
                override fun onCreateViewHolder(parent: ViewGroup,type: Int): RecyclerView.ViewHolder {
                    holdersCreated++
                    return object : RecyclerView.ViewHolder(FrameLayout(context).apply { layoutParams=RecyclerView.LayoutParams(-1,-2) }) {}
                }
                override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
                    applyAppearanceTree(holder.itemView,appearance.state)
                }
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder,position: Int) {
                    val row=rows[position]
                    val container=holder.itemView as FrameLayout
                    container.removeAllViews()
                    container.addView(when {
                        row.item!=null -> courseRow(row.item,row.date,snapshot,compact)
                        row.message!=null -> text(row.message,14f,true)
                        else -> dateHeading(row.date)
                    })
                }
            }
            layoutManager?.onRestoreInstanceState(positions[key]); lists += key to this
        }
        val body=LinearLayout(context).apply { orientation=if(wide) HORIZONTAL else VERTICAL }
        addView(body,LayoutParams(-1,0,1f))
        if(wide) (0..1).forEach { index ->
            if(index==1) body.addView(View(context).apply { setBackgroundColor(0x22777777) },LayoutParams(context.dp(1),-1))
            val col=LinearLayout(context).apply { orientation=VERTICAL }
            col.addView(dateHeading(days[index].date),LayoutParams(-1,-2))
            col.addView(list("$index",rows(index)),LayoutParams(-1,0,1f))
            body.addView(col,LayoutParams(0,-1,1f))
        } else {
            val rows=if(recent) listOf(Row(days[0].date))+rows(0)+Row(days[1].date)+rows(1) else rows(0)
            body.addView(list("single",rows),LayoutParams(-1,-1))
        }
    }
    private fun courseRow(item: ClassOccurrence,displayDate: LocalDate,snapshot: CourseCardSnapshot,compact: Boolean): View {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(0,context.dp(4),0,context.dp(4)) }
        row.addView(object : View(context), AppearanceAware {
            override fun applyAppearance(state: AppearanceState) { background=courseBackground(context,item,true,state) }
        }.apply { applyAppearance(appearance.state) }, LayoutParams(context.dp(4),-1))
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
        field((if(recent && width-paddingLeft-paddingRight<context.dp(470)) "${displayDate.monthValue}/${displayDate.dayOfMonth} · " else "")+context.occurrenceTime(item, snapshot.zone), if(compact) 12f else 14f,true)
        if (!compact) listOf(item.entry.location,item.teacher,if(item.start<=snapshot.now) tr("正在上课","In progress") else "").filter { it.isNotBlank() }.forEach { field(it,15f,true) }
        row.addView(fields, LayoutParams(0,-2,1f)); row.minimumHeight=context.dp(48)
        row.isEnabled=interactions.enabled; row.setOnClickListener {
            if(interactions.enabled && source.ready && !source.error && !source.applyingSnapshot) {
                val current=CourseCardDays.get(source, CourseCardSnapshot.capture(source)).flatMap { it.all }.firstOrNull { it.sourceId==item.sourceId && it.seriesId==item.seriesId && it.anchorDate==item.anchorDate && it.entry.id==item.entry.id && it.start==item.start }
                if(current!=null) { interactions.claimGesture(); editors.classDetail(current) }
            }
        }

        return row
    }
    override fun applyAppearance(state: AppearanceState) {
        super.applyAppearance(state)
        for(i in 0 until childCount) applyAppearanceTree(getChildAt(i),state)
    }
}
