package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ClassEditSessionTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val monday = LocalDate.parse("2026-10-12")
    private val local = TimetableEntry("stable-id", "Original", 1, 540, 600, "Room")
    private fun occurrence(state: ScheduleState) = ScheduleProjection.week(state, monday, zone).first()
    private fun patch(session: ClassEditSession, title: String = session.draft.entry.title, location: String = session.draft.entry.location,
        teacher: String = session.draft.teacher, note: String = session.draft.note) = session.patch(title, location, teacher, note,
        session.draft.academicDate, session.draft.academicDate.dayOfWeek, false, null, null,
        session.draft.entry.startMinute, session.draft.entry.endMinute)

    @Test fun untouchedFieldsInheritAndExplicitEmptyClearsOnlyChangedField() {
        val state = ScheduleState(entries = listOf(local))
        val session = ClassEditSession(state, occurrence(state), true, zone)
        assertEquals(ClassPatch(), patch(session))
        assertEquals(ClassPatch(location = ""), patch(session, location = ""))
        val edit = ClassOverride.of(null, local.id, null, patch(session, title = "Edited"))
        val replaced = state.copy(entries = listOf(local.copy(location = "New room")), classOverrides = listOf(edit))
        assertEquals("New room", occurrence(replaced).entry.location)
        assertEquals("Edited", occurrence(replaced).entry.title)
    }
    @Test fun weeklyBaselineIncludesSeriesEditWhileAllWeeksIgnoresWeeklyEdits() {
        val series = ClassOverride.of(null, local.id, null, ClassPatch(title = "Series", teacher = "Teacher"))
        val weekly = ClassOverride.of(null, local.id, monday.toString(), ClassPatch(title = "Weekly"))
        val state = ScheduleState(entries = listOf(local), classOverrides = listOf(series, weekly))
        val week = ClassEditSession(state, occurrence(state), false, zone)
        assertEquals("Series", week.baseline.entry.title)
        assertEquals(ClassPatch(title = "Weekly"), patch(week))
        val all = ClassEditSession(state, occurrence(state), true, zone)
        assertEquals("Series", all.draft.entry.title)
        assertEquals("Original", all.baseline.entry.title)
        assertEquals(ClassPatch(title = "Series", teacher = "Teacher"), patch(all))
    }
    @Test fun profileFormRoundTripsAllPhasesAndRejectsInvalidInput() {
        val profile = BuiltInProfiles.term2026
        val parsed = TermProfileForm.parseProfileForm(profile.termKey, profile.timezone,
            profile.week1Monday.toString(), TermProfileForm.phasesToText(profile))
        assertEquals(profile, parsed)
        assertNotNull(runCatching { TermProfileForm.parseProfileForm("future", "Asia/Shanghai", "2026-10-12", "1 08:00 08:45") }.exceptionOrNull())
    }
}
