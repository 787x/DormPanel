package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ScheduleOverridesTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val monday = LocalDate.parse("2026-10-05")
    private fun occurrence(id: String, series: String, date: LocalDate, title: String = "高等数学B-1",
        periods: String? = "[05-06节]", sourceId: String = "csv"): ImportedClassOccurrence {
        val start = date.atTime(14, 30).atZone(zone).toInstant().toEpochMilli()
        val end = date.atTime(16, 10).atZone(zone).toInstant().toEpochMilli()
        return ImportedClassOccurrence(id, sourceId, series, null, start, title, start, end,
            zone.id, "Room 1", "opaque CSV metadata", periods)
    }
    private fun importedSource() = ImportSource("csv", "Hubei", "personal.csv", "local_document", null,
        "湖北大学 2026-2027-1", "hash", 0, zone.id, 2, 0, 0)
    private fun state(vararg items: ImportedClassOccurrence) = ScheduleState(
        sources = listOf(importedSource()), imported = items.toList(),
        profiles = mapOf(BuiltInProfiles.TERM_2026_2027_1 to BuiltInProfiles.term2026))
    private fun week(state: ScheduleState, date: LocalDate = monday) = ScheduleProjection.week(state, date, zone)
    private fun edit(source: String? = "csv", series: String, date: LocalDate? = null, patch: ClassPatch) =
        ClassOverride.of(source, series, date?.toString(), patch)

    @Test fun noClassesSuppressesLocalAndImportedButNotEventsAndResetRestores() {
        val imported = occurrence("a", "series", monday)
        val eventStart = monday.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val base = state(imported).copy(entries = listOf(TimetableEntry("local", "Local", 1, 540, 600)),
            events = listOf(CalendarEvent("event", "Meeting", eventStart, eventStart + 3600000)))
        assertEquals(2, week(base).count { it.date == monday })
        val holiday = base.copy(dayAdjustments = listOf(DayAdjustment(monday.toString(), "none")))
        assertTrue(week(holiday).none { it.date == monday })
        val clock = object : ScheduleClock {
            override fun instant() = monday.atTime(8, 0).atZone(zone).toInstant()
            override fun zone() = zone
            override fun locale() = java.util.Locale.UK
        }
        assertEquals(ClassStatus.NO_MORE_TODAY, ScheduleProjection.timetableCard(holiday, clock, 2, 1).status)
        assertTrue(ScheduleProjection.timetableCard(holiday, clock, 2, 1).occurrences.isEmpty())
        assertEquals(1, ScheduleProjection.eventsOn(holiday, monday, zone).size)
        assertEquals(2, week(holiday.copy(dayAdjustments = emptyList())).count { it.date == monday })
    }

    @Test fun makeupCopiesTemplateWithoutRecursingThroughSourceDayOverride() {
        val friday = monday.plusDays(4)
        val sunday = monday.plusDays(6)
        val base = state(occurrence("a", "fri", friday)).copy(
            entries = listOf(TimetableEntry("local", "Local Friday", 5, 540, 600)),
            dayAdjustments = listOf(DayAdjustment(friday.toString(), "none"),
                DayAdjustment(sunday.toString(), "weekday", 5)))
        assertTrue(week(base).none { it.date == friday })
        assertEquals(2, week(base).count { it.date == sunday })
        assertEquals(friday, week(base).first { it.date == sunday }.copiedFrom)
    }

    @Test fun makeupPeriodUsesTargetDateAfterAutumnCutover() {
        val wednesday = monday.plusDays(2)
        val sunday = monday.plusDays(6)
        val base = state(occurrence("a", "wed", wednesday)).copy(
            dayAdjustments = listOf(DayAdjustment(sunday.toString(), "weekday", 3)))
        val normal = week(base).single { it.date == wednesday }
        val makeup = week(base).single { it.date == sunday }
        assertEquals(14 * 60 + 30, normal.entry.startMinute)
        assertEquals(14 * 60, makeup.entry.startMinute)
        assertEquals(15 * 60 + 40, makeup.entry.endMinute)
        assertFalse(makeup.allWeeksEdited)
        assertFalse(makeup.thisWeekEdited)
    }

    @Test fun thisWeekCanMoveAndEditWithoutChangingSourceOrLaterWeek() {
        val wednesday = monday.plusDays(2)
        val later = wednesday.plusWeeks(1)
        val original = occurrence("a", "wed", wednesday)
        val other = occurrence("b", "wed", later)
        val patch = ClassPatch(title = "Advanced", date = monday.plusDays(4).toString(),
            periodStart = 9, periodEnd = 10, timingMode = "linked", location = "",
            teacher = "Professor", note = "Bring notes")
        val edited = state(original, other).copy(classOverrides = listOf(edit(series = "wed", date = wednesday, patch = patch)))
        val changed = week(edited).single()
        assertEquals(monday.plusDays(4), changed.date)
        assertEquals("Advanced", changed.entry.title)
        assertEquals(19 * 60, changed.entry.startMinute)
        assertEquals(20 * 60 + 40, changed.entry.endMinute)
        assertEquals("", changed.entry.location)
        assertEquals("Professor", changed.teacher)
        assertEquals("Bring notes", changed.note)
        assertEquals("高等数学B-1", week(edited, monday.plusWeeks(1)).single().entry.title)
        assertEquals("Room 1", original.location)
    }

    @Test fun movingLinkedClassAcrossCutoverRecomputesItsClockTime() {
        val wednesday = monday.plusDays(2)
        val friday = monday.plusDays(4)
        val original = occurrence("a", "wed", wednesday)
        val moved = state(original).copy(classOverrides = listOf(edit(series = "wed", date = wednesday,
            patch = ClassPatch(date = friday.toString()))))
        val effective = week(moved).single()
        assertEquals(friday, effective.date)
        assertEquals(14 * 60, effective.entry.startMinute)
        assertEquals(15 * 60 + 40, effective.entry.endMinute)
    }

    @Test fun allWeeksUsesStructuralSeriesIdentityAndPhaseSpecificTimes() {
        val firstMonday = monday.minusWeeks(1)
        val before = firstMonday.plusDays(2)
        val after = before.plusWeeks(2)
        val sameTitleOtherSeries = firstMonday.plusDays(4)
        val data = state(occurrence("a", "wed", before), occurrence("b", "wed", after),
            occurrence("c", "fri", sameTitleOtherSeries))
        val changed = data.copy(classOverrides = listOf(edit(series = "wed", patch = ClassPatch(
            title = "Edited", weekday = 5, periodStart = 5, periodEnd = 6, timingMode = "linked"))))
        val first = week(changed, firstMonday)
        assertEquals(2, first.count { it.date == sameTitleOtherSeries })
        assertEquals("高等数学B-1", first.single { it.seriesId == "fri" }.entry.title)
        assertEquals(14 * 60 + 30, first.single { it.seriesId == "wed" }.entry.startMinute)
        val second = week(changed, firstMonday.plusWeeks(2)).single()
        assertEquals(14 * 60, second.entry.startMinute)
        assertEquals(firstMonday.plusWeeks(2).plusDays(4), second.date)
    }

    @Test fun customTimeRemainsFixedAcrossTermPhases() {
        val firstMonday = monday.minusWeeks(1)
        val first = occurrence("a", "wed", firstMonday.plusDays(2))
        val second = occurrence("b", "wed", monday.plusWeeks(1).plusDays(2))
        val data = state(first, second).copy(classOverrides = listOf(edit(series = "wed", patch = ClassPatch(
            timingMode = "custom", startMinute = 13 * 60 + 15, endMinute = 14 * 60 + 45))))
        assertEquals(13 * 60 + 15, week(data, firstMonday).single().entry.startMinute)
        assertEquals(13 * 60 + 15, week(data, monday.plusWeeks(1)).single().entry.startMinute)
        assertFalse(week(data, monday.plusWeeks(1)).single().linked)
    }

    @Test fun linkedEveningThreePeriodRangeUsesProfileEnd() {
        val original = occurrence("a", "wed", monday.plusDays(2))
        val data = state(original).copy(classOverrides = listOf(edit(series = "wed", patch = ClassPatch(
            periodStart = 9, periodEnd = 11, timingMode = "linked"))))
        val effective = week(data).single()
        assertEquals(19 * 60, effective.entry.startMinute)
        assertEquals(21 * 60 + 35, effective.entry.endMinute)
        assertEquals("[09-10-11节]", effective.periodLabel)
    }

    @Test fun resetAndSourceReplacementRetainOnlyMatchingSeries() {
        val wednesday = monday.plusDays(2)
        val base = occurrence("a", "wed", wednesday)
        val all = edit(series = "wed", patch = ClassPatch(title = "All weeks"))
        val one = edit(series = "wed", date = wednesday, patch = ClassPatch(title = "This week"))
        val both = state(base, occurrence("b", "wed", wednesday.plusWeeks(1))).copy(classOverrides = listOf(all, one))
        assertEquals("This week", week(both).single().entry.title)
        assertEquals("All weeks", week(both.copy(classOverrides = listOf(all))).single().entry.title)
        assertEquals("高等数学B-1", week(both.copy(classOverrides = listOf(one)), monday.plusWeeks(1)).single().entry.title)
        assertEquals("高等数学B-1", week(both.copy(classOverrides = emptyList())).single().entry.title)
        val replaced = both.copy(imported = listOf(base.copy(id = "new", location = "New source room")))
        assertEquals("This week", week(replaced).single().entry.title)
        assertEquals("New source room", week(replaced).single().entry.location)
        val unmatched = both.copy(imported = listOf(base.copy(id = "new", seriesId = "different")))
        assertEquals("高等数学B-1", week(unmatched).single().entry.title)
    }
}
