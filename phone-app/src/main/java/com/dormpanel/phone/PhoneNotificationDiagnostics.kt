package com.dormpanel.phone

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** Run from an executor; Xiaomi's content provider query may block. Read-only. */
internal object PhoneNotificationDiagnostics {
    fun report(context: Context): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        val runtimeGranted = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val enabled = runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }
            .getOrElse { "查询异常" }.toString()
        val channel = runCatching { manager.getNotificationChannel("course_status") }.getOrNull()
        val promotedEligibility = if (Build.VERSION.SDK_INT >= 36) runCatching {
            NotificationManagerCompat.from(context).canPostPromotedNotifications().toString()
        }.getOrDefault("查询失败") else "系统 API 不支持"

        // Diagnostics intentionally do not inspect a posted Notification's private RemoteViews.
        val latestPromoteRequest = CourseNoticeObservability.summary(context)
            .lines().map { it.trim() }.firstOrNull { it.startsWith("最近一次提升请求") }
            ?.substringAfter("：") ?: "无记录"
        val protocol = runCatching {
            Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0)
        }.getOrDefault(0)
        val focus = if (protocol in 1..3) runCatching {
            val extras = Bundle().apply { putString("package", context.packageName) }
            context.contentResolver.call(Uri.parse("content://miui.statusbar.notification.public"),
                "canShowFocus", null, extras)?.getBoolean("canShowFocus", false)?.toString() ?: "查询结果为空"
        }.getOrDefault("查询失败") else "未检测到焦点通知协议"
        return """
            Android API：${Build.VERSION.SDK_INT}
            通知运行时授权：${if (runtimeGranted) "已授权/无需授权" else "未授权"}
            系统通知总开关：$enabled
            课程通知渠道：${channel?.importance?.toString() ?: "尚未创建或查询失败"}
            Live Updates 系统支持：${if (Build.VERSION.SDK_INT >= 36) "支持（API 36+）" else "不支持（API 35 及以下）"}
            Live Updates 提升资格：$promotedEligibility
            当前有效跟踪会话：${if (CourseLiveTracking.hasActive(context, java.time.Instant.now())) "进行中" else "未开启"}
            最近一次提升请求：$latestPromoteRequest
            实际系统提升状态：需要系统通知对象/UI 人工确认，无法仅凭本应用 API 证明
            Xiaomi 焦点协议版本：$protocol
            Xiaomi 焦点通知用户开关：$focus
            
            注意：
            - canPostPromotedNotifications() 为 true 只代表系统允许本应用申请提升，
              不代表当前通知已被实际 promoted；setRequestPromotedOngoing() 也只发出请求。
            - 只有持续、用户主动、时间敏感的运行时活动才是官方 Live Updates 理想场景；
              课程进度在此 PR 中标记为实验性。
            - Xiaomi 焦点协议或用户开关为真，不代表小米业务场景审核通过。
            - 小米后台本地通知白名单没有可靠的公共查询 API，请在真机按文档人工验证。
            - 设备锁屏展示、实际提升和后台触发时效不能由这份诊断证明。
        """.trimIndent() + "\n\n" + CourseNoticeObservability.summary(context)
    }
}