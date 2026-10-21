package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test

class ScheduleMergeTest {
    private val base = SnapshotFixture.snapshot().state
    private fun merge(l: ScheduleState, r: ScheduleState) = ScheduleMerge.merge(base, l, r)
    private fun valid(s: ScheduleState) = ScheduleSnapshotCodec.validate(SnapshotFixture.snapshot().copy(state = s))
    @Test fun localRemoteAndIdenticalChangesAreIdempotent() {
        val changed = base.copy(entries = base.entries.map { it.copy(location = "A301") })
        listOf(merge(changed, base), merge(base, changed), merge(changed, changed)).forEach {
            assertTrue(it.conflicts.isEmpty()); assertTrue(ScheduleMerge.same(changed, it.state)); valid(it.state)
        }
        assertTrue(ScheduleMerge.same(base, base.copy(entries = base.entries.reversed(), sync = SnapshotIdentity("local", "only"))))
    }
    @Test fun independentRecordsMergeAndDeletionDoesNotReappear() {
        val l = base.copy(events = emptyList(), entries = base.entries.map { it.copy(location = "A301") })
        val r = base.copy(dayAdjustments = emptyList(), classOverrides = base.classOverrides.drop(1))
        val m = merge(l, r)
        assertTrue(m.conflicts.isEmpty()); assertTrue(m.state.events.isEmpty()); assertTrue(m.state.dayAdjustments.isEmpty())
        assertEquals("A301", m.state.entries.single().location)
        assertEquals(r.classOverrides.toSet(), m.state.classOverrides.toSet()); valid(m.state)
    }
    @Test fun differentImportedCoursesInSameSourceMerge() {
        val one = ClassOverride.of("stable-source", "math", null, ClassPatch(location = "A301"))
        val two = ClassOverride.of("stable-source", "english", null, ClassPatch(location = "B201"))
        val m = merge(base.copy(classOverrides = base.classOverrides + one), base.copy(classOverrides = base.classOverrides + two))
        assertTrue(m.conflicts.isEmpty()); assertTrue(m.state.classOverrides.containsAll(listOf(one, two))); valid(m.state)
    }
    @Test fun twoRealImportedCoursesProjectBothOfflineRoomEditsAfterMerge() {
        val first = base.imported.single()
        val second = first.copy(id = "english-instance", seriesId = "english-series", uid = "english", title = "英语",
            originalStart = first.originalStart + 86_400_000, start = first.start + 86_400_000, end = first.end + 86_400_000)
        val b = base.copy(imported = listOf(first, second), dayAdjustments = emptyList(),
            sources = base.sources.map { it.copy(occurrenceCount = 2, lastEnd = second.end) })
        val local = b.copy(classOverrides = b.classOverrides.map { if (it.sourceId == first.sourceId && it.anchorDate == null) it.copy(location = "A301") else it })
        val remote = b.copy(classOverrides = b.classOverrides + ClassOverride.of(second.sourceId, second.seriesId, null, ClassPatch(location = "B201")))
        val result = ScheduleMerge.merge(b, local, remote)
        assertTrue(result.conflicts.isEmpty()); valid(result.state)
        val classes = ScheduleProjection.week(result.state, java.time.LocalDate.parse("2026-10-12"), java.time.ZoneId.of("Asia/Shanghai"))
        assertEquals("A301", classes.single { it.seriesId == first.seriesId }.entry.location)
        assertEquals("B201", classes.single { it.seriesId == second.seriesId }.entry.location)
        assertEquals("english-instance", result.state.imported.single { it.seriesId == second.seriesId }.id)
    }
    @Test fun sameRecordConflictPreservesBothAndResolvesOnlyConflicts() {
        val l = base.copy(entries = base.entries.map { it.copy(location = "A301") }, events = emptyList())
        val r = base.copy(entries = base.entries.map { it.copy(location = "B201") }, dayAdjustments = emptyList())
        assertEquals(1, merge(l, r).conflicts.size)
        ScheduleMerge.Choice.entries.forEach { choice ->
            val m = ScheduleMerge.merge(base, l, r, choice)
            assertEquals(if (choice == ScheduleMerge.Choice.LOCAL) "A301" else "B201", m.state.entries.single().location)
            assertTrue(m.state.events.isEmpty()); assertTrue(m.state.dayAdjustments.isEmpty()); valid(m.state)
        }
    }
    @Test fun nullExplicitEmptyAndResetAreDistinct() {
        val original = base.classOverrides.first()
        val inherited = base.copy(classOverrides = base.classOverrides.map { if (it.key == original.key) it.copy(location = null) else it })
        val reset = base.copy(classOverrides = base.classOverrides.filterNot { it.key == original.key })
        assertEquals(1, merge(inherited, reset).conflicts.size)
        assertFalse(ScheduleMerge.same(base, inherited)); assertFalse(ScheduleMerge.same(inherited, reset))
        assertTrue(merge(reset, reset).conflicts.isEmpty())
        val m = ScheduleMerge.merge(base, inherited, reset, ScheduleMerge.Choice.REMOTE)
        assertTrue(m.state.classOverrides.none { it.key == original.key }); valid(m.state)
    }
    @Test fun sourceDeletionVersusChildEditIsAnAtomicConflict() {
        val deleted = base.copy(sources = emptyList(), imported = emptyList(), classOverrides = base.classOverrides.filter { it.sourceId == null })
        val edited = base.copy(classOverrides = base.classOverrides.map { if (it.sourceId != null) it.copy(note = "offline") else it })
        assertEquals(1, merge(deleted, edited).conflicts.size)
        val local = ScheduleMerge.merge(base, deleted, edited, ScheduleMerge.Choice.LOCAL).state
        assertTrue(local.sources.isEmpty()); assertTrue(local.imported.isEmpty()); assertTrue(local.classOverrides.all { it.sourceId == null }); valid(local)
        val remote = ScheduleMerge.merge(base, deleted, edited, ScheduleMerge.Choice.REMOTE).state
        assertEquals(edited.classOverrides.toSet(), remote.classOverrides.toSet()); valid(remote)
    }
    @Test fun localCourseDeletionVersusOverrideAndDeleteVersusUpdateConflict() {
        val deleted = base.copy(entries = emptyList(), classOverrides = base.classOverrides.filter { it.sourceId != null })
        val edited = base.copy(classOverrides = base.classOverrides.map { if (it.sourceId == null) it.copy(note = "new") else it })
        assertEquals(1, merge(deleted, edited).conflicts.size)
        valid(ScheduleMerge.merge(base, deleted, edited, ScheduleMerge.Choice.LOCAL).state)
        val changed = base.copy(entries = base.entries.map { it.copy(location = "new") })
        assertEquals(1, merge(deleted, changed).conflicts.size)
    }
    @Test fun replacementRetainsStableIdentityAndConcurrentSparseEdits() {
        val replaced = base.copy(sources = base.sources.map { it.copy(sha256 = "b".repeat(64)) }, imported = base.imported.map { it.copy(location = "new source room") })
        val edited = base.copy(classOverrides = base.classOverrides.map { if (it.anchorDate != null) it.copy(note = "offline note") else it })
        val m = merge(replaced, edited)
        assertTrue(m.conflicts.isEmpty()); assertEquals("stable-source", m.state.sources.single().id)
        assertEquals("stable-instance", m.state.imported.single().id)
        assertEquals("stable-series", m.state.imported.single().seriesId)
        assertEquals("offline note", m.state.classOverrides.first { it.anchorDate != null }.note); valid(m.state)
        assertEquals(1, merge(replaced, base.copy(sources = base.sources.map { it.copy(displayName = "other replacement") })).conflicts.size)
    }
    @Test fun identicalReplacementsAtDifferentImportTimesDoNotConflictOrCreateRevisions() {
        val one = base.copy(sources = base.sources.map { it.copy(sha256 = "b".repeat(64), importedAt = 100) })
        val two = one.copy(sources = one.sources.map { it.copy(importedAt = 200) })
        val result = merge(one, two)
        assertTrue(result.conflicts.isEmpty()); assertTrue(ScheduleMerge.same(one, two))
        assertEquals(200L, result.state.sources.single().importedAt)
    }
    @Test fun termProvenanceAndRelatedEditsAreNotSilentlyOverwritten() {
        val term = base.profiles.getValue("custom-term")
        val changed = base.copy(profiles = base.profiles + (term.termKey to term.copy(timezone = "Asia/Tokyo")))
        val edited = base.copy(classOverrides = base.classOverrides.map { if (it.sourceId != null) it.copy(note = "dependent edit") else it })
        assertEquals(1, merge(changed, edited).conflicts.size)
        valid(ScheduleMerge.merge(base, changed, edited, ScheduleMerge.Choice.LOCAL).state)
        valid(ScheduleMerge.merge(base, changed, edited, ScheduleMerge.Choice.REMOTE).state)
        val builtInOverride = base.copy(customProfileKeys = base.customProfileKeys + BuiltInProfiles.term2026.termKey)
        assertFalse(ScheduleMerge.same(base, builtInOverride))
        assertTrue(merge(builtInOverride, changed).conflicts.isEmpty())
    }    @Test fun dateCompositionAndAffectedThisWeekEditRequireOneDecision() {
        val changed = base.copy(dayAdjustments = base.dayAdjustments + DayAdjustment("2026-10-12", "none"))
        val edited = base.copy(classOverrides = base.classOverrides.map { if (it.anchorDate == "2026-10-12") it.copy(note = "concurrent") else it })
        assertEquals(1, merge(changed, edited).conflicts.size)
        val reset = base.copy(classOverrides = base.classOverrides.filterNot { it.anchorDate == "2026-10-12" })
        assertEquals(1, merge(changed, reset).conflicts.size)
        val own = ScheduleMerge.merge(base, changed, edited, ScheduleMerge.Choice.LOCAL).state
        assertTrue(own.dayAdjustments.any { it.date == "2026-10-12" }); assertTrue(own.classOverrides.none { it.note == "concurrent" }); valid(own)
        val other = ScheduleMerge.merge(base, changed, edited, ScheduleMerge.Choice.REMOTE).state
        assertTrue(other.dayAdjustments.none { it.date == "2026-10-12" }); assertTrue(other.classOverrides.any { it.note == "concurrent" }); valid(other)
        val unrelated = base.copy(dayAdjustments = base.dayAdjustments.map { it.copy(mode = "none", weekday = null) })
        assertTrue(merge(unrelated, edited).conflicts.isEmpty())
    }

}
