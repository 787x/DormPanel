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
            val deadlines = app.getSharedPreferences("course_notice_validity", Context.MODE_PRIVATE)
            val validity = CourseNoticeValidity(
                read = { if (deadlines.contains("until")) deadlines.getLong("until", 0) else null },
                write = { until ->
                    val edit = deadlines.edit()
                    if (until == null) edit.remove("until") else edit.putLong("until", until)
                    // Persist before notify; an interrupted process must fail closed.
                    if (!edit.commit()) {
                        CourseNotificationPublisher.cancel(app)
                        if (until != null) error("Cannot persist course notice deadline")
                    }
                },
                cancel = { CourseNotificationPublisher.cancel(app) }
            )
            validity.expire(System.currentTimeMillis())
            val machine = coordinator ?: WidgetRefreshCoordinator<ScheduleState>(
                present = { CourseNoticeSettings.read(app).mode != CourseNoticeMode.OFF &&
                    CourseNotificationPublisher.available(app) },
                later = { ms, f -> main.postDelayed(f, ms); Unit },
                read = { callback ->
                    ScheduleStores.create(app, TermScheduleProfileStore(app)).loadPresentation { result ->
                        main.post { callback(result) }
                    }
                },
                publish = { result ->
                    // Failure is not an empty schedule. Recovery retains only an unexpired frame.
                    val state = result.getOrNull()
                    if (state != null) {
                        val now = Instant.now()
                        val opts = CourseNoticeSettings.read(app)
                        val plan = CourseNoticePlanner.plan(state, now, ZoneId.systemDefault(), opts)
                        validity.remember(plan.nextWake.toEpochMilli())
                        CourseNotificationPublisher.publish(app, plan, opts)
                        schedule(app, plan.nextWake)
                    } else Log.w(TAG, "Schedule read failed; checking notification expiry")
                },
                timedOut = { Log.w(TAG, "Schedule read timed out; checking notification expiry") },
                recover = { schedule(app, Instant.ofEpochMilli(validity.retryAt(System.currentTimeMillis()))) },
                cancel = {
                    app.getSystemService(AlarmManager::class.java).cancel(alarm(app))
                    validity.clear()
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
                Intent.ACTION_MY_PACKAGE_REPLACED
            )) return
        val pending = goAsync()
        CourseNotificationCoordinator.request(context) { pending.finish() }
    }
}
