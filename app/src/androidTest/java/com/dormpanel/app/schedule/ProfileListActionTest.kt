package com.dormpanel.app.schedule

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Distinguishes an edited built-in term from a genuinely custom future term.
 * The profile list must offer Reset only for terms with a real built-in preset.
 */
class ProfileListActionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val namespace = "test_profile_list_${System.nanoTime()}"

    @Before fun setUp() { TermScheduleProfileStore.overrideNamespace = namespace }
    @After fun tearDown() {
        TermScheduleProfileStore.overrideNamespace = null
        instrumentation.targetContext
            .getSharedPreferences(namespace, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun editedBuiltInHasPresetAndCanReset() {
        val store = TermScheduleProfileStore(instrumentation.targetContext)
        store.put(BuiltInProfiles.term2026.copy(week1Monday = LocalDate.parse("2026-09-07")))
        val term = "2026-2027-1"
        // Not built-in (override present) but has a preset.
        assertFalse(store.isBuiltIn(term))
        assertNotNull(BuiltInProfiles.builtIn(term))
        // Reset restores the built-in.
        store.remove(term)
        assertTrue(store.isBuiltIn(term))
    }

    @Test fun customFutureTermHasNoPresetAndDeleteRemovesIt() {
        val store = TermScheduleProfileStore(instrumentation.targetContext)
        val custom = TermScheduleProfile("2099-2100-1", "Asia/Shanghai",
            LocalDate.parse("2099-08-31"),
            listOf(SchedulePhase(LocalDate.parse("2099-08-31"),
                listOf(PeriodTime(1, java.time.LocalTime.of(8, 0), java.time.LocalTime.of(8, 45))))))
        store.put(custom)
        val term = "2099-2100-1"
        assertFalse(store.isBuiltIn(term))
        assertNull(BuiltInProfiles.builtIn(term))
        // Delete removes it; there is no built-in to reset to.
        store.remove(term)
        assertNull(store.get(term))
    }

    @Test fun threeStatesAreDistinct() {
        val store = TermScheduleProfileStore(instrumentation.targetContext)
        // 1. Pure built-in: isBuiltIn == true, hasPreset == true
        assertTrue(store.isBuiltIn("2026-2027-1"))
        assertNotNull(BuiltInProfiles.builtIn("2026-2027-1"))
        // 2. Edited built-in: isBuiltIn == false, hasPreset == true
        store.put(BuiltInProfiles.term2026.copy(week1Monday = LocalDate.parse("2026-09-07")))
        assertFalse(store.isBuiltIn("2026-2027-1"))
        assertNotNull(BuiltInProfiles.builtIn("2026-2027-1"))
        // 3. Custom future: isBuiltIn == false, hasPreset == false
        store.put(TermScheduleProfile("2099-2100-1", "Asia/Shanghai",
            LocalDate.parse("2099-08-31"),
            listOf(SchedulePhase(LocalDate.parse("2099-08-31"),
                listOf(PeriodTime(1, java.time.LocalTime.of(8, 0), java.time.LocalTime.of(8, 45)))))))
        assertFalse(store.isBuiltIn("2099-2100-1"))
        assertNull(BuiltInProfiles.builtIn("2099-2100-1"))
    }
}
