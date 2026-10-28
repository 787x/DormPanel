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
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val pending = goAsync()
        TodayWidgetUpdates.request(context) { pending.finish() }
    }
}
class WideTodayWidgetProvider : TodayWidgetProvider()

/** Main-thread coordinator: one read at a time, coalesced reruns, bounded broadcast lifetime. */
internal object TodayWidgetUpdates {
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var running = false
    private var readSerial = 0L
    private val waiters = mutableListOf<() -> Unit>()
    private val components = listOf(TodayWidgetProvider::class.java, WideTodayWidgetProvider::class.java)
    fun request(context: Context, done: () -> Unit = {}) {
        val app = context.applicationContext
        main.post {
            var finished = false
            val finish = { if (!finished) { finished = true; done() } }
            waiters.add(finish)
            main.postDelayed({ finish(); waiters.remove(finish) }, 8000)
            generation++
            if (!running) read(app)
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
    private fun read(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val widgets = ids(context)
        if (widgets.isEmpty()) { schedule(context, Instant.now()); finish(); return }
        running = true
        val ticket = generation
        val serial = ++readSerial
        main.postDelayed({
            if (running && serial == readSerial) {
                readSerial++
                ids(context).forEach { manager.updateAppWidget(it, render(context, it, null, "本地课表读取超时，请打开应用检查")) }
                schedule(context, Instant.now().plusSeconds(1800)); running = false; finish()
            }
        }, 7000)
        widgets.forEach { manager.updateAppWidget(it, render(context, it, null, "课程仍在加载")) }
        try {
            val profiles = TermScheduleProfileStore(context)
            ScheduleStores.create(context, profiles).loadPresentation { loaded ->
                if (serial != readSerial) return@loadPresentation
                try {
                    if (ticket == generation) {
                        val now = Instant.now(); val zone = ZoneId.systemDefault()
                        val result = loaded.mapCatching { state ->
                            state to TodayCourses.project(state, now, zone)
                        }
                        ids(context).forEach { id -> manager.updateAppWidget(id, result.fold(
                            { render(context, id, it, null) },
                            { render(context, id, null, "本地课表读取失败，请打开应用检查") })) }
                        schedule(context, minOf(result.getOrNull()?.second?.next ?: now.plusSeconds(1800), now.plusSeconds(1800)))
                    }
                } catch (_: Exception) {
                    ids(context).forEach { manager.updateAppWidget(it, render(context, it, null, "本地课表读取失败，请打开应用检查")) }
                    schedule(context, Instant.now().plusSeconds(1800))
                } finally {
                    running = false
                    if (ticket != generation) read(context) else finish()
                }
            }
        } catch (_: Exception) {
            widgets.forEach { manager.updateAppWidget(it, render(context, it, null, "本地课表读取失败，请打开应用检查")) }
            schedule(context, Instant.now().plusSeconds(1800)); running = false; finish()
        }
    }
    private fun finish() { val copy = waiters.toList(); waiters.clear(); copy.forEach { it() } }
    private fun render(context: Context, id: Int, data: Pair<ScheduleState, TodayCourses.Day>?, error: String?): RemoteViews {
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(id)
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 140)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 140)
        val scale = context.resources.configuration.fontScale.coerceAtLeast(1f)
        val capacity = ((height - 64 * scale) / (50 * scale)).toInt().coerceIn(1, if (width >= 250) 3 else 2)
        val views = RemoteViews(context.packageName, R.layout.widget_today)
        val date = data?.second?.date ?: LocalDate.now()
        val profile = data?.first?.let { state -> state.sources.mapNotNull { it.resolvedTermKey() }.distinct()
            .mapNotNull { state.profiles[it] }.filter { it.validate() == null && it.phaseOn(date) != null }.singleOrNull() }
        val week = profile?.let { ChronoUnit.WEEKS.between(it.week1Monday, date.minusDays((date.dayOfWeek.value-1).toLong())) + 1 }?.takeIf { it > 0 }
        views.setTextViewText(R.id.widget_title, "今日课程")
        views.setTextViewText(R.id.widget_date, date.format(DateTimeFormatter.ofPattern("M月d日 EEEE")) + if (width >= 250 && week != null) " · 第${week}周" else "")
        val state = data?.first; val day = data?.second
        val message = error ?: when {
            state == null -> "课程仍在加载"
            state.entries.isEmpty() && state.sources.isEmpty() && state.imported.isEmpty() -> "尚未配置课表，点击打开应用"
            day!!.all.isEmpty() -> "今天没有课程"
            day.remaining.isEmpty() -> "今天的课程已结束"
            else -> ""
        }
        views.setTextViewText(R.id.widget_message, message)
        views.setViewVisibility(R.id.widget_message, if (message.isEmpty()) View.GONE else View.VISIBLE)
        views.removeAllViews(R.id.widget_courses)
        val zone = ZoneId.systemDefault(); val now = Instant.now()
        day?.remaining?.take(capacity)?.forEach { item ->
            val row = RemoteViews(context.packageName, R.layout.widget_course)
            val format = DateTimeFormatter.ofPattern("HH:mm")
            val time = item.start.atZone(zone).format(format) + "–" + item.end.atZone(zone).format(format)
            val metadata = listOf(item.entry.location, if (width >= 250) item.teacher else "").filter { it.isNotBlank() }.joinToString(" · ")
            row.setTextViewText(R.id.widget_course_title, item.entry.title)
            row.setTextViewText(R.id.widget_course_info, listOf(time, metadata).filter { it.isNotEmpty() }.joinToString(" · "))
            row.setTextViewText(R.id.widget_course_status, if (item.start <= now) "${now.atZone(zone).format(format)} 更新时上课中" else "")
            row.setViewVisibility(R.id.widget_course_status, if (item.start <= now && width >= 250 && height >= 150 * scale) View.VISIBLE else View.GONE)
            val colors = intArrayOf(0xff719abb.toInt(),0xff93ae76.toInt(),0xffc087a0.toInt(),0xffa494c0.toInt(),0xffc6ab70.toInt(),0xff78b4aa.toInt())
            row.setInt(R.id.widget_course_color, "setBackgroundColor", colors[TodayCourses.colorIndex(item)])
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
