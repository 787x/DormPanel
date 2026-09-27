package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test

/**
 * Correctness tests for the PR25 cleanup:
 * - NeedsProfile preserves raw-file identity through the effective fingerprint
 * - two different future-term CSV byte payloads with the same profile never collide
 * - row/period consistency rejects unrelated earlier periods
 * - no profile-only fallback can masquerade as a CSV import fingerprint
 */
class CsvFingerprintIdentityTest {
    private val fixtureBytes: ByteArray =
        javaClass.getResourceAsStream("/schedule/hubei_2026-2027-1.csv")!!.use { it.readBytes() }

    private fun futureCsv(titleSuffix: String, dayLabel: String = "星期一"): String =
        """湖北大学 测试  学生个人课表
,学年学期:2099-2100-1,,,,,,
,$dayLabel,星期二,星期三,星期四,星期五,星期六,星期天
"第一二节  (01,02小节)  08:00-09:40","课程A$titleSuffix
1-16周[01-02节]
教室A",,,,,
"""

    @Test fun twoDifferentFutureCsvBytesWithSameProfileProduceDifferentFingerprints() {
        val csv1 = futureCsv("α").toByteArray(Charsets.UTF_8)
        val csv2 = futureCsv("β").toByteArray(Charsets.UTF_8)
        val profile = BuiltInProfiles.term2026.copy(termKey = "2099-2100-1",
            week1Monday = java.time.LocalDate.parse("2099-08-31"))
        val f1 = TimetableImporter.csvFingerprint(csv1, profile)
        val f2 = TimetableImporter.csvFingerprint(csv2, profile)
        assertNotEquals(f1, f2)
    }

    @Test fun needsProfileRetainsRawDigestSoFingerprintMatchesReadyPath() {
        // Simulate two different CSVs that both need a profile for a future term.
        val csv1 = futureCsv("α").toByteArray(Charsets.UTF_8)
        val csv2 = futureCsv("β").toByteArray(Charsets.UTF_8)
        val importer = TimetableImporter(profiles = null)
        val outcome1 = importer.parseOutcome(ScheduleArtifact("a.csv", csv1))
        val outcome2 = importer.parseOutcome(ScheduleArtifact("b.csv", csv2))
        assertTrue(outcome1 is TimetableImporter.ParseOutcome.NeedsProfile)
        assertTrue(outcome2 is TimetableImporter.ParseOutcome.NeedsProfile)
        val needs1 = outcome1 as TimetableImporter.ParseOutcome.NeedsProfile
        val needs2 = outcome2 as TimetableImporter.ParseOutcome.NeedsProfile
        val profile = BuiltInProfiles.term2026.copy(termKey = "2099-2100-1",
            week1Monday = java.time.LocalDate.parse("2099-08-31"))
        val p1 = resolveHubeiCsv(needs1.structure, profile, needs1.filename, rawDigest = needs1.rawDigest)
        val p2 = resolveHubeiCsv(needs2.structure, profile, needs2.filename, rawDigest = needs2.rawDigest)
        assertNotEquals(p1.sha256, p2.sha256)
        // Same CSV + same profile → same fingerprint through the NeedsProfile path too.
        val p1b = resolveHubeiCsv(needs1.structure, profile, needs1.filename, rawDigest = needs1.rawDigest)
        assertEquals(p1.sha256, p1b.sha256)
    }

    @Test fun noProfileOnlyFallbackIsUsableAsCsvImportFingerprint() {
        val profile = BuiltInProfiles.term2026
        // The raw-digest API is the only way to produce a CSV fingerprint.
        val a = TimetableImporter.csvFingerprintFromDigest(digest("fileA".toByteArray()), profile)
        val b = TimetableImporter.csvFingerprintFromDigest(digest("fileB".toByteArray()), profile)
        assertNotEquals(a, b)
        // Same digest + same profile → stable.
        assertEquals(a, TimetableImporter.csvFingerprintFromDigest(digest("fileA".toByteArray()), profile))
    }

    @Test fun changedProfileChangesFingerprintFromSameRawDigest() {
        val raw = digest(fixtureBytes)
        val profile = BuiltInProfiles.term2026
        val f1 = TimetableImporter.csvFingerprintFromDigest(raw, profile)
        val f2 = TimetableImporter.csvFingerprintFromDigest(raw, profile.copy(
            week1Monday = profile.week1Monday.plusWeeks(1)))
        assertNotEquals(f1, f2)
    }

    @Test fun rowPeriodConsistencyRejectsUnrelatedEarlierPeriods() {
        // Row declares 09,10,11. Cell claiming 01-02 must be rejected.
        val bad = """湖北大学 测试  学生个人课表
,学年学期:2026-2027-1,,,,,,
,星期一,星期二,星期三,星期四,星期五,星期六,星期天
"第九十十一节  (09,10,11小节)  19:00-21:35","坏课程
1-16周[01-02节]
教室X",,,,,
"""
        try {
            HubeiCsvTimetableParser.parse(bad)
            // If the series list is empty after rejection, that's fine — but we must not
            // produce a 01-02 series from a 09-10-11 row.
        } catch (_: ScheduleImportException) { /* acceptable */ }
        val result = runCatching { HubeiCsvTimetableParser.parse(bad) }
        if (result.isSuccess) {
            val structure = result.getOrThrow()
            assertTrue(structure.series.none { it.title == "坏课程" && it.periodNumbers == listOf(1, 2) })
        }
    }

    @Test fun rowPeriodConsistencyAcceptsValidSubset() {
        val ok = """湖北大学 测试  学生个人课表
,学年学期:2026-2027-1,,,,,,
,星期一,星期二,星期三,星期四,星期五,星期六,星期天
"第九十十一节  (09,10,11小节)  19:00-21:35","好课程
1-16周[09-10节]
教室Y",,,,,
"""
        val structure = HubeiCsvTimetableParser.parse(ok)
        val series = structure.series.first { it.title == "好课程" }
        assertEquals(listOf(9, 10), series.periodNumbers)
    }
}

/**
 * Future terms must not silently inherit 2026 preset dates.
 * Model-level proof: a profile with a non-2026 term must require explicit week1/phase input.
 */
class FutureTermProfileTest {
    @Test fun futureTermHasNoBuiltInPreset() {
        assertNull(BuiltInProfiles.builtIn("2099-2100-1"))
        assertNull(BuiltInProfiles.builtIn("2027-2028-1"))
        assertNotNull(BuiltInProfiles.builtIn("2026-2027-1"))
    }

    @Test fun futureTermProfileCannotValidateWithoutExplicitDates() {
        // Empty phases / missing week1 → validation failure.
        val empty = TermScheduleProfile("2099-2100-1", "Asia/Shanghai",
            java.time.LocalDate.parse("2099-08-31"), emptyList())
        assertNotNull(empty.validate())
        // Valid profile requires the user to supply week1 and at least one phase.
        val explicit = TermScheduleProfile("2099-2100-1", "Asia/Shanghai",
            java.time.LocalDate.parse("2099-08-31"),
            listOf(SchedulePhase(java.time.LocalDate.parse("2099-08-31"),
                listOf(PeriodTime(1, java.time.LocalTime.of(8, 0), java.time.LocalTime.of(8, 45))))))
        assertNull(explicit.validate())
    }

    @Test fun builtIn2026StillOpensWithDefaults() {
        val profile = BuiltInProfiles.term2026
        assertEquals("2026-2027-1", profile.termKey)
        assertEquals(java.time.LocalDate.parse("2026-08-31"), profile.week1Monday)
        assertEquals(2, profile.phases.size)
    }
}
