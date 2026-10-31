package com.dormpanel.phone

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal const val STOP_TRACKING_ACTION = "com.dormpanel.phone.STOP_COURSE_TRACKING"

/** One PendingIntent identity per tracking session: the data URI includes the session token,
 *  so FLAG_UPDATE_CURRENT can never rewrite an older session's stop action into a newer one.
 *  Extras carry only the token (no course content or credentials). */
internal fun trackingStopPendingIntent(context: Context, sessionToken: String): PendingIntent =
    PendingIntent.getBroadcast(
        context, 21002,
        Intent(context, CourseNotificationReceiver::class.java)
            .setAction(STOP_TRACKING_ACTION)
            .setData(Uri.parse("$TRACKING_STOP_URI/$sessionToken"))
            .putExtra(EXTRA_TRACKING_SESSION, sessionToken),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

/** Normal local notification; no permanent foreground service. */
internal object CourseNotificationPublisher {
    private const val CHANNEL = "course_status"
    private const val ID = 21001
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(ID)

    fun available(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, "课程状态", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "本地课程进度和下一节课"
        })
        return (Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun publish(context: Context, plan: CourseNoticePlan, options: CourseNoticeOptions) {
        try {
            val course = plan.course ?: run { cancel(context); return }
            val manager = context.getSystemService(NotificationManager::class.java)
            if (options.mode == CourseNoticeMode.OFF || !available(context)) { cancel(context); return }

            val zone = ZoneId.systemDefault()
            val time = timeFormat.format(course.start.atZone(zone)) + "–" +
                timeFormat.format(course.end.atZone(zone))
            val title = when {
                plan.inProgress -> "正在上课 · ${course.entry.title}"
                plan.imminent -> "即将上课 · ${course.entry.title}"
                else -> "下一节 · ${course.entry.title}"
            }
            val subtitle = listOf(time, course.entry.location).filter { it.isNotBlank() }.joinToString(" · ")
            val open = PendingIntent.getActivity(
                context, 21001,
                Intent(context, TimetableActivity::class.java)
                    .setAction("com.dormpanel.phone.OPEN_TODAY")
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification_course)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setAutoCancel(false)
                // System-side expiry survives process death and delayed/non-exact refresh alarms.
                .setTimeoutAfter((plan.nextWake.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(1))
                .setVisibility(if (options.hideOnLockscreen) NotificationCompat.VISIBILITY_PRIVATE
                    else NotificationCompat.VISIBILITY_PUBLIC)
                .setPublicVersion(NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification_course)
                    .setContentTitle("DormPanel · 课程状态")
                    .setContentText("点按查看课表")
                    .build())
            if (plan.inProgress || plan.imminent) {
                builder.setWhen((if (plan.inProgress) course.end else course.start).toEpochMilli())
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
            } else builder.setShowWhen(false)
            if (plan.tracking) {
                val sessionId = plan.trackingSessionId
                if (sessionId != null) {
                    val stop = trackingStopPendingIntent(context, sessionId)
                    builder.addAction(R.drawable.ic_notification_course, "结束跟踪", stop)
                        .setDeleteIntent(stop)
                    // Only a valid, explicit tracking session requests promotion on Android 16+.
                    // Not a proof of eligibility or actual promotion; the system may still show a
                    // normal notification (no Xiaomi private extras, no custom RemoteViews).
                    if (Build.VERSION.SDK_INT >= 36) builder.setRequestPromotedOngoing(true)
                }
            }
            manager.notify(ID, builder.build())
            // Only after notify() actually returned do we record delivery and (for API 36+)
            // that a promotion was requested. Neither proves SystemUI showed/promoted anything.
            CourseNoticeObservability.notifyReturned(context)
            if (plan.tracking && Build.VERSION.SDK_INT >= 36) {
                CourseNoticeObservability.promoteRequested(context)
            }
        } catch (e: Exception) {
            // A failed query/build/notify() must not leave a "promotion requested"/
            // "notify returned" record. The exception is rethrown so the coordinator's
            // duplicate Result.failure callback can be deduplicated without losing scope.
            CourseNoticeObservability.failed(context, "通知发布异常: ${e.javaClass.simpleName}")
            throw e
        }
    }
}
