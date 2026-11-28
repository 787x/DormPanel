package com.dormpanel.app.icloud

import okhttp3.HttpUrl
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

internal enum class CalendarAccess { READ_ONLY, WRITABLE, UNKNOWN }

internal data class CalendarPrivileges(val bind: Boolean = false, val content: Boolean = false, val unbind: Boolean = false) {
    companion object {
        fun from(names: List<String>): CalendarPrivileges {
            val write = "all" in names || "write" in names
            return CalendarPrivileges(write || "bind" in names, write || "write-content" in names, write || "unbind" in names)
        }
    }
}

internal data class CloudCalendar(
    val id: String, val url: HttpUrl, val name: String, val color: Int?, val access: CalendarAccess,
    val resourceId: String? = null,
    val privileges: CalendarPrivileges = CalendarPrivileges(),
)

internal data class CloudEvent(
    val uid: String, val recurrence: String?, val title: String, val location: String, val description: String,
    val start: Long, val end: Long, val firstDay: LocalDate? = null, val endDay: LocalDate? = null,
) {
    fun start(zone: ZoneId) = firstDay?.atStartOfDay(zone)?.toInstant() ?: Instant.ofEpochMilli(start)
    fun end(zone: ZoneId) = endDay?.atStartOfDay(zone)?.toInstant() ?: Instant.ofEpochMilli(end)

    fun overlaps(from: Instant, until: Instant, zone: ZoneId): Boolean {
        val s = start(zone)
        val e = end(zone)
        return s < until && (e > from || s == e && s >= from)
    }
}

internal data class CalendarWindow(val month: YearMonth, val zone: ZoneId) {
    val start: Instant get() = month.atDay(1).atStartOfDay(zone).toInstant()
    val end: Instant get() = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()
    // DATE and floating DATE-TIME can be indexed in the server's calendar timezone.
    // Pad the wire query and filter against the device's actual month before publishing.
    val queryStart: Instant get() = start.minusSeconds(2 * 86400)
    val queryEnd: Instant get() = end.plusSeconds(2 * 86400)
}

internal const val CURRENT_PROJECTION_VERSION = 1

internal data class CachedObject(val etag: String, val events: List<CloudEvent>, val projectionVersion: Int = 0) {
    val hasCurrentProjection get() = projectionVersion == CURRENT_PROJECTION_VERSION
}

internal data class CalendarSnapshot(
    val checkedAt: Long, val collectionToken: String?, val objects: Map<String, CachedObject>,
    val problem: CalDavProblem? = null,
    val collectionUrl: String? = null,
) {
    val hasCurrentProjection get() = objects.values.all { it.hasCurrentProjection }
}

internal enum class CalDavProblem(val label: String) {
    AUTH("认证失败，请检查专用 App 密码"),
    ACCESS("日历访问受限"),
    NETWORK("网络连接失败"),
    TLS("HTTPS 连接未通过验证"),
    LIMITED("服务器限流"),
    DATA("日历响应不完整或无法解析"),
    SERVER("日历服务器暂不可用"),
    STORAGE("本机日历缓存或凭据不可用"),
    CAPACITY("日历超出缓存或事件容量"),
    BUDGET("本轮日历刷新超过资源预算"),
    CANCELLED("日历刷新已取消"),
}

internal open class CalDavFailure(val problem: CalDavProblem, val retryAt: Long = 0) : Exception(problem.name)

internal fun invalidCalendar(): Nothing = throw CalDavFailure(CalDavProblem.DATA)

internal fun checkCalendar(condition: Boolean) {
    if (!condition) invalidCalendar()
}

internal fun checkCalendarCapacity(condition: Boolean) {
    if (!condition) throw CalDavFailure(CalDavProblem.CAPACITY)
}

internal const val MAX_CALENDARS = 24
internal const val MAX_OBJECTS = 600
internal const val MAX_INSTANCES = 1200
