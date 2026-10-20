package com.dormpanel.app.schedule

/** Shared phase form codec; storage remains TermScheduleProfileStore. */
object TermProfileForm {
    fun phasesToText(profile: TermScheduleProfile): String = profile.phases
        .sortedBy { it.effectiveFrom }
        .joinToString("\n\n") { phase ->
            (listOf(phase.effectiveFrom.toString()) + phase.periods.sortedBy { it.periodNumber }
                .map { "${it.periodNumber} ${it.start} ${it.end}" }).joinToString("\n")
        }

    fun parseProfileForm(term: String, timezone: String, week1: String, phasesText: String): TermScheduleProfile {
        val termKey = term.trim()
        importCheck(termKey.isNotBlank(), "Term key is required.")
        val zone = timezone.trim()
        importCheck(runCatching { java.time.ZoneId.of(zone) }.isSuccess, "Unknown timezone: $zone")
        val monday = runCatching { java.time.LocalDate.parse(week1.trim()) }.getOrElse {
            throw ScheduleImportException("Week 1 Monday must be yyyy-MM-dd.")
        }
        val phases = mutableListOf<SchedulePhase>()
        var currentFrom: java.time.LocalDate? = null
        var currentPeriods = mutableListOf<PeriodTime>()
        fun flush() {
            val from = currentFrom ?: return
            phases += SchedulePhase(from, currentPeriods.toList())
            currentFrom = null
            currentPeriods = mutableListOf()
        }
        phasesText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val date = runCatching { java.time.LocalDate.parse(line) }.getOrNull()
            if (date != null && !line.contains(' ')) {
                flush(); currentFrom = date
            } else {
                importCheck(currentFrom != null, "Each period line must follow a yyyy-MM-dd phase date.")
                val parts = line.split(Regex("\\s+"))
                importCheck(parts.size == 3, "Period line must be: number start end")
                val number = parts[0].toIntOrNull() ?: throw ScheduleImportException("Invalid period number.")
                val start = runCatching { java.time.LocalTime.parse(parts[1]) }.getOrElse { throw ScheduleImportException("Invalid start time.") }
                val end = runCatching { java.time.LocalTime.parse(parts[2]) }.getOrElse { throw ScheduleImportException("Invalid end time.") }
                importCheck(end > start, "Period end must be after start.")
                currentPeriods += PeriodTime(number, start, end)
            }
        }
        flush()
        importCheck(phases.isNotEmpty(), "At least one schedule phase is required.")
        return TermScheduleProfile(termKey, zone, monday, phases)
    }

}
