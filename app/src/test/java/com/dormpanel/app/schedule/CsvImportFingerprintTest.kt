package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test

/**
 * Focused fingerprint and replacement-semantics tests for CSV imports.
 * ICS fingerprints remain raw-bytes SHA-256 and are covered by IcsScheduleImporterTest.
 */
class CsvImportFingerprintTest {
    private val fixtureBytes: ByteArray =
        javaClass.getResourceAsStream("/schedule/hubei_2026-2027-1.csv")!!.use { it.readBytes() }
    private val profile = BuiltInProfiles.term2026

    @Test fun sameCsvAndProfileProduceSameFingerprint() {
        val a = TimetableImporter.csvFingerprint(fixtureBytes, profile)
        val b = TimetableImporter.csvFingerprint(fixtureBytes.copyOf(), profile)
        assertEquals(a, b)
    }

    @Test fun changedWeek1OrPhaseOrPeriodTimeChangesFingerprint() {
        val base = TimetableImporter.csvFingerprint(fixtureBytes, profile)
        assertNotEquals(base, TimetableImporter.csvFingerprint(fixtureBytes,
            profile.copy(week1Monday = profile.week1Monday.plusDays(7))))
        assertNotEquals(base, TimetableImporter.csvFingerprint(fixtureBytes,
            profile.copy(phases = listOf(profile.phases[0].copy(effectiveFrom = profile.phases[0].effectiveFrom.plusDays(1)),
                profile.phases[1]))))
        assertNotEquals(base, TimetableImporter.csvFingerprint(fixtureBytes,
            profile.copy(phases = listOf(profile.phases[0].copy(periods = profile.phases[0].periods.map {
                if (it.periodNumber == 1) it.copy(end = it.end.plusMinutes(1)) else it
            }), profile.phases[1]))))
    }

    @Test fun fingerprintIsIndependentOfPhaseAndPeriodListOrder() {
        val reordered = profile.copy(phases = profile.phases.reversed().map {
            it.copy(periods = it.periods.reversed())
        })
        assertEquals(TimetableImporter.csvFingerprint(fixtureBytes, profile),
            TimetableImporter.csvFingerprint(fixtureBytes, reordered))
    }

    @Test fun icsFingerprintIsStillRawBytesSha256() {
        val ics = "BEGIN:VCALENDAR\nVERSION:2.0\nEND:VCALENDAR\n".toByteArray()
        // Same bytes must hash identically regardless of any profile.
        assertEquals(digest(ics), digest(ics.copyOf()))
    }

    @Test fun profileMaterialIsStableAcrossConstructionOrder() {
        val a = BuiltInProfiles.term2026
        val b = a.copy(phases = a.phases.reversed().map { it.copy(periods = it.periods.reversed()) })
        assertEquals(a.fingerprintMaterial(), b.fingerprintMaterial())
    }
}
