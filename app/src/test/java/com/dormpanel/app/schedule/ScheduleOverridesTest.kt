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

    @Test fun profileRefreshUsesChangedSnapshotWithoutRewritingImportedRows() {
        val date = monday.plusDays(2)
        val base = occurrence("a", "series", date)
        val customSource = importedSource().copy(calendarName = "湖北大学 2027-2028-1", termKey = null)
        val edit = edit(series = "series", patch = ClassPatch(periodStart = 5, periodEnd = 6, timingMode = "linked"))
        val initial = ScheduleState(sources = listOf(customSource), imported = listOf(base), classOverrides = listOf(edit))
        val store = object : ScheduleStore {
            override fun load(callback: (Result<ScheduleState>) -> Unit) = callback(Result.success(initial))
            override fun put(event: CalendarEvent, callback: (Result<Unit>) -> Unit) = callback(Result.success(Unit))
            override fun put(entry: TimetableEntry, callback: (Result<Unit>) -> Unit) = callback(Result.success(Unit))
            override fun deleteEvent(id: String, callback: (Result<Unit>) -> Unit) = callback(Result.success(Unit))
            override fun deleteEntry(id: String, callback: (Result<Unit>) -> Unit) = callback(Result.success(Unit))
            override fun close() = Unit
        }
        fun profile(start: String) = TermScheduleProfile("2027-2028-1", zone.id, monday,
            listOf(SchedulePhase(monday, listOf(PeriodTime(5, LocalTime.parse(start), LocalTime.parse("14:15")),
                PeriodTime(6, LocalTime.parse("14:20"), LocalTime.parse("15:00"))))))
        var current: TermScheduleProfile? = profile("14:00")
        val source = ScheduleSource(store, profileResolver = { current })
        var notifications = 0
        source.subscribe { notifications++ }
        assertEquals(14 * 60, week(source.state).single().entry.startMinute)
        current = profile("13:30"); source.refreshProfiles()
        assertEquals(13 * 60 + 30, week(source.state).single().entry.startMinute)
        assertEquals(1, notifications)
        source.refreshProfiles(); assertEquals(1, notifications)
        current = null; source.refreshProfiles()
        assertNull(ScheduleProjection.profile(source.state, week(source.state).single()))
        assertEquals(base.start, source.state.imported.single().start)
        assertEquals(2, notifications)
    }

    @Test fun editedBuiltInProfileResetIsVisibleWithoutRestart() {
        val date = monday.plusDays(2)
        val base = state(occurrence("a", "series", date)).copy(classOverrides = listOf(edit(series = "series",
            patch = ClassPatch(periodStart = 5, periodEnd = 6, timingMode = "linked"))))
        val store = object : ScheduleStore {
            override fun load(callback: (Result<ScheduleState>) -> Unit) = callback(Result.success(base))
            override fun put(event: CalendarEvent, callback: (Result<Unit>) -> Unit) = callback(Result.success(Unit))
            override fun put(entry: TimetableEntry, callback: (Result<Unit>) -> Unit) = callback(Result.success(Unit))
            override fun deleteEvent(id: String, callback: (Result<Unit>) -> Unit) = callback(Result.success(Unit))
            override fun deleteEntry(id: String, callback: (Result<Unit>) -> Unit) = callback(Result.success(Unit))
            override fun close() = Unit
        }
        var current = BuiltInProfiles.term2026.copy(phases = BuiltInProfiles.term2026.phases.map { phase ->
            phase.copy(periods = phase.periods.map { if (it.periodNumber == 5) it.copy(start = LocalTime.of(13, 30)) else it })
        })
        val source = ScheduleSource(store, profileResolver = { current })
        assertEquals(13 * 60 + 30, week(source.state).single().entry.startMinute)
        current = BuiltInProfiles.term2026; source.refreshProfiles()
        assertEquals(14 * 60 + 30, week(source.state).single().entry.startMinute)
    }

    @Test fun linkedTimesUseProfileTimezoneAcrossScopesAndMakeup() {
        val display = ZoneOffset.UTC
        val targetWeek = LocalDate.parse("2026-10-12")
        val wednesday = targetWeek.plusDays(2)
        val sunday = targetWeek.plusDays(6)
        val original = occurrence("a", "series", wednesday)
        val source = importedSource().copy(termKey = "2026-2027-1")
        val base = ScheduleState(sources = listOf(source), imported = listOf(original),
            profiles = mapOf(source.termKey!! to BuiltInProfiles.term2026))
        fun assertUtc(item: ClassOccurrence, date: LocalDate) {
            assertEquals(date.atTime(6, 0).toInstant(display), item.start)
            assertEquals(date.atTime(7, 40).toInstant(display), item.end)
            assertEquals(360, item.entry.startMinute)
            assertEquals(460, item.entry.endMinute)
        }
        val allWeeks = base.copy(classOverrides = listOf(edit(series = "series", patch = ClassPatch(
            periodStart = 5, periodEnd = 6, timingMode = "linked"))))
        assertUtc(ScheduleProjection.week(allWeeks, targetWeek, display).single(), wednesday)
        val moved = base.copy(classOverrides = listOf(edit(series = "series", date = wednesday,
            patch = ClassPatch(date = targetWeek.plusDays(4).toString(), timingMode = "linked"))))
        assertUtc(ScheduleProjection.week(moved, targetWeek, display).single(), targetWeek.plusDays(4))
        val makeup = base.copy(dayAdjustments = listOf(DayAdjustment(sunday.toString(), "weekday", 3)))
        assertUtc(ScheduleProjection.week(makeup, targetWeek, display).single { it.date == sunday }, sunday)
    }

    @Test fun overnightImportedClassKeepsItsInstantsForNonTimeEdits() {
        val date = monday.plusDays(2)
        val start = date.atTime(23, 0).atZone(zone).toInstant()
        val end = date.plusDays(1).atTime(1, 0).atZone(zone).toInstant()
        val base = occurrence("overnight", "night", date, periods = null).copy(start = start.toEpochMilli(),
            end = end.toEpochMilli(), originalStart = start.toEpochMilli())
        val edited = state(base).copy(classOverrides = listOf(edit(series = "night", date = date,
            patch = ClassPatch(title = "Night lab", location = "Lab", teacher = "Professor", note = "Bring notes"))))
        val result = week(edited).single()
        assertEquals(start, result.start); assertEquals(end, result.end)
        assertEquals(23 * 60, result.entry.startMinute); assertEquals(60, result.entry.endMinute)
        assertEquals("Night lab", result.entry.title); assertEquals("Professor", result.teacher)
    }

    @Test fun linkedMondayDisplaysOnSundayAndMetadataEditsKeepAcademicIdentityAndInstants() {
        val display = ZoneId.of("America/New_York")
        val academic = LocalDate.parse("2026-10-12")
        val displayDate = academic.minusDays(1)
        val start = academic.atTime(8, 0).atZone(zone).toInstant()
        val end = academic.atTime(9, 40).atZone(zone).toInstant()
        val row = occurrence("morning", "morning", academic, periods = "[01-02节]").copy(
            start = start.toEpochMilli(), end = end.toEpochMilli(), originalStart = start.toEpochMilli())
        val base = state(row)
        val displayWeek = academic.minusWeeks(1)
        fun projected(data: ScheduleState) = ScheduleProjection.week(data, displayWeek, display).single()
        fun assertPresentation(item: ClassOccurrence) {
            assertEquals(Instant.parse("2026-10-12T00:00:00Z"), item.start)
            assertEquals(Instant.parse("2026-10-12T01:40:00Z"), item.end)
            assertEquals(displayDate, item.date)
            assertEquals(item.start.atZone(display).toLocalDate(), item.date)
            assertEquals(7, item.entry.day)
            assertEquals(20 * 60, item.entry.startMinute)
            assertEquals(21 * 60 + 40, item.entry.endMinute)
            assertEquals(academic, item.academicDate)
            assertEquals(academic, item.anchorDate)
        }
        assertPresentation(projected(base))
        val thisWeek = base.copy(classOverrides = listOf(edit(series = "morning", date = academic,
            patch = ClassPatch(teacher = "Professor", note = "Bring notes"))))
        val weekly = projected(thisWeek)
        assertPresentation(weekly)
        assertEquals("Professor", weekly.teacher); assertEquals("Bring notes", weekly.note)
        val allWeeks = base.copy(classOverrides = listOf(edit(series = "morning",
            patch = ClassPatch(title = "Morning lab", location = "New room"))))
        val recurring = projected(allWeeks)
        assertPresentation(recurring)
        assertEquals("Morning lab", recurring.entry.title); assertEquals("New room", recurring.entry.location)
        listOf(ClassPatch(teacher = "Only teacher"), ClassPatch(note = "Only note")).forEach { patch ->
            val metadataOnly = projected(base.copy(classOverrides = listOf(edit(series = "morning",
                date = academic, patch = patch))))
            assertPresentation(metadataOnly)
            assertEquals(row.title, metadataOnly.entry.title)
            assertEquals(row.location, metadataOnly.entry.location)
            assertEquals(patch.teacher ?: "", metadataOnly.teacher)
            assertEquals(patch.note ?: "", metadataOnly.note)
        }
        val combined = projected(allWeeks.copy(classOverrides = allWeeks.classOverrides + thisWeek.classOverrides))
        assertPresentation(combined)
        assertEquals("Morning lab", combined.entry.title); assertEquals("Professor", combined.teacher)
        assertEquals(row, thisWeek.imported.single())
        assertTrue(ScheduleProjection.week(thisWeek, academic, display).isEmpty())
        val clock = object : ScheduleClock {
            override fun instant() = displayDate.atTime(19, 0).atZone(display).toInstant()
            override fun zone() = display
            override fun locale() = java.util.Locale.US
        }
        val card = ScheduleProjection.timetableCard(thisWeek, clock, 2, 1)
        assertEquals(ClassStatus.NEXT_TODAY, card.status)
        assertPresentation(card.occurrences.single())
        assertEquals(start, ScheduleProjection.nextBoundary(thisWeek, clock))
    }

    @Test fun linkedDateMoveKeepsOriginalAcademicAnchorAcrossDisplayWeekBoundary() {
        val display = ZoneId.of("America/New_York")
        val originalDate = LocalDate.parse("2026-10-12")
        val target = originalDate.plusDays(2)
        val start = originalDate.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val end = originalDate.atTime(9, 40).atZone(zone).toInstant().toEpochMilli()
        val row = occurrence("morning", "morning", originalDate, periods = "[01-02节]").copy(
            start = start, end = end, originalStart = start)
        val data = state(row).copy(classOverrides = listOf(edit(series = "morning", date = originalDate,
            patch = ClassPatch(date = target.toString(), note = "Moved"))))
        val moved = ScheduleProjection.week(data, originalDate, display).single()
        assertEquals(target.atTime(8, 0).atZone(zone).toInstant(), moved.start)
        assertEquals(target.atTime(9, 40).atZone(zone).toInstant(), moved.end)
        assertEquals(target.minusDays(1), moved.date)
        assertEquals(target, moved.academicDate)
        assertEquals(originalDate, moved.anchorDate)
        assertEquals("Moved", moved.note)
        assertTrue(ScheduleProjection.week(data, originalDate.minusWeeks(1), display).isEmpty())
    }

    @Test fun makeupResolvesTargetAcademicPhaseBeforeCrossingDisplayDateBoundary() {
        val display = ZoneId.of("America/New_York")
        val sourceDate = LocalDate.parse("2026-10-05")
        val target = LocalDate.parse("2026-10-08")
        val profile = TermScheduleProfile(BuiltInProfiles.TERM_2026_2027_1, zone.id, LocalDate.parse("2026-08-31"),
            listOf(SchedulePhase(LocalDate.parse("2026-08-31"), listOf(
                PeriodTime(1, LocalTime.of(8, 0), LocalTime.of(8, 45)),
                PeriodTime(2, LocalTime.of(8, 55), LocalTime.of(9, 40)))),
                SchedulePhase(target, listOf(PeriodTime(1, LocalTime.of(9, 0), LocalTime.of(9, 45)),
                    PeriodTime(2, LocalTime.of(9, 55), LocalTime.of(10, 40))))))
        assertNull(profile.validate())
        val start = sourceDate.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val end = sourceDate.atTime(9, 40).atZone(zone).toInstant().toEpochMilli()
        val row = occurrence("morning", "morning", sourceDate, periods = "[01-02节]").copy(
            start = start, end = end, originalStart = start)
        val base = state(row).copy(profiles = mapOf(profile.termKey to profile),
            dayAdjustments = listOf(DayAdjustment(target.toString(), "weekday", 1)))
        val data = base.copy(classOverrides = listOf(edit(series = "morning", date = target,
            patch = ClassPatch(teacher = "Makeup teacher", note = "Keep target phase"))))
        val copied = ScheduleProjection.week(data, sourceDate, display).single()
        assertEquals(Instant.parse("2026-10-08T01:00:00Z"), copied.start)
        assertEquals(Instant.parse("2026-10-08T02:40:00Z"), copied.end)
        assertEquals(target.minusDays(1), copied.date)
        assertEquals(3, copied.entry.day)
        assertEquals(21 * 60, copied.entry.startMinute)
        assertEquals(22 * 60 + 40, copied.entry.endMinute)
        assertEquals(target, copied.academicDate); assertEquals(target, copied.anchorDate)
        assertEquals(sourceDate, copied.copiedFrom)
        assertEquals("Makeup teacher", copied.teacher)
        val baseline = ScheduleProjection.week(base, sourceDate, display).single()
        assertEquals(baseline.start, copied.start); assertEquals(baseline.end, copied.end)
        assertEquals(baseline.date, copied.date)
    }

    @Test fun oldCsvTermRecoveryRequiresExactParserSignature() {
        val old = importedSource().copy(calendarName = "湖北大学 2027-2028-1", termKey = null)
        assertEquals("2027-2028-1", old.resolvedTermKey())
        assertNull(old.copy(calendarName = "Personal 2027-2028-1").resolvedTermKey())
        assertNull(old.copy(filename = "personal.ics").resolvedTermKey())
        assertNull(old.copy(calendarName = "湖北大学 2027-2028-1 extra").resolvedTermKey())
        assertNull(old.copy(calendarName = null, displayName = "湖北大学 2027-2028-1").resolvedTermKey())
    }
}
