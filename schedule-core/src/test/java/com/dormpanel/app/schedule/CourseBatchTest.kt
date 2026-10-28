package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test

class CourseBatchTest {
    private val entry = TimetableEntry("local", "数学", 1, 540, 600)
    private val state = ScheduleState(entries = listOf(entry))
    private fun edit(id: String = "local") = ClassOverride.of(null, id, null, ClassPatch(title = "新名称"))
    @Test fun localNamesNeverLinkIdentity() {
        val groups = CourseBatch.candidates(state.copy(entries = listOf(entry, entry.copy(id = "other"))))
        assertEquals(2, groups.size)
        assertEquals(listOf("local"), groups[0].series)
    }
    @Test fun missingSecondSeriesRejectsWholeBatch() {
        assertTrue(runCatching { CourseBatch.validate(state, state, listOf(edit(), edit("missing"))) }.isFailure)
        assertTrue(state.classOverrides.isEmpty())
    }
    @Test fun concurrentOverrideRejectsStaleCapture() {
        val current = state.copy(classOverrides = listOf(edit().copy(location = "单周以外")))
        assertTrue(runCatching { CourseBatch.validate(current, state, listOf(edit())) }.isFailure)
    }
    @Test fun validBatchPreservesWeeklyExceptions() {
        val weekly = ClassOverride.of(null, "local", "2026-10-26", ClassPatch(location = "例外教室", teacher = ""))
        val captured = state.copy(classOverrides = listOf(weekly))
        CourseBatch.validate(captured, captured, listOf(edit()))
        assertEquals("", captured.classOverrides.single().teacher)
        assertEquals("例外教室", captured.classOverrides.single().location)
    }
    @Test fun invalidTextRejectsBatch() {
        assertTrue(runCatching { CourseBatch.validate(state, state, listOf(edit().copy(title = "x".repeat(161)))) }.isFailure)
    }
}
