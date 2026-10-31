package com.dormpanel.phone

import android.content.Context
import java.time.Instant

/** Local-only QA markers. API delivery is not evidence that SystemUI displayed a notification. */
internal object CourseNoticeObservability {
    private fun prefs(context: Context) = context.getSharedPreferences("course_notice_observation", Context.MODE_PRIVATE)
    fun scheduled(context: Context, at: Instant) {
        prefs(context).edit().putLong("planned", at.toEpochMilli()).apply()
    }
    /** Clear after alarm cancellation/disable so stale planned wakeups are not reported as current. */
    fun unscheduled(context: Context) {
        prefs(context).edit().remove("planned").apply()
    }
    fun fired(context: Context) {
        prefs(context).edit().putLong("fired", System.currentTimeMillis()).apply()
    }
    fun notifyReturned(context: Context) {
        prefs(context).edit().putLong("notified", System.currentTimeMillis()).apply()
    }
    /** Records that an explicit tracking notification requested promotion (API 36+). */
    fun promoteRequested(context: Context) {
        prefs(context).edit().putLong("promoteRequested", System.currentTimeMillis()).apply()
    }
    fun failed(context: Context, stage: String) {
        prefs(context).edit().putString("failure", stage)
            .putLong("failedAt", System.currentTimeMillis()).apply()
    }
    fun summary(context: Context): String {
        val p = prefs(context)
        fun timestamp(key: String): String {
            val ms = p.getLong(key, 0)
            return if (ms == 0L) "无记录" else Instant.ofEpochMilli(ms).toString()
        }
        return """
            下一次计划唤醒：${timestamp("planned")}
            最近闹钟广播收到：${timestamp("fired")}
            最近 notify() 正常返回：${timestamp("notified")}
            最近一次提升请求（API 36+ 跟踪中）：${timestamp("promoteRequested")}
            最近错误：${p.getString("failure", null) ?: "无记录"}
            最近错误时间：${timestamp("failedAt")}
            
            这些时间点用于与真机锁屏录像、ADB 日志对照。
            notify() 返回不意味着通知已经在 HyperOS 的 UI 上显示。
            提升请求只说明应用调用过 setRequestPromotedOngoing，不代表系统实际授予提升。
        """.trimIndent()
    }
}
