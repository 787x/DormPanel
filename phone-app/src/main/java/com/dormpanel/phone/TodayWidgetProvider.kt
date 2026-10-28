package com.dormpanel.phone

import android.app.*
import android.appwidget.*
import android.content.*
import android.os.*
import android.view.View
import android.widget.RemoteViews
import com.dormpanel.app.schedule.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

open class TodayWidgetProvider : AppWidgetProvider() {
    override fun onDeleted(context: Context, appWidgetIds: IntArray) { WidgetTitleSettings.remove(context,appWidgetIds) }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val pending = goAsync()
        TodayWidgetUpdates.request(context) { pending.finish() }
    }
}
class WideTodayWidgetProvider : TodayWidgetProvider()

/** Main-thread adapter for bounded Store reads and Launcher-owned RemoteViews. */
internal object TodayWidgetUpdates {
    private val main = Handler(Looper.getMainLooper())
    private val components = listOf(TodayWidgetProvider::class.java, WideTodayWidgetProvider::class.java)
    private var coordinator: WidgetRefreshCoordinator<ScheduleState>? = null
    fun request(context: Context, done: () -> Unit = {}) {
        val app = context.applicationContext
        main.post {
            val machine = coordinator ?: WidgetRefreshCoordinator<ScheduleState>(
                present = { ids(app).isNotEmpty() },
                later = { delay, action -> main.postDelayed({ action() }, delay); Unit },
                read = { callback ->
                    ScheduleStores.create(app, TermScheduleProfileStore(app)).loadPresentation { result ->
                        main.post { callback(result) }
                    }
                },
                publish = { result ->
                    val now = Instant.now()
                    val zone=ZoneId.systemDefault()
                    val cache=mutableMapOf<Boolean,Result<Pair<ScheduleState,TodayCourses.Day>>>()
                    var next=now.plusSeconds(1800)
                    ids(app).forEach { id ->
                        val tomorrow=WidgetTitleSettings.tomorrow(app,id)
                        val data=cache.getOrPut(tomorrow) { result.mapCatching { it to TodayCourses.display(it,now,zone,tomorrow) } }
                        AppWidgetManager.getInstance(app).updateAppWidget(id,data.fold(
                            { render(app,id,it,null) }, { render(app,id,null,"本地课表读取失败，请打开应用检查") }))
                        next=minOf(next,data.getOrNull()?.second?.next ?: now.plusSeconds(300))
                    }
                    schedule(app,next)
                },
                timedOut = { ids(app).forEach { id -> AppWidgetManager.getInstance(app).updateAppWidget(id,
                    render(app, id, null, "本地课表读取超时，请打开应用检查")) } },
                recover = { schedule(app, Instant.now().plusSeconds(300)) },
                cancel = { app.getSystemService(AlarmManager::class.java).cancel(alarm(app)) }
            ).also { coordinator = it }
            machine.request(done)
        }
    }
    private fun ids(context: Context) = components.flatMap {
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, it)).toList()
    }
    private fun alarm(context: Context) = PendingIntent.getBroadcast(context, 0,
        Intent(context, TodayWidgetProvider::class.java).setAction("com.dormpanel.phone.WIDGET_REFRESH"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun schedule(context: Context, next: Instant) {
        val manager = context.getSystemService(AlarmManager::class.java)
        if (ids(context).isEmpty()) manager.cancel(alarm(context))
        else manager.set(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), alarm(context))
    }
    private fun render(context: Context, id: Int, data: Pair<ScheduleState, TodayCourses.Day>?, error: String?): RemoteViews {
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(id)
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 140)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 140)
        val scale = context.resources.configuration.fontScale.coerceAtLeast(1f)
        val wide = width >= 250
        val available = height - 24 - 20 * scale
        val capacity = if (wide) (available / (43 * scale + 10)).toInt().coerceIn(1,3)
            else if (available >= 50 * scale + 10 + 30 * scale + 10) 2 else 1
        val views = RemoteViews(context.packageName, R.layout.widget_today)
        val date = data?.second?.date ?: LocalDate.now()
        val tomorrow = date > LocalDate.now()
        val profile = data?.first?.let { state -> state.sources.mapNotNull { it.resolvedTermKey() }.distinct()
            .mapNotNull { state.profiles[it] }.filter { it.validate() == null && it.phaseOn(date) != null }.singleOrNull() }
        val week = profile?.let { ChronoUnit.WEEKS.between(it.week1Monday, date.minusDays((date.dayOfWeek.value-1).toLong())) + 1 }?.takeIf { it > 0 }
        views.setTextViewText(R.id.widget_title, WidgetTitleSettings.get(context,id) ?: TodayCourses.title(data?.first))
        views.setTextViewText(R.id.widget_date, date.format(DateTimeFormatter.ofPattern("MM.dd")) + if (wide && week != null) "  第${week}周" else "")
        views.setTextViewText(R.id.widget_weekday, listOf("周一","周二","周三","周四","周五","周六","周日")[date.dayOfWeek.value-1] + if (tomorrow) "·明日" else "")
        val state = data?.first; val day = data?.second
        val message = error ?: when {
            state == null -> "课程仍在加载"
            state.entries.isEmpty() && state.sources.isEmpty() && state.imported.isEmpty() -> "尚未配置课表，点击打开应用"
            day!!.all.isEmpty() -> if (tomorrow) "明天没有课程" else "今天没有课程"
            day.remaining.isEmpty() -> "今天的课程已结束"
            else -> ""
        }
        views.setTextViewText(R.id.widget_message, message)
        views.setViewVisibility(R.id.widget_message, if (message.isEmpty()) View.GONE else View.VISIBLE)
        views.removeAllViews(R.id.widget_courses)
        val zone = ZoneId.systemDefault()
        day?.remaining?.take(capacity)?.forEachIndexed { index, item ->
            val row = RemoteViews(context.packageName, if (wide) R.layout.widget_course_wide else R.layout.widget_course)
            val format = DateTimeFormatter.ofPattern("HH:mm")
            val time = item.start.atZone(zone).format(format) + "–" + item.end.atZone(zone).format(format)
            val metadata = listOf(item.entry.location, if (width >= 250) item.teacher else "").filter { it.isNotBlank() }.joinToString(" · ")
            row.setTextViewText(R.id.widget_course_title, item.entry.title)
            val compactSecondary = !wide && index > 0
            row.setTextViewText(R.id.widget_course_info, if (compactSecondary) "$time  ${item.entry.location}" else metadata)
            row.setViewVisibility(R.id.widget_course_info, if (metadata.isBlank() && !compactSecondary) View.GONE else View.VISIBLE)
            row.setTextViewText(R.id.widget_course_status, time)
            row.setViewVisibility(R.id.widget_course_status, if (wide || compactSecondary) View.GONE else View.VISIBLE)
            if (compactSecondary) {
                row.setTextViewTextSize(R.id.widget_course_title, android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                row.setTextViewTextSize(R.id.widget_course_info, android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
            }
            if (wide) {
                row.setTextViewText(R.id.widget_course_start, item.start.atZone(zone).format(format))
                row.setTextViewText(R.id.widget_course_end, item.end.atZone(zone).format(format))
            }
            row.setImageViewResource(R.id.widget_course_color, CourseColors.accentDrawable(item))
            row.setContentDescription(R.id.widget_course_row, "${item.entry.title}，$time，${item.entry.location}，${item.teacher}")
            views.addView(R.id.widget_courses, row)
        }
        val intent = Intent(context, TimetableActivity::class.java).setAction("com.dormpanel.phone.OPEN_TODAY")
            .setData(android.net.Uri.parse("dormpanel://widget/$id/today"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        return views
    }
}
