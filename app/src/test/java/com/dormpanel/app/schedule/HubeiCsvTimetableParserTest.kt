package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class HubeiCsvTimetableParserTest {
    private val fixtureBytes: ByteArray =
        javaClass.getResourceAsStream("/schedule/hubei_2026-2027-1.csv")!!.use { it.readBytes() }
    private val fixtureText: String = CsvReader.decode(fixtureBytes)
    private val profile = BuiltInProfiles.term2026

    @Test fun sanitizedFixtureKeepsBomQuotedMultilineAndSignature() {
        assertTrue(fixtureBytes.size >= 3 && fixtureBytes[0] == 0xEF.toByte())
        assertTrue(HubeiCsvTimetableParser.looksLike(fixtureText))
        val structure = HubeiCsvTimetableParser.parseBytes(fixtureBytes)
        assertEquals("2026-2027-1", structure.termKey)
        assertEquals(13, structure.series.size)
    }

    @Test fun fixtureOracleProducesThirteenSeriesAndOneHundredThirtyOneOccurrences() {
        val structure = HubeiCsvTimetableParser.parseBytes(fixtureBytes)
        val parsed = CsvTimetableResolver.resolve(structure, profile)
        assertEquals(13, parsed.seriesCount)
        assertEquals(131, parsed.occurrences.size)
        assertEquals("Asia/Shanghai", parsed.occurrences.map { it.timezone }.distinct().single())
    }

    @Test fun locationIsFirstNormalLineAndTrailingMetadataStaysDescription() {
        val structure = HubeiCsvTimetableParser.parseBytes(fixtureBytes)
        val management = structure.series.first { it.title == "管理学原理" && it.weekday == 5 }
        assertEquals("B307通识多媒体教室", management.location)
        assertEquals("602", management.description)
        val pe = structure.series.first { it.title == "大学体育基础素质课" }
        assertEquals("通识田径场", pe.location)
        assertTrue(pe.description.startsWith("A005"))
    }

    @Test fun militaryTrainingNoteWarnsWithoutBecomingAnOccurrence() {
        val structure = HubeiCsvTimetableParser.parseBytes(fixtureBytes)
        assertTrue(structure.warnings.any { it.contains("军事训练") })
        assertTrue(structure.series.none { it.title.contains("军事训练") })
        val parsed = CsvTimetableResolver.resolve(structure, profile)
        assertTrue(parsed.occurrences.none { it.title.contains("军事训练") })
    }

    @Test fun periodCellEndTimesAreAuthoritativeNotRowRange() {
        val structure = HubeiCsvTimetableParser.parseBytes(fixtureBytes)
        val parsed = CsvTimetableResolver.resolve(structure, profile)
        val zone = ZoneId.of("Asia/Shanghai")
        val early = parsed.occurrences.first { it.title == "大数据导论（公选）" }
        val start = Instant.ofEpochMilli(early.start).atZone(zone)
        val end = Instant.ofEpochMilli(early.end).atZone(zone)
        assertEquals("2026-09-03", start.toLocalDate().toString())
        assertEquals("19:00", start.toLocalTime().toString())
        assertEquals("20:40", end.toLocalTime().toString())
    }

    @Test fun nineToElevenCellEndsAtTwentyOneThirtyFive() {
        val structure = HubeiCsvTimetableParser.parseBytes(fixtureBytes)
        val parsed = CsvTimetableResolver.resolve(structure, profile)
        val zone = ZoneId.of("Asia/Shanghai")
        val longClass = parsed.occurrences.first {
            it.title == "思想道德与法治" && it.periodLabel == "[09-10-11节]"
        }
        val end = Instant.ofEpochMilli(longClass.end).atZone(zone)
        assertEquals("21:35", end.toLocalTime().toString())
    }

    @Test fun midWeekSeasonalCutoverIsPerOccurrenceDate() {
        val structure = HubeiCsvTimetableParser.parseBytes(fixtureBytes)
        val parsed = CsvTimetableResolver.resolve(structure, profile)
        val zone = ZoneId.of("Asia/Shanghai")
        val guide = parsed.occurrences.filter { it.title == "管理科学与工程导论" }
            .sortedBy { it.start }
        val before = guide.first { Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() == LocalDate.parse("2026-10-01") }
        val onCutover = guide.first { Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() == LocalDate.parse("2026-10-08") }
        assertEquals("16:25", Instant.ofEpochMilli(before.start).atZone(zone).toLocalTime().toString())
        assertEquals("18:05", Instant.ofEpochMilli(before.end).atZone(zone).toLocalTime().toString())
        assertEquals("15:55", Instant.ofEpochMilli(onCutover.start).atZone(zone).toLocalTime().toString())
        assertEquals("17:35", Instant.ofEpochMilli(onCutover.end).atZone(zone).toLocalTime().toString())
    }

    @Test fun finalOccurrenceEndsWeek16Friday() {
        val structure = HubeiCsvTimetableParser.parseBytes(fixtureBytes)
        val parsed = CsvTimetableResolver.resolve(structure, profile)
        val zone = ZoneId.of("Asia/Shanghai")
        val last = parsed.occurrences.maxBy { it.start }
        assertEquals("2026-12-18", Instant.ofEpochMilli(last.start).atZone(zone).toLocalDate().toString())
        assertEquals("高等数学B-1", last.title)
    }

    @Test fun quotedMultilineCellsAndCommasSurviveRoundTrip() {
        val rows = CsvReader.parse(fixtureText)
        val courseRow = rows.first { row -> row.any { it.contains("高等数学B-1") && it.contains("4-16周") } }
        val cell = courseRow.first { it.contains("高等数学B-1") }
        assertTrue(cell.contains("\n") || cell.contains("\r"))
        assertTrue(cell.contains("B310通识多媒体教室"))
    }

    @Test fun malformedAndUnsupportedCsvAreRejectedClearly() {
        try { CsvReader.parse("a,\"unterminated"); fail() }
        catch (e: ScheduleImportException) { assertTrue(e.message!!.contains("Malformed", true)) }
        try { HubeiCsvTimetableParser.parse("姓名,班级\n张三,一班"); fail() }
        catch (e: ScheduleImportException) { assertTrue(e.message!!.contains("Hubei") || e.message!!.contains("课表") || e.message!!.contains("学期") || e.message!!.contains("期")) }
        try { HubeiCsvTimetableParser.parse("湖北大学 学生个人课表\n学年学期:2026-2027-1\n星期一,星期二,星期三,星期四,星期五,星期六,星期天\n第1节,,,,,,"); fail() }
        catch (e: ScheduleImportException) { assertTrue(e.message!!.isNotBlank()) }
    }

    @Test fun utf8WithoutBomAndGb18030FallbackDecode() {
        val utf8 = fixtureText.toByteArray(Charsets.UTF_8)
        val structure = HubeiCsvTimetableParser.parse(CsvReader.decode(utf8))
        assertEquals(13, structure.series.size)
        val gb = runCatching {
            val charset = java.nio.charset.Charset.forName("GB18030")
            CsvReader.decode(fixtureText.toByteArray(charset))
        }.getOrNull()
        if (gb != null) assertEquals(fixtureText, gb)
    }

    @Test fun missingPeriodInProfileRejectsResolveInsteadOfGuessing() {
        val structure = HubeiCsvTimetableParser.parseBytes(fixtureBytes)
        val incomplete = profile.copy(phases = listOf(
            profile.phases.first().copy(periods = profile.phases.first().periods.filter { it.periodNumber <= 8 })))
        try { CsvTimetableResolver.resolve(structure, incomplete); fail() }
        catch (e: ScheduleImportException) { assertTrue(e.message!!.contains("Period")) }
    }

    @Test fun unsupportedWeekSyntaxIsSurfacedNotInferred() {
        val text = """湖北大学 测试  学生个人课表
,学年学期:2026-2027-1,,,,,,
,星期一,星期二,星期三,星期四,星期五,星期六,星期天
"第一二节  (01,02小节)  08:00-09:40","奇怪课程
单周[01-02节]
教室",,,,,
"""
        val result = runCatching { HubeiCsvTimetableParser.parse(text) }
        if (result.isSuccess) {
            val structure = result.getOrThrow()
            assertTrue(structure.series.none { it.title == "奇怪课程" })
        } else {
            assertTrue(result.exceptionOrNull() is ScheduleImportException)
        }
    }
}

class TermScheduleProfileTest {
    private val storeContext = null // unit tests exercise model/persistence through in-memory paths

    @Test fun builtInDefaultsMatchSuppliedPreset() {
        val profile = BuiltInProfiles.term2026
        assertEquals("2026-2027-1", profile.termKey)
        assertEquals("Asia/Shanghai", profile.timezone)
        assertEquals(LocalDate.parse("2026-08-31"), profile.week1Monday)
        assertEquals(2, profile.phases.size)
        val summer = profile.phases[0]
        assertEquals(LocalDate.parse("2026-08-31"), summer.effectiveFrom)
        assertEquals(LocalTime.of(8, 0), summer.period(1)!!.start)
        assertEquals(LocalTime.of(8, 45), summer.period(1)!!.end)
        assertEquals(LocalTime.of(19, 0), summer.period(9)!!.start)
        assertEquals(LocalTime.of(20, 40), summer.period(10)!!.end)
        assertEquals(LocalTime.of(21, 35), summer.period(11)!!.end)
        val autumn = profile.phases[1]
        assertEquals(LocalDate.parse("2026-10-08"), autumn.effectiveFrom)
        assertEquals(LocalTime.of(14, 0), autumn.period(5)!!.start)
        assertEquals(LocalTime.of(15, 55), autumn.period(7)!!.start)
        assertEquals(LocalTime.of(17, 35), autumn.period(8)!!.end)
    }

    @Test fun validationRejectsInvalidConfiguration() {
        val base = BuiltInProfiles.term2026
        assertNotNull(base.copy(termKey = " ").validate())
        assertNotNull(base.copy(timezone = "Not/AZone").validate())
        assertNotNull(base.copy(week1Monday = LocalDate.parse("2026-09-01")).validate())
        assertNotNull(base.copy(phases = emptyList()).validate())
        assertNotNull(base.copy(phases = listOf(base.phases[0], base.phases[0])).validate())
        assertNotNull(base.copy(phases = listOf(
            base.phases[0].copy(periods = listOf(PeriodTime(1, LocalTime.of(9, 0), LocalTime.of(8, 0)))))).validate())
        val lateOnly = base.copy(phases = listOf(base.phases[1]))
        assertNotNull(lateOnly.validate())
        assertNull(base.validate())
    }

    @Test fun phaseSelectionUsesOccurrenceDateNotWeek() {
        val profile = BuiltInProfiles.term2026
        assertEquals(LocalDate.parse("2026-08-31"), profile.phaseOn(LocalDate.parse("2026-08-31"))!!.effectiveFrom)
        assertEquals(LocalDate.parse("2026-08-31"), profile.phaseOn(LocalDate.parse("2026-10-07"))!!.effectiveFrom)
        assertEquals(LocalDate.parse("2026-10-08"), profile.phaseOn(LocalDate.parse("2026-10-08"))!!.effectiveFrom)
        assertNull(profile.phaseOn(LocalDate.parse("2026-08-30")))
    }

    @Test fun fingerprintChangesWithProfileAndIgnoresListOrder() {
        val a = BuiltInProfiles.term2026
        val bytes = "sample".toByteArray()
        val f1 = TimetableImporter.csvFingerprint(bytes, a)
        val f2 = TimetableImporter.csvFingerprint(bytes, a)
        assertEquals(f1, f2)
        val reordered = a.copy(phases = a.phases.reversed().map {
            it.copy(periods = it.periods.reversed())
        })
        assertEquals(f1, TimetableImporter.csvFingerprint(bytes, reordered))
        val moved = a.copy(week1Monday = LocalDate.parse("2026-09-07"))
        assertNotEquals(f1, TimetableImporter.csvFingerprint(bytes, moved))
        val retimed = a.copy(phases = listOf(a.phases[0].copy(periods = a.phases[0].periods.map {
            if (it.periodNumber == 1) it.copy(start = LocalTime.of(8, 5)) else it
        }), a.phases[1]))
        assertNotEquals(f1, TimetableImporter.csvFingerprint(bytes, retimed))
    }
}

class CsvTimetableImportTest {
    private val fixtureBytes: ByteArray =
        javaClass.getResourceAsStream("/schedule/hubei_2026-2027-1.csv")!!.use { it.readBytes() }

    @Test fun builtInProfileResolvesFixtureWithoutStore() {
        val importer = TimetableImporter(profiles = null)
        val outcome = importer.parseOutcome(ScheduleArtifact("t.csv", fixtureBytes))
        assertTrue(outcome is TimetableImporter.ParseOutcome.Ready)
        val ready = outcome as TimetableImporter.ParseOutcome.Ready
        assertEquals(131, ready.preview.occurrences.size)
        assertEquals("Hubei University CSV", ready.preview.formatLabel)
        assertEquals("2026-2027-1", ready.preview.termKey)
    }

    @Test fun unknownTermRequiresProfileInsteadOfGuessing() {
        val text = CsvReader.decode(fixtureBytes).replace("2026-2027-1", "2099-2100-1")
        val bytes = text.toByteArray(Charsets.UTF_8)
        val importer = TimetableImporter(profiles = null)
        val outcome = importer.parseOutcome(ScheduleArtifact("future.csv", bytes))
        assertTrue(outcome is TimetableImporter.ParseOutcome.NeedsProfile)
        val needed = outcome as TimetableImporter.ParseOutcome.NeedsProfile
        assertEquals("2099-2100-1", needed.structure.termKey)
    }

    @Test fun effectiveFingerprintTracksProfileChanges() {
        val profile = BuiltInProfiles.term2026
        val f1 = TimetableImporter.csvFingerprint(fixtureBytes, profile)
        val f2 = TimetableImporter.csvFingerprint(fixtureBytes, profile.copy(
            week1Monday = profile.week1Monday.plusWeeks(1)))
        assertNotEquals(f1, f2)
        assertEquals(f1, TimetableImporter.csvFingerprint(fixtureBytes, profile))
    }

    @Test fun icsFingerprintRemainsRawBytesOnly() {
        val ics = """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//test//EN
BEGIN:VEVENT
UID:one
DTSTAMP:20260901T000000Z
SUMMARY:Math
DTSTART;TZID=Asia/Shanghai:20260904T080000
DTEND;TZID=Asia/Shanghai:20260904T095000
END:VEVENT
END:VCALENDAR
""".toByteArray()
        val importer = TimetableImporter()
        val preview = importer.parse(ScheduleArtifact("a.ics", ics))
        assertEquals(digest(ics), preview.sha256)
    }

    @Test fun previewDoesNotWriteConfirmedImportCreatesOccurrences() {
        val importer = TimetableImporter()
        val preview = importer.parse(ScheduleArtifact("t.csv", fixtureBytes))
        assertEquals(131, preview.occurrences.size)
        val store = object : ScheduleStore {
            var committed: ImportCommit? = null
            override fun load(callback: (Result<ScheduleState>) -> Unit) = callback(Result.success(ScheduleState()))
            override fun put(event: CalendarEvent, callback: (Result<Unit>) -> Unit) = callback(Result.success(Unit))
            override fun put(entry: TimetableEntry, callback: (Result<Unit>) -> Unit) = callback(Result.success(Unit))
            override fun deleteEvent(id: String, callback: (Result<Unit>) -> Unit) = callback(Result.success(Unit))
            override fun deleteEntry(id: String, callback: (Result<Unit>) -> Unit) = callback(Result.success(Unit))
            override fun commitImport(source: ImportSource, occurrences: List<ImportedClassOccurrence>,
                replace: Boolean, callback: (Result<ImportCommit>) -> Unit) {
                committed = ImportCommit(source, occurrences, false)
                callback(Result.success(committed!!))
            }
            override fun close() {}
        }
        var result: ImportCommit? = null
        IcsScheduleImporter().commit(preview, store, "湖北大学 2026-2027-1", null, 1L) { result = it.getOrThrow() }
        assertEquals(131, result!!.occurrences.size)
        assertEquals(preview.sha256, result!!.source.sha256)
    }
}
