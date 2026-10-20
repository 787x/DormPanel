package com.dormpanel.app.schedule

import java.time.*
import java.util.UUID

internal object SnapshotFixture {
    val dataset = "11111111-1111-4111-8111-111111111111"
    fun snapshot(revision: String = UUID.randomUUID().toString(), parent: String? = null): ScheduleSnapshot {
        val start = Instant.parse("2026-10-12T00:00:00Z").toEpochMilli()
        val end = start + 45 * 60_000
        val profile = BuiltInProfiles.term2026.copy(termKey = "custom-term", phases = BuiltInProfiles.term2026.phases.map {
            it.copy(periods = it.periods.map { period -> if (period.periodNumber == 1) period.copy(start = LocalTime.of(8, 5)) else period })
        })
        val source = ImportSource("stable-source", "课程", "classes.csv", "webdav", "https://example.org/dav/classes.csv", "Calendar", "a".repeat(64), 123,
            "Asia/Shanghai", 1, start, end, profile.termKey)
        return ScheduleSnapshot(dataset, revision, parent, 123456789, ScheduleState(
            events = listOf(CalendarEvent("event", "日历", start, end, "note")),
            entries = listOf(TimetableEntry("local", "本地课程", 2, 600, 645, "教室")),
            sources = listOf(source), imported = listOf(ImportedClassOccurrence("stable-instance", source.id, "stable-series", "uid", start, "导入课程", start, end, "Asia/Shanghai", "A101", "teacher: A", "[01-01节]")),
            dayAdjustments = listOf(DayAdjustment("2026-10-14", "weekday", 1, "调课")),
            classOverrides = listOf(
                ClassOverride.of(source.id, "stable-series", null, ClassPatch(location = "", teacher = "教师", periodStart = 1, periodEnd = 1, timingMode = "linked")),
                ClassOverride.of(source.id, "stable-series", "2026-10-12", ClassPatch(date = "2026-10-13", note = "指定周")),
                ClassOverride.of(null, "local", null, ClassPatch(title = "本地编辑"))),
            profiles = mapOf(profile.termKey to profile, BuiltInProfiles.term2026.termKey to BuiltInProfiles.term2026), customProfileKeys = setOf(profile.termKey)))
    }
}
