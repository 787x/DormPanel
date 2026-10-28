package com.dormpanel.app.schedule

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future

/** Immutable, pixel-independent display data. Contains no Context, Views, listeners or writers. */
internal data class X08eCourseText(val title: String, val time: String, val periods: String?, val status: String, val classic: String)
internal data class X08eWeekDisplay(val week: LocalDate, val items: List<ClassOccurrence>, val days: List<List<ClassOccurrence>>,
    val texts: Map<ClassOccurrence,X08eCourseText>, val segments: List<X08eSegment>, val range: Pair<Int,Int>,
    val axis: X08eWeekAxis?, val usesPeriods: Boolean, val lanes: List<List<List<List<X08eSegment>>>>) {
    companion object {
        fun prepare(state: ScheduleState, week: LocalDate, zone: ZoneId, style: X08eScheduleStyle, locale: Locale): X08eWeekDisplay {
            val items=X08ePreviewTrace.section("projection") { ScheduleProjection.week(state,week,zone) }
            val format=DateTimeFormatter.ofPattern("HH:mm",locale)
            val zh=locale.language=="zh"
            val texts=X08ePreviewTrace.section("card-text") { items.associateWith { item ->
                val p=X08eWeekGeometry.currentPeriods(item,ScheduleProjection.profile(state,item))?.let {
                    val range=if(it.first==it.last) "${it.first}" else "${it.first}–${it.last}"
                    if(zh) "第${range}节" else "Period $range"
                }
                val status=listOfNotNull(if(item.thisWeekEdited || item.allWeeksEdited) if(zh) "已修改" else "Edited" else null,
                    if(item.copiedFrom!=null) if(zh) "补课" else "Makeup" else null).joinToString(" · ")
                val endDate=item.end.atZone(zone).toLocalDate()
                val time="${item.start.atZone(zone).format(format)}–${if(endDate!=item.date) "${endDate.monthValue}/${endDate.dayOfMonth} " else ""}${item.end.atZone(zone).format(format)}"
                X08eCourseText(item.entry.title,time,p,status,listOf(item.entry.title,time.replace("–","\n– "),item.entry.location,
                    listOfNotNull(p,status.takeIf { it.isNotBlank() }).joinToString(" · ")).filter { it.isNotBlank() }.joinToString("\n"))
            } }
            val days=(0..6).map { day -> val date=week.plusDays(day.toLong()); items.filter { it.date==date || (day==0 && it.date<date) } }
            if(style.layout==X08eScheduleLayout.CLASSIC) return X08eWeekDisplay(week,items,days,texts,emptyList(),0 to 0,null,false,emptyList())
            return X08ePreviewTrace.section("geometry") {
                val segments=X08eWeekGeometry.segments(items,week,zone)
                val range=X08eWeekGeometry.range(segments)
                val period=if(style.layout==X08eScheduleLayout.PERIODS) X08eWeekGeometry.periods(state,items,week,zone,range.first,range.second) else null
                val axis=period ?: X08eWeekGeometry.time(range.first,range.second)
                val lanes=(0..6).map { day -> X08eWeekGeometry.groups(segments.filter { it.day==day }).map(X08eWeekGeometry::lanes) }
                X08eWeekDisplay(week,items,days,texts,segments,range,axis,period!=null,lanes)
            }
        }
    }
}

/** At most current +/- one week of data, only while the visible timetable enables swiping.
 * Epoch-owned maps prevent canceled computations from repopulating a replacement cache.
 */
internal class X08eDisplayCache(private val prepare: (ScheduleState,LocalDate,ZoneId,X08eScheduleStyle,Locale)->X08eWeekDisplay = X08eWeekDisplay::prepare) {
    private class Epoch(val state: ScheduleState, val zone: ZoneId, val style: X08eScheduleStyle, val locale: Locale, val configuration: String) {
        @Volatile var alive=true
        val values=ConcurrentHashMap<LocalDate,X08eWeekDisplay>()
        @Volatile var allowed=emptySet<LocalDate>()
    }
    private var epoch: Epoch?=null
    private var executor: java.util.concurrent.ExecutorService?=null
    private var task: Future<*>?=null
    fun configure(state: ScheduleState, zone: ZoneId, style: X08eScheduleStyle, locale: Locale, configuration: String) {
        val e=epoch
        if(e!=null && e.state===state && e.zone==zone && e.style==style && e.locale==locale && e.configuration==configuration) return
        invalidate()
        epoch=Epoch(state,zone,style,locale,configuration)
    }
    fun get(week: LocalDate): X08eWeekDisplay?=epoch?.values?.get(week)
    fun put(value: X08eWeekDisplay) { epoch?.let { if(value.week in it.allowed) it.values[value.week]=value } }
    fun warm(week: LocalDate) {
        val e=epoch ?: return
        val allowed=setOf(week.minusWeeks(1),week,week.plusWeeks(1))
        if(e.allowed==allowed && task?.isDone==false) return
        synchronized(e) { e.allowed=allowed; e.values.keys.retainAll(allowed) }
        if(listOf(week.plusWeeks(1),week.minusWeeks(1)).all { e.values.containsKey(it) }) return
        task?.cancel(true)
        val worker=executor ?: java.util.concurrent.ThreadPoolExecutor(0,1,1,java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.LinkedBlockingQueue(),java.util.concurrent.ThreadFactory { r -> Thread(r,"x08e-display-data").apply { isDaemon=true } }).also { executor=it }
        (worker as java.util.concurrent.ThreadPoolExecutor).purge()
        task=worker.submit {
            for(target in listOf(week.plusWeeks(1),week.minusWeeks(1))) {
                if(!e.alive || Thread.currentThread().isInterrupted) return@submit
                if(e.values.containsKey(target)) continue
                val data=prepare(e.state,target,e.zone,e.style,e.locale)
                synchronized(e) {
                    if(e.alive && !Thread.currentThread().isInterrupted && target in e.allowed) e.values[target]=data
                }
            }
        }
    }
    fun invalidate() {
        epoch?.let { e -> synchronized(e) { e.alive=false; e.values.clear() } }; epoch=null
        task?.cancel(true); task=null
        (executor as? java.util.concurrent.ThreadPoolExecutor)?.queue?.clear()
    }
    fun clear() { invalidate(); executor?.shutdownNow(); executor=null }
}
