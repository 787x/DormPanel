package com.dormpanel.phone

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dormpanel.app.schedule.ScheduleState
import com.dormpanel.app.schedule.ScheduleStores
import com.dormpanel.app.schedule.TermScheduleProfileStore
import java.time.Instant
import java.time.ZoneId

/** Independent of AppWidget presence. Store callbacks are bounded by WidgetRefreshCoordinator. */
internal object CourseNotificationCoordinator {
    private val main = Handler(Looper.getMainLooper())
    private var coordinator: WidgetRefreshCoordinator<ScheduleState>? = null
    private const val ACTION = "com.dormpanel.phone.COURSE_NOTICE_REFRESH"
    private const val TAG = "DormPanelCourseNotice"

    fun request(context: Context, done: () -> Unit = {}) {
        val app = context.applicationContext
        main.post {
            val machine = coordinator ?: WidgetRefreshCoordinator<ScheduleState>(
                present = { CourseNoticeSettings.read(app).mode != CourseNoticeMode.OFF ||
                    CourseLiveTracking.hasActive(app, Instant.now()) },
                later = { ms, f -> main.postDelayed(f, ms); Unit },
                read = { callback ->
                    ScheduleStores.create(app, TermScheduleProfileStore(app)).loadPresentation { result ->
                        main.post { callback(result) }
                    }
                },
                publish = { result ->
                    val now = Instant.now()
                    val opts = CourseNoticeSettings.read(app)
                    val plan = CourseNoticePlanner.plan(result.getOrThrow(), now, ZoneId.systemDefault(), opts)
                    val tracked = CourseLiveTracking.match(app, plan.occurrences, now)
                    val displayed = if (tracked != null) plan.copy(
                        course = tracked, inProgress = true, imminent = false,
                        nextWake = minOf(plan.nextWake, tracked.end)
                    ) else plan
                    CourseNotificationPublisher.publish(app, displayed, opts, promote = tracked != null)
                    schedule(app, displayed.nextWake)
                },
                timedOut = { Log.w(TAG, "Schedule read timed out; keeping last notification") },
                recover = { schedule(app, Instant.now().plusSeconds(300)) },
                cancel = {
                    app.getSystemService(AlarmManager::class.java).cancel(alarm(app))
                    CourseNotificationPublisher.cancel(app)
                }
            ).also { coordinator = it }
            machine.request(done)
        }
    }

    private fun alarm(context: Context) = PendingIntent.getBroadcast(
        context, 21001,
        Intent(context, CourseNotificationReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    private fun schedule(context: Context, next: Instant) {
        val manager = context.getSystemService(AlarmManager::class.java)
        manager.cancel(alarm(context))
        manager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            maxOf(next.toEpochMilli(), System.currentTimeMillis() + 1000),
            alarm(context)
        )
    }
}

class CourseNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(
                "com.dormpanel.phone.COURSE_NOTICE_REFRESH",
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_DATE_CHANGED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                STOP_TRACKING_ACTION
            )) return
        val pending = goAsync()
        if (intent.action == STOP_TRACKING_ACTION) CourseLiveTracking.stop(context)
        CourseNotificationCoordinator.request(context) { pending.finish() }
    }
}
