package com.dormpanel.app.schedule

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * One class period's wall-clock bounds on days that fall in a given schedule phase.
 * Times are civil times in the profile timezone; they are not instants.
 */
data class PeriodTime(val periodNumber: Int, val start: LocalTime, val end: LocalTime) {
    fun valid() = periodNumber >= 1 && end > start
}

/**
 * A date-effective schedule. The latest phase whose [effectiveFrom] is on or before an
 * occurrence date supplies that occurrence's period times. Phases may start mid-week.
 */
data class SchedulePhase(val effectiveFrom: LocalDate, val periods: List<PeriodTime>) {
    fun period(number: Int) = periods.firstOrNull { it.periodNumber == number }
    fun valid() = periods.isNotEmpty() && periods.all { it.valid() } &&
        periods.map { it.periodNumber }.distinct().size == periods.size
}

/**
 * Maps week/period structure from a Hubei University personal timetable CSV onto concrete
 * dates and times. Pure data: no I/O, no hardcoded term decisions outside [BuiltInProfiles].
 */
data class TermScheduleProfile(
    val termKey: String,
    val timezone: String,
    val week1Monday: LocalDate,
    val phases: List<SchedulePhase>
) {
    /** Latest phase effective on [date], or null when none has started yet. */
    fun phaseOn(date: LocalDate): SchedulePhase? =
        phases.filter { it.effectiveFrom <= date }.maxByOrNull { it.effectiveFrom }

    /** Week-1-Monday + (week-1) weeks + weekday offset (1=Monday … 7=Sunday). */
    fun dateFor(week: Int, weekday: Int): LocalDate =
        week1Monday.plusWeeks((week - 1).toLong()).plusDays((weekday - 1).toLong())

    /**
     * Effective import fingerprint contribution. Canonical and locale-independent:
     * phases sorted by effectiveFrom, periods sorted by periodNumber.
     */
    fun fingerprintMaterial(): String = buildString {
        append("term=").append(termKey).append('\n')
        append("tz=").append(timezone).append('\n')
        append("w1=").append(week1Monday).append('\n')
        phases.sortedBy { it.effectiveFrom }.forEach { phase ->
            append("phase=").append(phase.effectiveFrom)
            phase.periods.sortedBy { it.periodNumber }.forEach { period ->
                append('|').append(period.periodNumber).append(':')
                    .append(period.start).append('-').append(period.end)
            }
            append('\n')
        }
    }

    fun validate(): String? {
        if (termKey.isBlank()) return "Term key is required."
        if (runCatching { ZoneId.of(timezone) }.isFailure) return "Unknown timezone: $timezone."
        if (week1Monday.dayOfWeek != DayOfWeek.MONDAY) return "Week 1 Monday must fall on a Monday."
        if (phases.isEmpty()) return "At least one schedule phase is required."
        if (phases.map { it.effectiveFrom }.distinct().size != phases.size)
            return "Duplicate phase effective dates."
        phases.forEach { phase ->
            if (!phase.valid()) return "Each phase needs unique positive periods with end after start."
            phase.periods.forEach { period ->
                if (period.start < LocalTime.of(5, 0) || period.end > LocalTime.of(23, 59))
                    return "Period times must fall between 05:00 and 23:59."
            }
        }
        if (phaseOn(week1Monday) == null) return "No schedule phase is effective at term start."
        return null
    }

    companion object {
        fun parsePeriodTime(number: Int, start: String, end: String): PeriodTime? {
            val s = runCatching { LocalTime.parse(start) }.getOrNull() ?: return null
            val e = runCatching { LocalTime.parse(end) }.getOrNull() ?: return null
            return PeriodTime(number, s, e).takeIf { it.valid() }
        }

        fun parsePhase(effectiveFrom: String, periods: List<Triple<Int, String, String>>): SchedulePhase? {
            val date = runCatching { LocalDate.parse(effectiveFrom) }.getOrNull() ?: return null
            val built = periods.map { (n, s, e) -> parsePeriodTime(n, s, e) ?: return null }
            return SchedulePhase(date, built).takeIf { it.valid() }
        }
    }
}

/** Built-in editable preset for 2026-2027-1. User overrides replace it; reset restores it. */
object BuiltInProfiles {
    const val TERM_2026_2027_1 = "2026-2027-1"

    private fun phase(effectiveFrom: String, vararg rows: Triple<Int, String, String>): SchedulePhase =
        SchedulePhase(LocalDate.parse(effectiveFrom), rows.map { (n, s, e) ->
            PeriodTime(n, LocalTime.parse(s), LocalTime.parse(e))
        })

    /**
     * Summer and autumn period times for the 2026-2027-1 Hubei University term.
     * These dates/times are a preset only; future terms must be configured manually.
     */
    val term2026 = TermScheduleProfile(
        termKey = TERM_2026_2027_1,
        timezone = "Asia/Shanghai",
        week1Monday = LocalDate.parse("2026-08-31"),
        phases = listOf(
            phase("2026-08-31",
                Triple(1, "08:00", "08:45"), Triple(2, "08:55", "09:40"),
                Triple(3, "09:55", "10:40"), Triple(4, "10:50", "11:35"),
                Triple(5, "14:30", "15:15"), Triple(6, "15:25", "16:10"),
                Triple(7, "16:25", "17:10"), Triple(8, "17:20", "18:05"),
                Triple(9, "19:00", "19:45"), Triple(10, "19:55", "20:40"),
                Triple(11, "20:50", "21:35")),
            phase("2026-10-08",
                Triple(1, "08:00", "08:45"), Triple(2, "08:55", "09:40"),
                Triple(3, "09:55", "10:40"), Triple(4, "10:50", "11:35"),
                Triple(5, "14:00", "14:45"), Triple(6, "14:55", "15:40"),
                Triple(7, "15:55", "16:40"), Triple(8, "16:50", "17:35"),
                Triple(9, "19:00", "19:45"), Triple(10, "19:55", "20:40"),
                Triple(11, "20:50", "21:35"))
        )
    )

    fun builtIn(termKey: String): TermScheduleProfile? =
        if (termKey == TERM_2026_2027_1) term2026 else null
}

/**
 * Lightweight persisted term-profile store. Non-secret local configuration in SharedPreferences.
 * Built-in 2026-2027-1 is the default until a user override is saved; delete restores the preset.
 */
class TermScheduleProfileStore(context: Context) {
    companion object {
        @Volatile var overrideNamespace: String? = null
        private const val KEY_OVERRIDES = "overrides"
    }

    private val prefs = context.getSharedPreferences(
        overrideNamespace ?: "term_schedule_profiles", Context.MODE_PRIVATE)

    /** Override if present, else built-in for known terms, else null. */
    fun get(termKey: String): TermScheduleProfile? {
        val trimmed = termKey.trim()
        overrides()[trimmed]?.let { return it }
        return BuiltInProfiles.builtIn(trimmed)
    }

    fun isBuiltIn(termKey: String): Boolean =
        BuiltInProfiles.builtIn(termKey.trim()) != null && !overrides().containsKey(termKey.trim())

    fun put(profile: TermScheduleProfile) {
        val problem = profile.validate()
        require(problem == null) { problem ?: "Invalid term profile." }
        val all = overrides()
        all[profile.termKey.trim()] = profile
        save(all)
    }

    /** Removes a user override so the built-in preset applies again (if any). */
    fun remove(termKey: String) {
        val all = overrides()
        if (all.remove(termKey.trim()) != null) save(all)
    }

    fun list(): List<TermScheduleProfile> {
        val merged = linkedMapOf<String, TermScheduleProfile>()
        BuiltInProfiles.builtIn(BuiltInProfiles.TERM_2026_2027_1)?.let {
            merged[it.termKey] = it
        }
        merged.putAll(overrides())
        return merged.values.sortedBy { it.termKey }
    }

    private fun overrides(): MutableMap<String, TermScheduleProfile> {
        val raw = prefs.getString(KEY_OVERRIDES, "{}") ?: "{}"
        val root = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        val result = linkedMapOf<String, TermScheduleProfile>()
        root.keys().forEach { key ->
            val profile = runCatching { fromJson(key, root.getJSONObject(key)) }.getOrNull()
            if (profile != null) result[key] = profile
        }
        return result
    }

    private fun save(overrides: Map<String, TermScheduleProfile>) {
        val root = JSONObject()
        overrides.forEach { (key, profile) -> root.put(key, toJson(profile)) }
        prefs.edit().putString(KEY_OVERRIDES, root.toString()).apply()
    }

    private fun toJson(profile: TermScheduleProfile): JSONObject = JSONObject()
        .put("termKey", profile.termKey)
        .put("timezone", profile.timezone)
        .put("week1Monday", profile.week1Monday.toString())
        .put("phases", JSONArray().apply {
            profile.phases.forEach { phase ->
                put(JSONObject().put("effectiveFrom", phase.effectiveFrom.toString())
                    .put("periods", JSONArray().apply {
                        phase.periods.forEach { period ->
                            put(JSONObject().put("periodNumber", period.periodNumber)
                                .put("start", period.start.toString())
                                .put("end", period.end.toString()))
                        }
                    }))
            }
        })

    private fun fromJson(key: String, json: JSONObject): TermScheduleProfile? {
        val termKey = json.optString("termKey", key)
        val timezone = json.optString("timezone")
        val week1 = runCatching { LocalDate.parse(json.optString("week1Monday")) }.getOrNull() ?: return null
        val phasesJson = json.optJSONArray("phases") ?: return null
        val phases = mutableListOf<SchedulePhase>()
        for (i in 0 until phasesJson.length()) {
            val phaseJson = phasesJson.optJSONObject(i) ?: return null
            val from = runCatching { LocalDate.parse(phaseJson.optString("effectiveFrom")) }.getOrNull() ?: return null
            val periodsJson = phaseJson.optJSONArray("periods") ?: return null
            val periods = mutableListOf<PeriodTime>()
            for (j in 0 until periodsJson.length()) {
                val periodJson = periodsJson.optJSONObject(j) ?: return null
                val number = periodJson.optInt("periodNumber", -1)
                val start = runCatching { LocalTime.parse(periodJson.optString("start")) }.getOrNull() ?: return null
                val end = runCatching { LocalTime.parse(periodJson.optString("end")) }.getOrNull() ?: return null
                periods += PeriodTime(number, start, end)
            }
            phases += SchedulePhase(from, periods)
        }
        val profile = TermScheduleProfile(termKey, timezone, week1, phases)
        return profile.takeIf { it.validate() == null }
    }
}
