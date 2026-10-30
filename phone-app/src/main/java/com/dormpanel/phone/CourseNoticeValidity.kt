package com.dormpanel.phone

/** Only a deadline is persisted, never a second copy of course data. Main-thread confined. */
internal class CourseNoticeValidity(
    private val read: () -> Long?,
    private val write: (Long?) -> Unit,
    private val cancel: () -> Unit
) {
    fun remember(until: Long) = write(until)
    fun clear() { cancel(); write(null) }
    fun expire(now: Long) {
        val until = read()
        // Unknown includes notifications left by older versions without a saved deadline.
        if (until == null || now >= until) clear()
    }
    fun retryAt(now: Long): Long {
        expire(now)
        // A retry must not replace an earlier, still-valid display boundary.
        return minOf(now + 300_000, read()?.takeIf { it > now } ?: Long.MAX_VALUE)
    }
}
