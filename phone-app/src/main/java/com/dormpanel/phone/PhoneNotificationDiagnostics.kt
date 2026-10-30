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
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val runtimeGranted = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val channel = manager.getNotificationChannel("course_status")
        val promoted = if (Build.VERSION.SDK_INT >= 36) runCatching {
            NotificationManagerCompat.from(context).canPostPromotedNotifications().toString()
        }.getOrDefault("查询失败") else "系统 API 不支持"
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
            通知运行时授权：${if(runtimeGranted) "已授权/无需授权" else "未授权"}
            系统通知总开关：${if(enabled) "开启" else "关闭"}
            课程通知渠道：${channel?.importance?.toString() ?: "尚未创建"}
            Android Live Updates 提升资格：$promoted
            Xiaomi 焦点协议版本：$protocol
            Xiaomi 焦点通知用户开关：$focus
            主动跟踪：${if(CourseLiveTracking.hasActive(context, java.time.Instant.now())) "进行中" else "未开启"}
            
            注意：焦点协议或用户开关为真，不代表小米业务场景审核通过。
            小米后台本地通知白名单状态没有可靠的公共查询 API，请在真机按测试文档人工验证。
            设备锁屏展示、实际提升和后台触发时效不能由这份诊断证明。
        """.trimIndent()
    }
}
