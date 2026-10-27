package com.dormpanel.app.schedule

/** Small, activity-independent UI state. Owned by the existing ViewModel, never by the schedule store. */
class ScheduleViewport {
    internal val offsets = mutableMapOf<String, Int>()
    internal var gridMinute: Float? = null
}
