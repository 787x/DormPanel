package com.dormpanel.app.schedule

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Resolves Hubei CSV week/period structure into concrete occurrence instants using a
 * [TermScheduleProfile]. Phase selection is per occurrence date, never per whole term.
 */
object CsvTimetableResolver {
    /**
     * Resolves [structure] with [profile]. Missing period definitions for a required
     * period number reject the whole resolve — unknown scheduling semantics are never guessed.
     */
    fun resolve(structure: HubeiCsvStructure, profile: TermScheduleProfile, sourceId: String = ""): ParsedTimetable {
        val error = profile.validate()
        if (error != null) throw ScheduleImportException(error)
        importCheck(structure.termKey == profile.termKey,
            "Term profile ${profile.termKey} does not match CSV term ${structure.termKey}.")
        val zone = ZoneId.of(profile.timezone)
        val occurrences = mutableListOf<ImportedClassOccurrence>()
        val seriesIds = mutableSetOf<String>()
        val warnings = structure.warnings.toMutableList()
        warnings += "Classes are shown in the device timezone; their source timezone and exact instants are preserved."
        for (series in structure.series) {
            val seriesKey = listOf(series.title, series.weekday.toString(),
                series.periodNumbers.joinToString(","), series.weekStart.toString(), series.weekEnd.toString())
                .joinToString("\u0000")
            val seriesId = digest(seriesKey.toByteArray())
            importCheck(seriesIds.add(seriesId), "Duplicate course series in CSV.")
            val firstPeriod = series.periodNumbers.first()
            val lastPeriod = series.periodNumbers.last()
            for (week in series.weekStart..series.weekEnd) {
                val date = profile.dateFor(week, series.weekday)
                val phase = profile.phaseOn(date)
                    ?: throw ScheduleImportException("No schedule phase covers $date for ${series.title}.")
                val startPeriod = phase.period(firstPeriod)
                    ?: throw ScheduleImportException("Period $firstPeriod is missing from the ${phase.effectiveFrom} phase.")
                val endPeriod = phase.period(lastPeriod)
                    ?: throw ScheduleImportException("Period $lastPeriod is missing from the ${phase.effectiveFrom} phase.")
                val start = date.atTime(startPeriod.start).atZone(zone).toInstant()
                val end = date.atTime(endPeriod.end).atZone(zone).toInstant()
                importCheck(end > start, "Invalid period times for ${series.title} on $date.")
                val periodLabel = "[${series.periodNumbers.joinToString("-") { n -> "%02d".format(n) }}节]"
                val id = digest("$seriesId\u0000${start.toEpochMilli()}".toByteArray())
                occurrences += ImportedClassOccurrence(id, sourceId, seriesId, null,
                    start.toEpochMilli(), series.title, start.toEpochMilli(), end.toEpochMilli(),
                    profile.timezone, series.location,
                    listOf(periodLabel, series.location, series.description).filter { it.isNotBlank() }.joinToString("\n"),
                    periodLabel)
                importCheck(occurrences.size <= ImportLimits.OCCURRENCES, "CSV exceeds the 10,000 occurrence limit.")
            }
        }
        importCheck(occurrences.isNotEmpty(), "CSV produced no classes.")
        return ParsedTimetable("湖北大学 ${structure.termKey}", structure.series.size,
            occurrences.sortedBy { it.start }, warnings, false)
    }
}

/**
 * Format-aware timetable import. ICS keeps its existing raw-bytes SHA-256 fingerprint.
 * CSV uses an effective import fingerprint that also covers the active term profile.
 */
class TimetableImporter(
    private val ics: IcsScheduleParser = BiweeklyScheduleParser(),
    private val profiles: TermScheduleProfileStore? = null
) {
    /** Parse result for CSV that structurally validly identifies a term without a usable profile. */
    sealed class ParseOutcome {
        data class Ready(val preview: ImportPreview) : ParseOutcome()
        data class NeedsProfile(val structure: HubeiCsvStructure, val filename: String,
            val kind: String, val locator: String?) : ParseOutcome()
    }

    fun parse(artifact: ScheduleArtifact): ImportPreview {
        return when (val outcome = parseOutcome(artifact)) {
            is ParseOutcome.Ready -> outcome.preview
            is ParseOutcome.NeedsProfile -> throw ScheduleImportException(
                "No term schedule profile for ${outcome.structure.termKey}. Configure the term before importing.")
        }
    }

    fun parseOutcome(artifact: ScheduleArtifact, profileOverride: TermScheduleProfile? = null): ParseOutcome {
        val bytes = artifact.bytes()
        val text = runCatching { CsvReader.decode(bytes) }.getOrNull()
        if (text != null && HubeiCsvTimetableParser.looksLike(text)) {
            val structure = HubeiCsvTimetableParser.parse(text)
            val profile = profileOverride
                ?: profiles?.get(structure.termKey)
                ?: BuiltInProfiles.builtIn(structure.termKey)
            if (profile == null || profile.validate() != null || profile.termKey != structure.termKey) {
                return ParseOutcome.NeedsProfile(structure, artifact.filename, artifact.kind, artifact.locator)
            }
            val parsed = CsvTimetableResolver.resolve(structure, profile)
            val fingerprint = csvFingerprint(bytes, profile)
            return ParseOutcome.Ready(ImportPreview(artifact.filename, artifact.kind, artifact.locator,
                fingerprint, parsed,
                formatLabel = "Hubei University CSV",
                termKey = profile.termKey,
                week1Monday = profile.week1Monday.toString(),
                timezone = profile.timezone,
                phaseSummary = profile.phases.sortedBy { it.effectiveFrom }.joinToString("\n") { phase ->
                    val range = phase.periods.minOf { it.start }.toString() + "–" + phase.periods.maxOf { it.end }.toString()
                    "${phase.effectiveFrom} · ${phase.periods.size} periods · $range"
                }))
        }
        // ICS path: existing behavior, raw-bytes SHA-256 only.
        val parsed = ics.parse(artifact)
        return ParseOutcome.Ready(ImportPreview(artifact.filename, artifact.kind, artifact.locator,
            digest(bytes), parsed))
    }

    companion object {
        /**
         * Deterministic effective fingerprint for CSV imports.
         * Includes raw bytes, parser identity, and the full term profile so that a
         * profile change forces replacement even when the remote file is unchanged.
         */
        fun csvFingerprint(rawBytes: ByteArray, profile: TermScheduleProfile): String {
            val out = ByteArrayOutputStream()
            out.write(rawBytes)
            out.write(0)
            out.write(HubeiCsvTimetableParser.PARSER_ID.toByteArray(Charsets.UTF_8))
            out.write(0)
            out.write(profile.fingerprintMaterial().toByteArray(Charsets.UTF_8))
            return digest(out.toByteArray())
        }

        /** Profile-only fingerprint used by WebDAV bindings to detect local profile edits. */
        fun profileFingerprint(profile: TermScheduleProfile): String =
            digest(profile.fingerprintMaterial().toByteArray(Charsets.UTF_8))
    }
}

/**
 * Resolves a [HubeiCsvStructure] with an arbitrary profile (used by the profile-required UI flow
 * and tests) without touching the store.
 */
fun resolveHubeiCsv(structure: HubeiCsvStructure, profile: TermScheduleProfile,
    filename: String, kind: String = "local_document", locator: String? = null,
    rawBytes: ByteArray? = null): ImportPreview {
    val parsed = CsvTimetableResolver.resolve(structure, profile)
    val fingerprint = if (rawBytes != null) TimetableImporter.csvFingerprint(rawBytes, profile)
    else digest(profile.fingerprintMaterial().toByteArray())
    return ImportPreview(filename, kind, locator, fingerprint, parsed,
        formatLabel = "Hubei University CSV",
        termKey = profile.termKey,
        week1Monday = profile.week1Monday.toString(),
        timezone = profile.timezone,
        phaseSummary = profile.phases.sortedBy { it.effectiveFrom }.joinToString("\n") { phase ->
            val range = phase.periods.minOf { it.start }.toString() + "–" + phase.periods.maxOf { it.end }.toString()
            "${phase.effectiveFrom} · ${phase.periods.size} periods · $range"
        })
}
