package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.*

class IcsScheduleImporterTest {
    private val importer = IcsScheduleImporter()
    private fun event(extra: String = "", start: String = "DTSTART;TZID=Asia/Shanghai:20260904T080000",
        end: String = "DTEND;TZID=Asia/Shanghai:20260904T095000", uid: String = "one") =
        "BEGIN:VEVENT\nUID:$uid\nDTSTAMP:20260901T000000Z\nSUMMARY:数学\n$start\n$end\n$extra\nEND:VEVENT"
    private fun parse(events: String) = importer.parse(ScheduleArtifact("test.ics",
        "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//DormPanel tests//EN\n$events\nEND:VCALENDAR\n".toByteArray()))
    private fun rejected(events: String, part: String) {
        try { parse(events); fail("Expected refusal: $part") }
        catch (e: ScheduleImportException) { assertTrue("${e.message} should mention $part", e.message!!.contains(part, true)) }
    }
    @Test fun realWakeUpBytesRetainSevenIndependentSeriesAndFiniteUtcBoundaries() {
        val bytes = javaClass.getResourceAsStream("/schedule/wakeup.ics")!!.use { it.readBytes() }
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val preview = importer.parse(ScheduleArtifact("课表.ics", bytes))
            assertEquals(7, preview.seriesCount)
            assertEquals(175, preview.occurrences.size)
            assertEquals("Asia/Shanghai", preview.timezones)
            assertTrue(preview.hasAlarms); assertTrue(preview.warnings.any { it.contains("ignored") })
            val groups = preview.occurrences.groupBy { it.seriesId }
            assertEquals(7, groups.size); assertTrue(groups.values.all { it.size == 25 })
            val math = preview.occurrences.filter { it.title == "高等数学B-1" }
            assertEquals(2, math.map { it.seriesId }.distinct().size)
            val friday = math.filter { it.location.startsWith("B310") }
            assertEquals(Instant.parse("2026-09-04T00:00:00Z").toEpochMilli(), friday.first().start)
            assertEquals(Instant.parse("2027-02-19T00:00:00Z").toEpochMilli(), friday.last().start)
            assertEquals("第1 - 2节", friday.first().periodLabel)
            assertEquals("第1 - 2节\nB310通识多媒体教室", friday.first().description)
            assertEquals(Instant.parse("2026-09-02T05:30:00Z").toEpochMilli(), preview.firstStart)
            assertEquals(Instant.parse("2027-02-23T09:30:00Z").toEpochMilli(), preview.lastEnd)
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals(preview.occurrences, importer.parse(ScheduleArtifact("other.ics", bytes)).occurrences)
        } finally { TimeZone.setDefault(original) }
    }
    @Test fun intervalCountExdateRdateAndOriginalIdentity() {
        val p = parse(event("RRULE:FREQ=WEEKLY;COUNT=4;INTERVAL=2\nEXDATE;TZID=Asia/Shanghai:20260918T080000\nRDATE:20260920T000000Z"))
        assertEquals(listOf("2026-09-04", "2026-09-20", "2026-10-02", "2026-10-16"),
            p.occurrences.map { Instant.ofEpochMilli(it.start).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString() })
        assertTrue(p.occurrences.all { it.originalStart == it.start && it.end - it.start == 6600000L })
        assertEquals(p.occurrences.size, p.occurrences.map { it.id }.distinct().size)
    }
    @Test fun untilIsInclusiveInUtcNotSourceWallTime() {
        val exact = parse(event("RRULE:FREQ=WEEKLY;UNTIL=20260911T000000Z"))
        assertEquals(2, exact.occurrences.size)
        assertEquals(1, parse(event("RRULE:FREQ=WEEKLY;UNTIL=20260910T235959Z")).occurrences.size)
    }
    @Test fun calendarNameMetadataSupportsStandardAndLegacyExports() {
        assertEquals("Autumn", parse("NAME:Autumn\n${event()}").calendarName)
        assertEquals("秋季课表", parse("X-WR-CALNAME:秋季课表\n${event()}").calendarName)
    }
    @Test fun foldedUnicodeAndEscapedTextUtcAndRdateDoNotLoseDtstart() {
        val p = parse(event("DESCRIPTION:第1 - 2节\\nHello\\, world\\; \\nfolded\n text\\\\end\nLOCATION:Room\\, A\nRDATE:20260905T000000Z",
            "DTSTART:20260904T000000Z", "DTEND:20260904T015000Z"))
        assertEquals(2, p.occurrences.size)
        assertEquals("UTC", p.timezones)
        assertEquals("Room, A", p.occurrences.first().location)
        assertEquals("第1 - 2节\nHello, world; \nfoldedtext\\end", p.occurrences.first().description)
    }
    @Test fun timezoneDstUsesCivilStartAndRealInstant() {
        val p = parse(event("RRULE:FREQ=WEEKLY;COUNT=3", "DTSTART;TZID=America/New_York:20260301T090000", "DTEND;TZID=America/New_York:20260301T100000"))
        assertEquals(listOf("2026-03-01T14:00:00Z", "2026-03-08T13:00:00Z", "2026-03-15T13:00:00Z"), p.occurrences.map { Instant.ofEpochMilli(it.start).toString() })
    }
    @Test fun sameUidWithDifferentStartIsStillIndependentAndOverridesRefuseSafely() {
        val p = parse(event() + "\n" + event(start = "DTSTART;TZID=Asia/Shanghai:20260905T080000", end = "DTEND;TZID=Asia/Shanghai:20260905T095000"))
        assertEquals(2, p.seriesCount); assertEquals(2, p.occurrences.map { it.seriesId }.distinct().size)
        rejected(event() + "\n" + event(), "duplicate")
        rejected(event("RECURRENCE-ID;TZID=Asia/Shanghai:20260904T080000"), "RECURRENCE-ID")
    }
    @Test fun malformedUnboundedUnknownZonesAndUnsupportedRulesNeverReturnPartialPreview() {
        rejected(event("RRULE:FREQ=WEEKLY"), "unbounded")
        rejected(event("RRULE:FREQ=WEEKLY;COUNT=oops"), "Malformed")
        rejected(event("RRULE:FREQ=WEEKLY;COUNT=2;COUNT=3"), "Duplicate")
        rejected(event("RRULE:FREQ=SECONDLY;COUNT=5"), "DAILY and WEEKLY")
        rejected(event("RRULE:FREQ=WEEKLY;COUNT=3;BYMONTH=2"), "filters")
        rejected(event("RRULE:FREQ=WEEKLY;UNTIL=20261001T080000"), "UTC")
        rejected(event(start = "DTSTART:20260904T080000"), "Floating")
        rejected(event(start = "DTSTART;TZID=Not/AZone:20260904T080000"), "timezone")
        rejected(event(start = "DTSTART:20260230T080000Z"), "Unable")
        rejected(event(end = "DTEND:20260901T000000Z"), "duration")
        rejected(event().replace("SUMMARY:数学", ""), "SUMMARY")
        rejected(event("RDATE;VALUE=PERIOD:20260905T000000Z/20260905T010000Z"), "PERIOD")
    }
    @Test(timeout = 3000) fun hostileInputsHaveFiniteAdmissionLimits() {
        rejected(event("RRULE:FREQ=WEEKLY;COUNT=2147483647"), "limit")
        rejected(event("RRULE:FREQ=WEEKLY;UNTIL=99991231T235959Z"), "span")
        rejected(event("RRULE:FREQ=DAILY;COUNT=3;INTERVAL=7;BYDAY=MO"), "cannot produce")
        rejected((0..256).joinToString("\n") { event(uid = "$it") }, "256")
        try { ScheduleArtifact.read("big.ics", ByteArray(ImportLimits.BYTES + 1).inputStream()); fail() } catch (_: ScheduleImportException) { }
        rejected("BEGIN:VTIMEZONE\nTZID:Bad\nBEGIN:STANDARD\nRRULE:FREQ=SECONDLY\nEND:STANDARD\nEND:VTIMEZONE\n${event()}", "VTIMEZONE")
        rejected(event().removeSuffix("END:VEVENT"), "Malformed")
    }
    @Test fun weekProjectionKeepsManualAndActualDatesSeparateAndCardsFindDistantFuture() {
        val preview = parse(event("RRULE:FREQ=WEEKLY;COUNT=2;INTERVAL=2"))
        val owner = preview.source("source", "Imported", 1)
        val state = ScheduleState(entries = listOf(TimetableEntry("manual", "Manual", 5, 600, 660)),
            sources = listOf(owner), imported = preview.forSource(owner.id))
        val zone = ZoneId.of("Asia/Shanghai")
        assertEquals(2, ScheduleProjection.week(state, LocalDate.parse("2026-08-31"), zone).size)
        assertEquals(1, ScheduleProjection.week(state, LocalDate.parse("2026-09-07"), zone).size)
        assertEquals(2, ScheduleProjection.week(state, LocalDate.parse("2026-09-14"), zone).size)
        var now = Instant.parse("2026-09-04T00:30:00Z")
        val clock = object : ScheduleClock { override fun instant() = now; override fun zone() = zone; override fun locale() = Locale.UK }
        assertEquals(ClassStatus.CURRENT, ScheduleProjection.timetableCard(state, clock, 2, 1).status)
        now = Instant.parse("2026-09-01T00:30:00Z")
        val future = state.copy(entries = emptyList(), imported = state.imported.takeLast(1))
        assertEquals(LocalDate.parse("2026-09-18"), ScheduleProjection.timetableCard(future, clock, 3, 2).occurrences.first().date)
        assertTrue(ScheduleProjection.upcomingEvents(state, clock, 4).isEmpty())
    }
    @Test(timeout = 5000) fun totalOccurrenceLimitIsAppliedAcrossIndependentSeries() {
        rejected((1..3).joinToString("\n") { event("RRULE:FREQ=DAILY;COUNT=3659", uid = "series-$it") }, "limit")
    }
    @Test(timeout = 5000) fun repeatedLargeTextCannotAmplifyIntoAnUnboundedSnapshot() {
        rejected(event("DESCRIPTION:${"a".repeat(16000)}\nRRULE:FREQ=DAILY;COUNT=300"), "storage limit")
    }
    @Test fun dailyBydayAndWeeklyBydayAreExpandedByLibrary() {
        val p = parse(event("RRULE:FREQ=WEEKLY;COUNT=4;BYDAY=MO,FR"))
        assertEquals(listOf("2026-09-04", "2026-09-07", "2026-09-11", "2026-09-14"),
            p.occurrences.map { Instant.ofEpochMilli(it.start).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString() })
        assertEquals(3, parse(event("RRULE:FREQ=DAILY;COUNT=3;INTERVAL=7;BYDAY=FR")).occurrences.size)
    }
    @Test fun ongoingImportedClassAcrossMidnightIsStillCurrent() {
        val preview = parse(event(start = "DTSTART:20260903T233000Z", end = "DTEND:20260904T003000Z"))
        val state = ScheduleState(imported = preview.forSource("source"))
        val clock = object : ScheduleClock {
            override fun instant() = Instant.parse("2026-09-04T00:10:00Z")
            override fun zone() = ZoneOffset.UTC
            override fun locale() = Locale.UK
        }
        assertEquals(ClassStatus.CURRENT, ScheduleProjection.timetableCard(state, clock, 2, 1).status)
        assertEquals(Instant.parse("2026-09-04T00:30:00Z"), ScheduleProjection.nextBoundary(state, clock))
    }
}
