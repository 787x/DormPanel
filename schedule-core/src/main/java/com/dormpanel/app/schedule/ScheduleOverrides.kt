package com.dormpanel.app.schedule

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

/** Null patch fields inherit the latest source value; an empty text explicitly clears it. */
data class ClassPatch(
    val title: String? = null, val weekday: Int? = null, val date: String? = null,
    val periodStart: Int? = null, val periodEnd: Int? = null,
    val timingMode: String? = null, val startMinute: Int? = null, val endMinute: Int? = null,
    val location: String? = null, val teacher: String? = null, val note: String? = null
) {
    fun valid(weekly: Boolean): Boolean =
        (title == null || title.isNotBlank() && title.length <= 160) &&
        (weekday == null || weekday in 1..7) && (date == null || runCatching { LocalDate.parse(date) }.isSuccess) &&
        (weekly || weekday == null) && (date == null || !weekly) &&
        (periodStart == null || periodStart in 1..30) && (periodEnd == null || periodEnd in 1..30) &&
        (timingMode == null || timingMode == "linked" || timingMode == "custom") &&
        (startMinute == null || startMinute in 0..1439) && (endMinute == null || endMinute in 1..1439) &&
        listOf(location, teacher, note).all { it == null || it.length <= 500 }
}

@Entity(tableName = "day_adjustments")
data class DayAdjustment(@PrimaryKey val date: String, val mode: String, val weekday: Int? = null, val label: String = "") {
    fun valid() = runCatching { LocalDate.parse(date) }.isSuccess &&
        (mode == "none" || mode == "weekday" && weekday in 1..7 && weekday != LocalDate.parse(date).dayOfWeek.value) &&
        label.length <= 100
}

/** anchorDate is the original target date after day composition, before this-week movement. */
@Entity(tableName = "class_overrides", indices = [Index("sourceId")])
data class ClassOverride(
    @PrimaryKey val key: String, val sourceId: String?, val seriesId: String,
    val anchorDate: String?, val title: String? = null, val weekday: Int? = null,
    val date: String? = null, val periodStart: Int? = null, val periodEnd: Int? = null,
    val timingMode: String? = null, val startMinute: Int? = null, val endMinute: Int? = null,
    val location: String? = null, val teacher: String? = null, val note: String? = null
) {
    fun textCharacters(): Int = listOf(key, sourceId, seriesId, anchorDate, title, date,
        timingMode, location, teacher, note).sumOf { it?.length ?: 0 }
    fun patch() = ClassPatch(title, weekday, date, periodStart, periodEnd, timingMode,
        startMinute, endMinute, location, teacher, note)
    fun valid() = (sourceId?.length ?: 0) <= 160 && seriesId.length in 1..200 &&
        (anchorDate == null || runCatching { LocalDate.parse(anchorDate) }.isSuccess) && patch().valid(anchorDate == null)
    companion object {
        fun key(sourceId: String?, seriesId: String, anchorDate: String?) =
            "${if (sourceId == null) "L" else "I:${sourceId.length}:$sourceId"}:${seriesId.length}:$seriesId:${anchorDate ?: "all"}"
        fun of(sourceId: String?, seriesId: String, anchorDate: String?, patch: ClassPatch) =
            ClassOverride(key(sourceId, seriesId, anchorDate), sourceId, seriesId, anchorDate,
                patch.title, patch.weekday, patch.date, patch.periodStart, patch.periodEnd,
                patch.timingMode, patch.startMinute, patch.endMinute, patch.location, patch.teacher, patch.note)
    }
}
