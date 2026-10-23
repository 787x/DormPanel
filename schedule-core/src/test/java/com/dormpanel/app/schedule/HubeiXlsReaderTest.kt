package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test

class HubeiXlsReaderTest {
    private fun bytes(ext: String) =
        javaClass.getResourceAsStream("/schedule/hubei_2026-2027-1.$ext")!!.use { it.readBytes() }

    @Test
    fun equivalentFieldsIdentitiesAndSeasonalTimes() {
        val csv = HubeiCsvTimetableParser.parseBytes(bytes("csv"))
        val xls = HubeiXlsReader.parse(bytes("xls"))
        assertEquals(3, HubeiXlsReader.sheets(bytes("xls")).size)
        assertEquals(csv.series, xls.series)
        assertEquals(csv.warnings, xls.warnings)
        val a = CsvTimetableResolver.resolve(csv, BuiltInProfiles.term2026)
        val b = CsvTimetableResolver.resolve(xls, BuiltInProfiles.term2026)
        assertEquals(13, b.seriesCount)
        assertEquals(131, b.occurrences.size)
        assertEquals(a, b)
        val importer = TimetableImporter()
        val ready = importer.parse(ScheduleArtifact("a.xls", bytes("xls")))
        assertEquals("Hubei University XLS", ready.formatLabel)
        assertNotEquals(
            importer.parse(ScheduleArtifact("a.csv", bytes("csv"))).sha256,
            ready.sha256,
        )
    }

    @Test
    fun rejectsForgedAndTruncatedFiles() {
        for (b in listOf(bytes("csv"), bytes("xls").copyOf(700), ByteArray(1048577))) {
            try {
                HubeiXlsReader.parse(b)
                fail("must reject")
            } catch (expected: ScheduleImportException) {}
        }
    }

    @Test
    fun unknownProfileRetainsXlsParserIdentity() {
        val importer = TimetableImporter()
        val artifact = ScheduleArtifact("a.xls", bytes("xls"))
        val outcome =
            importer.parseOutcome(artifact, BuiltInProfiles.term2026.copy(termKey = "unknown"))
                as TimetableImporter.ParseOutcome.NeedsProfile
        val resolved =
            resolveHubeiCsv(
                outcome.structure,
                BuiltInProfiles.term2026,
                outcome.filename,
                outcome.kind,
                outcome.locator,
                outcome.rawDigest,
            )
        assertEquals(importer.parse(artifact).sha256, resolved.sha256)
        assertEquals("Hubei University XLS", resolved.formatLabel)
    }

    @Test
    fun rejectsFormulaEncryptionAndOutOfBoundsCell() {
        val raw = bytes("xls")
        val cell =
            (0 until raw.size - 4).first {
                raw[it] == 0xfd.toByte() &&
                    raw[it + 1] == 0.toByte() &&
                    raw[it + 2] == 10.toByte() &&
                    raw[it + 3] == 0.toByte()
            }
        for ((offset, value) in listOf(cell to 6, cell to 0x2f, cell + 5 to 1, cell + 6 to 64)) {
            val b = raw.copyOf()
            b[offset] = value.toByte()
            try {
                HubeiXlsReader.parse(b)
                fail("must reject")
            } catch (expected: ScheduleImportException) {}
        }
    }
}
