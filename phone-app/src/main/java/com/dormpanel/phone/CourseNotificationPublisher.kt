package com.dormpanel.phone

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal const val STOP_TRACKING_ACTION = "com.dormpanel.phone.STOP_COURSE_TRACKING"

/** Normal local notification; no permanent foreground service. */
internal object CourseNotificationPublisher {
    private const val CHANNEL = "course_status"
    private const val ID = 21001
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(ID)

    fun publish(context: Context, plan: CourseNoticePlan, options: CourseNoticeOptions, promote: Boolean = false) {
        val course = plan.course ?: run { cancel(context); return }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, "课程状态", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "本地课程进度和下一节课" })
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

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
            .setSmallIcon(R.drawable.ic_timetable)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(if (options.hideOnLockscreen) NotificationCompat.VISIBILITY_PRIVATE
                else NotificationCompat.VISIBILITY_PUBLIC)
            .setPublicVersion(NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_timetable)
                .setContentTitle("DormPanel · 课程状态")
                .setContentText("点按查看课表")
                .build())
        if (plan.inProgress || plan.imminent) {
            builder.setWhen((if (plan.inProgress) course.end else course.start).toEpochMilli())
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
        } else builder.setShowWhen(false)
        if (promote && Build.VERSION.SDK_INT >= 36) {
            // A request, not proof of eligibility or elevation; do not use Xiaomi private extras.
            val stop = PendingIntent.getBroadcast(
                context, 21002,
                Intent(context, CourseNotificationReceiver::class.java).setAction(STOP_TRACKING_ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setRequestPromotedOngoing(true)
                .addAction(R.drawable.ic_timetable, "结束跟踪", stop)
                .setDeleteIntent(stop)
        } else if (promote) {
            val stop = PendingIntent.getBroadcast(
                context, 21002,
                Intent(context, CourseNotificationReceiver::class.java).setAction(STOP_TRACKING_ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_timetable, "结束跟踪", stop)
                .setDeleteIntent(stop)
        }
        manager.notify(ID, builder.build())
    }
}
