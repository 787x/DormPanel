package com.dormpanel.app.schedule

/** Product composition root. Add ordinary adapters here; protocol and Writer stay generic. */
object SharedTypes {
    val adapters: List<SharedTypeAdapter> = listOf(SharedCourseColor, SharedCourseSchedule) + SharedProductivity.adapters
    fun registry() = SharedTypeRegistry(adapters)
}
