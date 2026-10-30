package com.dormpanel.phone

import android.content.Context

/** Opt-in and phone-local; never synced with schedule-core. */
internal object CourseNoticeSettings {
    private const val STORE = "course_notice_settings"
    fun read(context: Context): CourseNoticeOptions {
        val p = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val mode = CourseNoticeMode.entries.firstOrNull { it.name == p.getString("mode", "OFF") }
            ?: CourseNoticeMode.OFF
        val lead = p.getInt("lead", 15).takeIf { it in listOf(5, 15, 30) } ?: 15
        return CourseNoticeOptions(mode, lead, p.getBoolean("private", true))
    }
    fun write(context: Context, options: CourseNoticeOptions) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .putString("mode", options.mode.name)
            .putInt("lead", options.leadMinutes.coerceIn(1, 60))
            .putBoolean("private", options.hideOnLockscreen)
            .apply()
    }
}
