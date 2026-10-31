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
    /** Last publish/schedule exception already recorded; the coordinator's duplicate
     *  Result.failure callback reuses the same Throwable instance, so it must not
     *  overwrite the specific stage with a generic "Store read failed" record. */
    private var lastRecordedFailure: Throwable? = null

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
                present = {
                    val options = CourseNoticeSettings.read(app)
                    if (options.mode == CourseNoticeMode.OFF) {
                        // Turning ordinary notices off must also end an explicit tracking session.
                        CourseLiveTracking.stop(app)
                        false
                    } else CourseNotificationPublisher.available(app)
                },
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
                        val session = CourseLiveTracking.read(app)
                        val plan = CourseNoticePlanner.plan(state, now, ZoneId.systemDefault(), opts, session)
                        // A session that no longer resolves to the same in-progress instance
                        // (ended, cancelled, rescheduled, source replaced) is retired now.
                        if (session != null && plan.tracked == null) CourseLiveTracking.stop(app)
                        validity.remember(plan.nextWake.toEpochMilli())
                        try {
                            // Publisher and schedule record their own specific failure stage
                            // and rethrow. We only keep the Throwable reference for deduping
                            // the coordinator's duplicate Result.failure callback, then let
                            // the exception reach WidgetRefreshCoordinator so recovery runs.
                            CourseNotificationPublisher.publish(app, plan, opts)
                            schedule(app, plan.nextWake)
                        } catch (e: Exception) {
                            lastRecordedFailure = e
                            throw e
                        }
                    } else {
                        val failure = result.exceptionOrNull()
                        if (failure !== lastRecordedFailure) {
                            Log.w(TAG, "Schedule read failed; checking notification expiry")
                            CourseNoticeObservability.failed(app, "Store 读取失败: ${failure?.javaClass?.simpleName ?: "未知异常"}")
                        }
                        // Clear the marker after the retransmitted failure is handled, so a
                        // later genuine Store failure is always recorded.
                        lastRecordedFailure = null
                    }
                },
                timedOut = {
                    Log.w(TAG, "Schedule read timed out; checking notification expiry")
                    CourseNoticeObservability.failed(app, "Store 读取超时")
                },
                recover = {
                    // A failed recovery alarm must not escape the coordinator: the existing
                    // notification timeoutAfter and persisted validity still bound the frame.
                    runCatching { schedule(app, Instant.ofEpochMilli(validity.retryAt(System.currentTimeMillis()))) }
                },
                cancel = {
                    app.getSystemService(AlarmManager::class.java).cancel(alarm(app))
                    CourseNoticeObservability.unscheduled(app)
                    validity.clear()
                }
            ).also { coordinator = it }
            machine.request(done)
        }
    }

    /** Single reliable "end tracking" entry shared by the Activity and the notification
     *  action. Removes the saved session and immediately takes down the old tracking
     *  notification without waiting for the async Store read, then re-requests so the
     *  ordinary notice mode republishes the correct state. */
    fun stopTracking(context: Context, done: () -> Unit = {}) {
        val app = context.applicationContext
        CourseLiveTracking.stop(app)
        // Immediate, synchronous removal: never leave a stale promoted/tracking frame
        // waiting for a Store read that may fail or time out.
        runCatching { CourseNotificationPublisher.cancel(app) }
        runCatching { app.getSystemService(AlarmManager::class.java).cancel(alarm(app)) }
        CourseNoticeObservability.unscheduled(app)
        request(app, done)
    }

    private fun alarm(context: Context) = PendingIntent.getBroadcast(
        context, 21001,
        Intent(context, CourseNotificationReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    private fun schedule(context: Context, next: Instant) {
        val manager = context.getSystemService(AlarmManager::class.java)
        try {
            manager.cancel(alarm(context))
            CourseNoticeObservability.unscheduled(context)
            val target = maxOf(next.toEpochMilli(), System.currentTimeMillis() + 1000)
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, alarm(context))
            // Record the actual submitted trigger time only after setAndAllowWhileIdle returned.
            CourseNoticeObservability.scheduled(context, Instant.ofEpochMilli(target))
        } catch (e: Exception) {
            // A failed replacement must not leave the prior plan looking current.
            CourseNoticeObservability.unscheduled(context)
            CourseNoticeObservability.failed(context, "Alarm 调度异常: ${e.javaClass.simpleName}")
            throw e
        }
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
        if (intent.action == "com.dormpanel.phone.COURSE_NOTICE_REFRESH")
            CourseNoticeObservability.fired(context)
        if (intent.action == STOP_TRACKING_ACTION) {
            // Ends and takes down the old tracking frame immediately; the coordinator
            // then refreshes the ordinary notification asynchronously.
            CourseNotificationCoordinator.stopTracking(context) { pending.finish() }
        } else {
            CourseNotificationCoordinator.request(context) { pending.finish() }
        }
    }
}
