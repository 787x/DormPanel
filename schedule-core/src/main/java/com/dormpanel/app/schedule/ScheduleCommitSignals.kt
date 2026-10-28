package com.dormpanel.app.schedule

/** Data-free notification; consumers must reload the formal Store, never optimistic UI state. */
object ScheduleCommitSignals {
    @Volatile var changed: (() -> Unit)? = null
    fun notifyChanged() { runCatching { changed?.invoke() } }
}
