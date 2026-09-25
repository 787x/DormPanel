package com.dormpanel.app.schedule

import android.content.Context
import androidx.core.content.edit

interface ScheduleModeStore {
    fun read(): ScheduleMode
    fun write(mode: ScheduleMode)
}

class PreferencesScheduleModeStore(context: Context) : ScheduleModeStore {
    private val preferences = context.applicationContext.getSharedPreferences("schedule_view", Context.MODE_PRIVATE)

    override fun read(): ScheduleMode = try {
        ScheduleMode.entries.firstOrNull { it.name == preferences.getString("mode", null) } ?: ScheduleMode.CALENDAR
    } catch (_: ClassCastException) { ScheduleMode.CALENDAR }

    override fun write(mode: ScheduleMode) {
        preferences.edit { putString("mode", mode.name) }
    }
}
