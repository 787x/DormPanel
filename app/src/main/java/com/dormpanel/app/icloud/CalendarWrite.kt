package com.dormpanel.app.icloud

import okhttp3.HttpUrl

internal data class DavCalendarObject(val etag: String?, val ics: String)
internal data class DavWriteResponse(val code: Int, val retryAt: Long)
internal class CalDavWriteNotSent(problem: CalDavProblem, retryAt: Long) : CalDavFailure(problem, retryAt)
internal enum class CalendarWriteKind { CREATE, UPDATE, DELETE }
internal enum class CalendarWriteState(val label: String) {
    QUEUED("本机已保存，等待写入"),
    WRITING("正在写入"),
    CHECKING("写入结果待确认"),
    FAILED("写入失败"),
    CONFLICT("检测到远端冲突"),
    CONFIRMED("iCloud 已确认"),
}

internal data class CloudObjectRef(
    val account: String, val calendarId: String, val collection: HttpUrl, val href: HttpUrl,
    val uid: String, val recurrenceId: String?, val etag: String?, val resourceId: String?,
) {
    fun matches(account: String, calendar: CloudCalendar) =
        this.account == account && calendarId == calendar.id && collection == calendar.url && resourceId == calendar.resourceId
}

internal data class CloudEditBase(
    val ref: CloudObjectRef, val calendarName: String, val event: EditableCalendarObject,
    val canEdit: Boolean, val canDelete: Boolean,
    val isNewer: Boolean = false,
)

internal data class CalendarWrite(
    val id: String, val ref: CloudObjectRef, val calendarName: String, val kind: CalendarWriteKind,
    val draft: CloudEventDraft, val ics: String?,
    val state: CalendarWriteState = CalendarWriteState.QUEUED, val detail: String? = null,
    val attempted: Boolean = false, val acknowledged: Boolean = false, val retryAt: Long = 0,
    val verifiedEtag: String? = null,
    val cachePending: Boolean = false,
    val retryCount: Int = 0,
) {
    fun sameTarget(other: CloudObjectRef) =
        ref.account == other.account && ref.calendarId == other.calendarId && ref.href == other.href

    fun label(offline: Boolean): String =
        state.label + (if (offline && state in setOf(CalendarWriteState.QUEUED, CalendarWriteState.CHECKING)) " · 网络不可用" else "") +
            (detail?.let { " · $it" } ?: "")
}

internal fun CalendarWrite.canResumeAutomatically() =
    state == CalendarWriteState.QUEUED || state == CalendarWriteState.CHECKING

internal fun CalendarWrite.deferRetry(now: Long, initialDelay: Long): CalendarWrite {
    if (!canResumeAutomatically()) return this
    val count = retryCount.coerceIn(0, 8)
    return copy(retryAt = maxOf(retryAt, now + (initialDelay * (1L shl count)).coerceAtMost(300_000)),
        retryCount = (count + 1).coerceAtMost(8))
}

internal fun strongCalendarEtag(etag: String?): Boolean =
    etag != null && etag.length in 2..512 && etag.first() == '"' && etag.last() == '"' &&
        (1 until etag.lastIndex).all { etag[it] in '!'..'~' && etag[it] != '"' }

/** One immutable intent. Persist the uncertainty boundary before issuing any mutation. */
internal fun processCalendarWrite(
    client: CalDavClient, calendar: CloudCalendar, entry: CalendarWrite, round: CalDavRound,
    persist: (CalendarWrite) -> Unit,
): CalendarWrite {
    var op = entry
    fun state(value: CalendarWriteState, detail: String? = null, retryAt: Long = 0) =
        op.copy(state = value, detail = detail, retryAt = retryAt)
    fun blocked(detail: String, retryAt: Long = 0) =
        state(if (op.attempted || op.acknowledged) CalendarWriteState.CHECKING else CalendarWriteState.FAILED,
            detail, retryAt)
    fun conflict(message: String = "服务器版本已变化") = state(CalendarWriteState.CONFLICT, message)
    fun confirm(etag: String? = null) = state(CalendarWriteState.CONFIRMED).copy(verifiedEtag = etag)
    fun checkRemote(remote: DavCalendarObject?): CalendarWrite? {
        if (op.kind == CalendarWriteKind.DELETE) {
            return if (remote == null) {
                if (op.attempted) confirm() else conflict("服务器资源已不存在")
            } else if (remote.etag != op.ref.etag) conflict() else null
        }
        if (remote == null) {
            return when {
                op.kind == CalendarWriteKind.CREATE -> null
                op.acknowledged -> state(CalendarWriteState.CHECKING, "服务器回读尚未找到已接收的日程")
                else -> conflict("服务器资源已不存在")
            }
        }
        val originalVersion = op.kind == CalendarWriteKind.UPDATE && remote.etag == op.ref.etag
        if (op.attempted || originalVersion) {
            if (sameCalendarResult(op, checkNotNull(op.ics), remote.ics))
                return if (strongCalendarEtag(remote.etag)) confirm(remote.etag).copy(ics = remote.ics)
                else state(CalendarWriteState.CHECKING, "服务器未提供可验证的版本")
        }
        if (originalVersion) return null
        if (op.acknowledged && !strongCalendarEtag(remote.etag))
            return state(CalendarWriteState.CHECKING, "服务器未提供可验证的版本")
        return conflict(if (op.kind == CalendarWriteKind.CREATE) "预定的资源地址已被占用" else "服务器版本已变化")
    }

    try {
        val ref = op.ref
        if (ref.recurrenceId != null || !ref.matches(ref.account, calendar)) return conflict("无法确认原日历或资源")
        val privileges = client.verifyCollection(calendar, round)
        val remote = client.readObject(ref.collection, ref.href, round)
        checkRemote(remote)?.let { return it }
        if (op.acknowledged) return state(CalendarWriteState.CHECKING, "服务器回读尚未呈现已接收的修改")
        if (op.kind != CalendarWriteKind.CREATE) {
            if (remote == null || !strongCalendarEtag(ref.etag) || remote.etag != ref.etag) return conflict("无法确认服务器原始版本")
            if (runCatching { EditableCalendarObject.parse(remote.ics, ref.uid, op.draft.zone) }.isFailure) {
                return conflict("服务器资源已不符合安全编辑条件")
            }
        }
        val permitted = when (op.kind) {
            CalendarWriteKind.CREATE -> privileges.bind
            CalendarWriteKind.UPDATE -> client.objectPrivileges(ref.collection, ref.href, round).content
            CalendarWriteKind.DELETE -> privileges.unbind
        }
        if (!permitted) return blocked("权限不足或服务器未提供权限")
        val sending = state(CalendarWriteState.CHECKING).copy(attempted = true)
        val attemptedBefore = op.attempted
        persist(sending)
        op = sending
        val reply = try { client.mutateObject(ref.collection, ref.href, op.kind, ref.etag, op.ics, round) }
        catch (e: CalDavWriteNotSent) {
            op = op.copy(attempted = attemptedBefore)
            throw e
        }
        when (reply.code) {
            in 200..299 -> {
                val acknowledged = op.copy(acknowledged = true)
                persist(acknowledged)
                op = acknowledged
            }
            404, 409, 412 -> return conflict("服务器拒绝资源版本或写入前提（HTTP ${reply.code}）")
            429 -> return state(if (attemptedBefore) CalendarWriteState.CHECKING else CalendarWriteState.QUEUED,
                "服务器限流（HTTP 429）", reply.retryAt).copy(attempted = attemptedBefore)
            401, 403, 405, 413, 415, 422, 507, in 300..399 -> {
                op = op.copy(attempted = attemptedBefore)
                return blocked("服务器拒绝写入（HTTP ${reply.code}）", reply.retryAt)
            }
            else -> return state(CalendarWriteState.CHECKING, "服务器响应 HTTP ${reply.code}，需重新核对", reply.retryAt)
        }
        val verified = client.readObject(ref.collection, ref.href, round)
        if (op.kind == CalendarWriteKind.CREATE && verified == null) return state(CalendarWriteState.CHECKING, "服务器回读尚未找到新日程")
        return checkRemote(verified) ?: state(CalendarWriteState.CHECKING, "服务器回读尚未呈现已接收的修改")
    } catch (e: CalDavFailure) {
        if (!op.attempted && !op.acknowledged && e.problem in setOf(CalDavProblem.CANCELLED,
                CalDavProblem.NETWORK, CalDavProblem.SERVER, CalDavProblem.BUDGET, CalDavProblem.LIMITED)) {
            return state(CalendarWriteState.QUEUED, e.problem.label, e.retryAt)
        }
        // Failure to read supplies no evidence about a previously sent mutation.
        return blocked(e.problem.label, e.retryAt)
    }
}

private fun sameCalendarResult(op: CalendarWrite, expected: String, actual: String) = runCatching {
    val uid = op.ref.uid
    val zone = op.draft.zone
    EditableCalendarObject.parse(expected, uid, zone)
        .matches(EditableCalendarObject.parse(actual, uid, zone), preserveProperties = op.kind == CalendarWriteKind.UPDATE)
}.getOrDefault(false)
