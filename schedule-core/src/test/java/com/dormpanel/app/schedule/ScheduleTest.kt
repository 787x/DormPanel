package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.Locale

class ScheduleTest {
    private class Clock(var now: Instant = Instant.parse("2024-02-29T09:30:00Z"), var timezone: ZoneId = ZoneOffset.UTC,
        var language: Locale = Locale.UK) : ScheduleClock {
        override fun instant() = now
        override fun zone() = timezone
        override fun locale() = language
    }
    private class Store(var data: ScheduleState = ScheduleState()) : ScheduleStore {
        var pending: ((Result<ScheduleState>) -> Unit)? = null
        var delayed = false
        var writes = 0
        var closed = false
        override fun load(callback: (Result<ScheduleState>) -> Unit) { if (delayed) pending = callback else callback(Result.success(data)) }
        override fun put(event: CalendarEvent, callback: (Result<Unit>) -> Unit) { writes++; data = data.copy(events = data.events.filterNot { it.id == event.id } + event); callback(Result.success(Unit)) }
        override fun put(entry: TimetableEntry, callback: (Result<Unit>) -> Unit) { writes++; data = data.copy(entries = data.entries.filterNot { it.id == entry.id } + entry); callback(Result.success(Unit)) }
        override fun deleteEvent(id: String, callback: (Result<Unit>) -> Unit) { data = data.copy(events = data.events.filterNot { it.id == id }); callback(Result.success(Unit)) }
        override fun deleteEntry(id: String, callback: (Result<Unit>) -> Unit) { data = data.copy(entries = data.entries.filterNot { it.id == id }); callback(Result.success(Unit)) }
        override fun close() { closed = true }
    }
    @Test fun eventCrudIdentityOrderingOverlapAndRoundTrip() {
        val store = Store(); val source = ScheduleSource(store)
        assertFalse(source.saveEvent(" ", 1, 2)); assertFalse(source.saveEvent("Invalid", 2, 1)); assertFalse(source.saveEvent("Equal", 1, 1))
        assertTrue(source.saveEvent("Later", 200, 400)); assertTrue(source.saveEvent("Earlier", 100, 300))
        val later = source.state.events.last().id
        assertEquals(listOf("Earlier", "Later"), source.state.events.map { it.title })
        assertTrue(source.saveEvent("Edited", 200, 500, "note", later)); assertEquals(later, source.state.events.last().id)
        assertEquals(source.state, ScheduleSource(store).state)
        source.deleteEvent(later); assertEquals("Earlier", source.state.events.single().title)
        assertFalse(source.saveEvent("Missing", 1, 2, id = later))
        source.close(); assertFalse(source.saveEvent("Closed", 1, 2)); assertTrue(store.closed)
    }
    @Test fun timetableCrudStableTieOrderingAndValidation() {
        val source = ScheduleSource(Store())
        assertFalse(source.saveEntry("", 1, 0, 20)); assertFalse(source.saveEntry("Bad", 0, 0, 20))
        assertFalse(source.saveEntry("Bad", 1, 60, 60)); assertFalse(source.saveEntry("Bad", 1, -1, 20)); assertFalse(source.saveEntry("Bad", 1, 0, 1440))
        assertTrue(source.saveEntry("Later day", 7, 100, 200)); assertTrue(source.saveEntry("B", 1, 100, 200)); assertTrue(source.saveEntry("A", 1, 100, 300))
        assertEquals(listOf("A", "B", "Later day"), source.state.entries.map { it.title })
        val id = source.state.entries.first().id
        assertTrue(source.saveEntry("Updated", 2, 200, 300, "Lab", id))
        assertEquals(id, source.state.entries[1].id); source.deleteEntry(id)
        assertEquals(2, source.state.entries.size)
    }
    @Test fun equalTitleAndTimeUseStableId() {
        val source = ScheduleSource(Store(ScheduleState(
            events = listOf(CalendarEvent("b", "Same", 1, 2), CalendarEvent("a", "Same", 1, 3)),
            entries = listOf(TimetableEntry("b", "Same", 1, 1, 2), TimetableEntry("a", "Same", 1, 1, 3)))))
        assertEquals(listOf("a", "b"), source.state.events.map { it.id }); assertEquals(listOf("a", "b"), source.state.entries.map { it.id })
    }
    @Test fun invalidRowsAreHiddenNotReinterpretedOrOverwritten() {
        val store = Store(ScheduleState(listOf(CalendarEvent("bad", "Bad", 2, 1)), listOf(TimetableEntry("bad", "Bad", 8, 1, 2))))
        val source = ScheduleSource(store)
        assertTrue(source.ready); assertEquals(2, source.state.invalidRecords)
        assertTrue(source.state.events.isEmpty()); assertTrue(source.state.entries.isEmpty())
        source.saveEvent("Good", 1, 2); assertEquals(2, store.data.events.size)
    }
    @Test fun closeIgnoresLateLoadAndRefreshNeverWrites() {
        val store = Store().apply { delayed = true }; val source = ScheduleSource(store)
        var calls = 0; source.subscribe { calls++ }; source.close()
        store.pending!!(Result.success(ScheduleState())); source.refresh()
        assertFalse(source.ready); assertEquals(0, calls); assertEquals(0, store.writes)
    }
    @Test fun eventProjectionUsesLocalDatesAndExclusiveEnd() {
        val start = Instant.parse("2024-02-29T23:00:00Z").toEpochMilli()
        val state = ScheduleState(listOf(CalendarEvent("1", "Night", start, start + 7200000)))
        assertEquals(1, ScheduleProjection.eventsOn(state, LocalDate.parse("2024-02-29"), ZoneOffset.UTC).size)
        assertEquals(1, ScheduleProjection.eventsOn(state, LocalDate.parse("2024-03-01"), ZoneOffset.UTC).size)
        assertTrue(ScheduleProjection.eventsOn(state, LocalDate.parse("2024-02-29"), ZoneId.of("Asia/Shanghai")).isEmpty())
        val midnightEnd = state.copy(events = listOf(state.events[0].copy(end = start + 3600000)))
        assertTrue(ScheduleProjection.eventsOn(midnightEnd, LocalDate.parse("2024-03-01"), ZoneOffset.UTC).isEmpty())
    }
    @Test fun leapMonthLocaleAndYearTransitions() {
        val month = YearMonth.of(2024, 2)
        val uk = ScheduleProjection.monthCells(month, Locale.UK)
        assertEquals(42, uk.size); assertEquals(LocalDate.parse("2024-01-29"), uk.first()); assertEquals(LocalDate.parse("2024-03-10"), uk.last())
        assertEquals(29, uk.count { YearMonth.from(it) == month })
        assertEquals(LocalDate.parse("2024-01-28"), ScheduleProjection.monthCells(month, Locale.US).first())
        val session = ScheduleSession(Clock(Instant.parse("2024-12-31T23:00:00Z")))
        session.moveMonth(1); assertEquals(YearMonth.of(2025, 1), session.month)
        session.moveMonth(-1); assertEquals(YearMonth.of(2024, 12), session.month)
        session.select(LocalDate.parse("2024-02-29")); assertEquals(month, session.month)
    }
    @Test fun sessionSelectionRetainedUntilTodayAcrossTimezoneAndDateChange() {
        val clock = Clock(Instant.parse("2024-12-31T23:00:00Z")); val session = ScheduleSession(clock)
        session.mode = ScheduleMode.TIMETABLE; clock.timezone = ZoneId.of("Asia/Shanghai")
        assertEquals(LocalDate.parse("2024-12-31"), session.selectedDate)
        session.today(clock); assertEquals(LocalDate.parse("2025-01-01"), session.selectedDate)
        assertEquals(YearMonth.of(2025, 1), session.month); assertEquals(ScheduleMode.TIMETABLE, session.mode)
    }
    @Test fun selectedScheduleModeSurvivesNewSession() {
        val store = object : ScheduleModeStore {
            var saved = ScheduleMode.CALENDAR
            var writes = 0
            override fun read() = saved
            override fun write(mode: ScheduleMode) { saved = mode; writes++ }
        }
        val clock = Clock()
        val first = ScheduleSession(clock, store)
        assertEquals(ScheduleMode.CALENDAR, first.mode)
        first.mode = ScheduleMode.TIMETABLE
        first.mode = ScheduleMode.TIMETABLE
        assertEquals(1, store.writes)
        assertEquals(ScheduleMode.TIMETABLE, ScheduleSession(clock, store).mode)
        first.mode = ScheduleMode.CALENDAR
        assertEquals(ScheduleMode.CALENDAR, ScheduleSession(clock, store).mode)
    }
    @Test fun currentNextAndNextWeekWithOverlaps() {
        val clock = Clock()
        val state = ScheduleState(entries = listOf(TimetableEntry("1", "Current", 4, 540, 600), TimetableEntry("2", "Overlap", 4, 560, 620),
            TimetableEntry("3", "Next", 4, 660, 720), TimetableEntry("4", "Friday", 5, 0, 60)))
        var occurrences = ScheduleProjection.occurrences(state, clock)
        assertEquals(listOf("Current", "Overlap", "Next", "Friday"), occurrences.take(4).map { it.entry.title })
        assertTrue(occurrences.first().start <= clock.instant())
        clock.now = Instant.parse("2024-02-29T10:20:00Z")
        assertEquals("Next", ScheduleProjection.occurrences(state, clock).first().entry.title)
        clock.now = Instant.parse("2024-02-29T23:59:00Z")
        assertEquals("Friday", ScheduleProjection.occurrences(state, clock).first().entry.title)
        clock.now = Instant.parse("2024-03-01T01:00:00Z")
        occurrences = ScheduleProjection.occurrences(state, clock)
        assertEquals(LocalDate.parse("2024-03-07"), occurrences.first().date)
    }
    @Test fun sizesUpcomingWhenTodayEmptyAndExactBoundary() {
        val clock = Clock()
        val tomorrow = clock.instant().plusSeconds(86400).toEpochMilli()
        val state = ScheduleState(listOf(CalendarEvent("1", "Tomorrow", tomorrow, tomorrow + 1000)))
        assertEquals("Tomorrow", ScheduleProjection.upcomingEvents(state, clock, 1).single().title)
        assertEquals(1, ScheduleProjection.cardLimit(2, 1)); assertEquals(2, ScheduleProjection.cardLimit(2, 2)); assertEquals(4, ScheduleProjection.cardLimit(3, 2))
        assertEquals(Instant.parse("2024-03-01T00:00:00Z"), ScheduleProjection.nextBoundary(state, clock))
        val classes = state.copy(entries = listOf(TimetableEntry("1", "Now", 4, 540, 600)))
        assertEquals(Instant.parse("2024-02-29T10:00:00Z"), ScheduleProjection.nextBoundary(classes, clock))
    }
    @Test fun dstMidnightAndWeeklyCivilTime() {
        val clock = Clock(Instant.parse("2024-03-10T05:00:00Z"), ZoneId.of("America/New_York"))
        assertEquals(Instant.parse("2024-03-11T04:00:00Z"), ScheduleProjection.nextBoundary(ScheduleState(), clock))
        val state = ScheduleState(entries = listOf(TimetableEntry("1", "Morning", 7, 540, 600)))
        assertEquals(Instant.parse("2024-03-10T13:00:00Z"), ScheduleProjection.occurrences(state, clock).first().start)
    }
    @Test fun timetableCardDistinguishesCurrentNextEmptyAndFutureBySize() {
        val clock = Clock()
        val state = ScheduleState(entries = listOf(TimetableEntry("a", "Current", 4, 540, 600),
            TimetableEntry("b", "Next", 4, 660, 720), TimetableEntry("c", "Tomorrow", 5, 540, 600)))
        assertEquals(ClassStatus.CURRENT, ScheduleProjection.timetableCard(state, clock, 2, 1).status)
        assertEquals(1, ScheduleProjection.timetableCard(state, clock, 2, 1).occurrences.size)
        assertEquals(2, ScheduleProjection.timetableCard(state, clock, 2, 2).occurrences.size)
        clock.now = Instant.parse("2024-02-29T10:00:00Z")
        assertEquals(ClassStatus.NEXT_TODAY, ScheduleProjection.timetableCard(state, clock, 2, 1).status)
        clock.now = Instant.parse("2024-02-29T12:00:00Z")
        val compact = ScheduleProjection.timetableCard(state, clock, 2, 1)
        assertEquals(ClassStatus.NO_MORE_TODAY, compact.status); assertTrue(compact.occurrences.isEmpty())
        assertEquals("Tomorrow", ScheduleProjection.timetableCard(state, clock, 3, 2).occurrences.first().entry.title)
    }
}
