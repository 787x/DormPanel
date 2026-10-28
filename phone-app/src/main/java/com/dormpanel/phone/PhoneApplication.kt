package com.dormpanel.phone

import android.app.Application
import com.dormpanel.app.schedule.ScheduleCommitSignals

class PhoneApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ScheduleCommitSignals.changed = { TodayWidgetUpdates.request(this) }
    }
}
