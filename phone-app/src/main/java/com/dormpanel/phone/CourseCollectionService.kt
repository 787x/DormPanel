package com.dormpanel.phone

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.*
import android.net.Uri
import android.view.View
import android.widget.*
import com.dormpanel.app.schedule.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class RecentCoursesWidgetProvider : TodayWidgetProvider()
class WeekCoursesWidgetProvider : TodayWidgetProvider()

/** Bounded process-local rendering cache. Only the shared coordinator reads the business Store.
 * Failure replaces success. Factories never wait for asynchronous callbacks or write business data. */
internal object CollectionWidgets {
    private val processIdentity=java.util.UUID.randomUUID().toString()
    data class Cell(val text: String, val date: LocalDate, val rgb: Int = 0,val key: String = "")
    data class Row(val key: String, val cells: List<Cell>)
    data class Snapshot(val rows: List<Row>, val error: String?, val grid: WeekWidgetGrid? = null,val basis: CollectionBasis? = null,val weekLabel: String = "")
    private val loading=Snapshot(emptyList(),"课程仍在加载")
    @Volatile private var publication=Pair(loading,emptyMap<WeekWidgetMode,Snapshot>())
    fun snapshot(isWeek: Boolean) = if(isWeek) publication.second[WeekWidgetMode()] ?: loading else publication.first
    fun demand(context: Context): CollectionDemand {
        val manager=AppWidgetManager.getInstance(context)
        val recent=manager.getAppWidgetIds(ComponentName(context,RecentCoursesWidgetProvider::class.java)).isNotEmpty()
        val weeks=manager.getAppWidgetIds(ComponentName(context,WeekCoursesWidgetProvider::class.java)).map { id ->
            WeekWidgetMode(WidgetTitleSettings.periods(context,id),if(WidgetTitleSettings.weekdays(context,id)) 5 else 7)
        }.toSet()
        return CollectionDemand(recent,weeks)
    }
    fun instanceSnapshot(context: Context,id: Int,isWeek: Boolean,now: Instant = Instant.now(),zone: ZoneId = ZoneId.systemDefault()): Snapshot {
        if(isWeek) return publication.second[WeekWidgetMode(WidgetTitleSettings.periods(context,id),if(WidgetTitleSettings.weekdays(context,id)) 5 else 7)] ?: loading
        val data=publication.first
        return if(data.basis?.current(now,zone)==true) data else Snapshot(emptyList(),data.error?.takeIf { data.rows.isEmpty() } ?: "课表正在刷新",basis=CollectionBasis.at(now,zone))
    }
    fun isCollection(context: Context,id: Int) = AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider?.className in
        listOf(RecentCoursesWidgetProvider::class.java.name,WeekCoursesWidgetProvider::class.java.name)
    fun isWeek(context: Context,id: Int) = AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider?.className == WeekCoursesWidgetProvider::class.java.name
    fun publish(context: Context,result: Result<ScheduleState>,now: Instant,zone: ZoneId) {
        val needed=demand(context)
        val date=now.atZone(zone).toLocalDate()
        val monday=date.minusDays((date.dayOfWeek.value-1).toLong())
        val weekItems by lazy { ScheduleProjection.week(result.getOrThrow(),monday,zone) }
        val colors by lazy { CourseColors.snapshot(context,result.getOrThrow()) }
        fun text(item: ClassOccurrence): String {
            val format=DateTimeFormatter.ofPattern("HH:mm")
            val start=item.start.atZone(zone); val end=item.end.atZone(zone)
            return "${item.entry.title.take(160)}\n${item.entry.location.take(100)}\n${start.format(format)}–" +
                (if(start.toLocalDate()!=end.toLocalDate()) "次日 " else "") + end.format(format) +
                (if(item.teacher.isBlank()) "" else "\n${item.teacher.take(100)}")
        }
        fun cell(item: ClassOccurrence?,date: LocalDate,empty: String) = Cell(item?.let(::text) ?: empty,date,
            item?.let { colors.rgb(it) or (0xff shl 24) } ?: 0,
            item?.let { "${it.sourceId}:${it.seriesId}:${it.anchorDate}:${it.entry.id}" } ?: "empty:$date")
        val built=CollectionProjectionPlanner.build(result,needed,recent={ state ->
            val(a,b)=CollectionProjection.recent(state,now,zone)
            require(a.all.size+b.all.size<=512) { "课程过多，请打开应用查看" }
            Snapshot((0 until maxOf(1,a.remaining.size,b.all.size)).map { i -> Row("recent:"+
                listOf(a.remaining.getOrNull(i),b.all.getOrNull(i)).joinToString("|") { item ->
                    item?.let { "${it.sourceId}:${it.seriesId}:${it.anchorDate}:${it.entry.id}" } ?: "empty"
                },listOf(cell(a.remaining.getOrNull(i),a.date,if(i!=0) "" else if(a.all.isEmpty()) "今天没有课程" else "今天的课程已结束"),
                    cell(b.all.getOrNull(i),b.date,if(i==0) "明天没有课程" else ""))) },null,
                basis=CollectionBasis(date,zone),weekLabel=CollectionProjection.recentWeekLabel(state,date,zone,a to b))
        },week={ state,mode ->
            val grid=WeekWidgetGrid.create(state,monday,zone,colors::rgb,date,mode.periods,mode.days,weekItems)
            Snapshot((0 until grid.count).map { tile -> Row("$monday:${mode.periods}:${mode.days}:$tile",(0..6).map { day ->
                Cell("",monday.plusDays(day.toLong()))
            }) },null,grid)
        })
        fun resolve(value: Result<Snapshot>): Snapshot = value.getOrElse { error ->
            Snapshot(emptyList(),if(result.isFailure) "本地课表读取失败，请打开应用检查" else
                if(error is IllegalArgumentException) "课程过多或显示配置无效，请打开应用检查" else "课程投影失败，请打开应用检查",basis=CollectionBasis(date,zone))
        }
        publication=Pair(built.recent?.let(::resolve) ?: loading,built.weeks.mapValues { resolve(it.value) })
    }

    fun render(context: Context,id: Int,state: ScheduleState?,now: Instant = Instant.now(),zone: ZoneId = ZoneId.systemDefault()): RemoteViews {
        val isWeek=isWeek(context,id)
        val data=instanceSnapshot(context,id,isWeek,now,zone)
        val date=if(isWeek) now.atZone(zone).toLocalDate() else requireNotNull(data.basis).date; val monday=date.minusDays((date.dayOfWeek.value-1).toLong())
        val views=RemoteViews(context.packageName,if(isWeek) R.layout.widget_week_collection else R.layout.widget_recent_collection)
        views.setTextViewText(R.id.collection_title,WidgetTitleSettings.get(context,id) ?: TodayCourses.title(state))
        val profile=state?.sources?.mapNotNull { it.resolvedTermKey() }?.distinct()?.mapNotNull { state.profiles[it] }
            ?.filter { it.validate()==null && it.phaseOn(date)!=null && it.timezone==ZoneId.systemDefault().id }?.singleOrNull()
        val teaching=profile?.let { ChronoUnit.WEEKS.between(it.week1Monday,monday)+1 }?.takeIf { it>0 }
        val mode=if(!isWeek) "" else if(!WidgetTitleSettings.periods(context,id)) " · 实际时间" else if(data.grid?.axis?.periodLabels?.isNotEmpty()==true) " · 按节次" else " · 实际时间（无统一节次表）"
        if(isWeek) {
            views.setTextViewText(R.id.collection_date,date.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))+mode)
            views.setTextViewText(R.id.collection_title,(WidgetTitleSettings.get(context,id) ?: TodayCourses.title(state))+
                (teaching?.let { " | 第${it}周" } ?: "")+"  周${"一二三四五六日"[date.dayOfWeek.value-1]}")
        }
        else {
            views.setTextViewText(R.id.collection_date,data.weekLabel)
            listOf("今天" to date,"明天" to date.plusDays(1)).forEachIndexed { column,(label,day) ->
                val title=android.text.SpannableString("$label ${day.format(DateTimeFormatter.ofPattern("MM.dd"))} 周${"一二三四五六日"[day.dayOfWeek.value-1]}")
                val start=label.length+1
                title.setSpan(android.text.style.ForegroundColorSpan(context.getColor(R.color.collection_secondary)),start,title.length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                title.setSpan(android.text.style.RelativeSizeSpan(.85f),start,title.length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                views.setTextViewText(headerIds[column],title)
            }
        }
        val message=data.error ?: if(isWeek && data.grid?.segments?.isEmpty()==true) "本周没有课程" else ""
        views.setTextViewText(R.id.collection_message,message)
        views.setViewVisibility(R.id.collection_message,if(message.isEmpty()) View.GONE else View.VISIBLE)
        val basisToken=if(isWeek) "week" else "${data.basis?.date}/${data.basis?.zone?.id}"
        val lists=if(isWeek) intArrayOf(R.id.collection_list) else intArrayOf(R.id.collection_list,R.id.recent_tomorrow_list)
        lists.forEachIndexed { column,list ->
            views.setViewVisibility(list,if(data.error==null) View.VISIBLE else View.GONE)
            views.setRemoteAdapter(list,Intent(context,CourseCollectionService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).putExtra("recentColumn",column).putExtra("basisDate",data.basis?.date?.toString()).putExtra("basisZone",data.basis?.zone?.id)
                .setData(Uri.parse("dormpanel://collection/$id/$isWeek/$processIdentity/${WidgetTitleSettings.periods(context,id)}/${WidgetTitleSettings.weekdays(context,id)}/$column/$basisToken")))
        }
        val intent=Intent(context,TimetableActivity::class.java).setAction("com.dormpanel.phone.OPEN_COLLECTION")
            .setData(Uri.parse("dormpanel://widget/$id/collection/$basisToken")).putExtra("widgetWeek",isWeek)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val immutable=PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openHeader=if(isWeek) intent else Intent(intent).putExtra("widgetDate",date.toString())
        views.setOnClickPendingIntent(R.id.collection_title,PendingIntent.getActivity(context,id,openHeader,immutable))
        views.setOnClickPendingIntent(R.id.collection_date,PendingIntent.getActivity(context,id,openHeader,immutable))
        if(!isWeek) (0..1).forEach { column ->
            val openDate=Intent(intent).setData(Uri.parse("dormpanel://widget/$id/recent-date/$column/$basisToken"))
                .putExtra("widgetDate",date.plusDays(column.toLong()).toString())
            views.setOnClickPendingIntent(headerIds[column],PendingIntent.getActivity(context,id,openDate,immutable))
        }
        lists.forEach { list -> views.setPendingIntentTemplate(list,PendingIntent.getActivity(context,id+100000,intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)) }
        if(isWeek) (0..6).forEach { day ->
            views.setViewVisibility(headerIds[day],if(WidgetTitleSettings.weekdays(context,id) && day>=5) View.GONE else View.VISIBLE)
            views.setTextViewText(headerIds[day],"${"一二三四五六日"[day]}\n${monday.plusDays(day.toLong()).monthValue}/${monday.plusDays(day.toLong()).dayOfMonth}")
            views.setTextColor(headerIds[day],context.getColor(if(day==date.dayOfWeek.value-1) R.color.collection_ink else R.color.collection_secondary))
            views.setInt(headerIds[day],"setBackgroundColor",if(day==date.dayOfWeek.value-1) (if(context.resources.configuration.uiMode and 48 == 32) 0xff263e53.toInt() else 0xffdceafa.toInt()) else 0)
        }
        return views
    }
    val headerIds=intArrayOf(R.id.header_0,R.id.header_1,R.id.header_2,R.id.header_3,R.id.header_4,R.id.header_5,R.id.header_6)
    val cellIds=intArrayOf(R.id.cell_0,R.id.cell_1,R.id.cell_2,R.id.cell_3,R.id.cell_4,R.id.cell_5,R.id.cell_6)
}

class CourseCollectionService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext,intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,-1),intent.getIntExtra("recentColumn",0).coerceIn(0,1),runCatching { CollectionBasis(LocalDate.parse(intent.getStringExtra("basisDate")),ZoneId.of(intent.getStringExtra("basisZone"))) }.getOrNull())
    private class Factory(val context: Context,val id: Int,val column: Int,val expectedBasis: CollectionBasis?) : RemoteViewsFactory {
        val week=CollectionWidgets.isWeek(context,id)
        var data=CollectionWidgets.Snapshot(emptyList(),null)
        var width=320
        private var requested=false
        private fun valid(): Boolean {
            if(!CollectionWidgets.isCollection(context,id)) return false
            if(week) return true
            val valid=data.basis==expectedBasis && data.basis?.current(Instant.now(),ZoneId.systemDefault())==true
            if(!valid && !requested) { requested=true; TodayWidgetUpdates.request(context) }
            return valid
        }
        override fun onCreate() { if(CollectionWidgets.isCollection(context,id)) TodayWidgetUpdates.request(context) }
        override fun onDataSetChanged() {
            if(!CollectionWidgets.isCollection(context,id)) { data=CollectionWidgets.Snapshot(emptyList(),null); return }
            val snapshot=CollectionWidgets.instanceSnapshot(context,id,week)
            data=if(week) snapshot else snapshot.copy(rows=RecentColumnRows.select(snapshot.rows,column,
                cells={ it.cells },text={ it.text },key={ it.key },row={ key,cell -> CollectionWidgets.Row(key,listOf(cell)) }))
            width=AppWidgetManager.getInstance(context).getAppWidgetOptions(id).getInt(if(context.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,340)-20
        }
        override fun onDestroy() { data=CollectionWidgets.Snapshot(emptyList(),null) }
        override fun getCount()=if(valid()) data.rows.size else 0
        override fun getViewAt(position: Int): RemoteViews? {
            if(!CollectionWidgets.isCollection(context,id)) return null
            if(!valid()) return null
            val row=data.rows.getOrNull(position) ?: return null
            return RemoteViews(context.packageName,if(week) R.layout.widget_week_row else R.layout.widget_recent_row).apply {
                if(week) {
                    val grid=data.grid ?: return null
                    setImageViewBitmap(R.id.week_tile,grid.bitmap(position,width,context.resources.configuration.fontScale,context.resources.configuration.uiMode and 48 == 32))
                }
                row.cells.forEachIndexed { index,cell ->
                    val view=CollectionWidgets.cellIds[index]
                    if(week) setViewVisibility(view,if(index < data.grid!!.dayCount) View.VISIBLE else View.GONE)
                    
                    if(!week) {
                        val parts=cell.text.split("\n")
                        setTextViewText(R.id.recent_title_0,parts.firstOrNull().orEmpty())
                        setTextViewText(R.id.recent_info_0,parts.getOrNull(1).orEmpty())
                        setTextViewText(R.id.recent_time_0,parts.drop(2).joinToString("\n"))
                    }
                    setContentDescription(view,if(week) {
                        val grid=data.grid!!
                        val a=position*grid.tileHeight; val b=a+grid.tileHeight
                        cell.date.toString()+"，"+grid.segments.filter { it.day==index && grid.axis.y(it.end.toFloat())>a && grid.axis.y(it.start.toFloat())<b }
                            .joinToString("；") { "${it.item.entry.title}，${it.item.entry.location}，${it.item.teacher}，${it.item.start}–${it.item.end}" }
                    } else cell.text)
                    if(!week) setInt(view,"setBackgroundColor",0)
                    if(!week) setInt(R.id.stripe_0,"setBackgroundColor",cell.rgb)
                    setOnClickFillInIntent(view,Intent().putExtra("widgetDate",cell.date.toString()))
                }
            }
        }
        override fun getLoadingView(): RemoteViews?=null
        override fun getViewTypeCount()=1
        override fun getItemId(position: Int): Long = data.rows.getOrNull(position)?.key?.fold(1125899906842597L) { a,c -> a*31+c.code } ?: position.toLong()
        override fun hasStableIds()=true
    }
}
