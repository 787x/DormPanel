package com.dormpanel.app.schedule

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime

/**
 * Direct SharedPreferences persistence/readback for TermScheduleProfileStore.
 * JVM tests cannot prove SharedPreferences survives process restarts.
 */
@RunWith(AndroidJUnit4::class)
class TermScheduleProfileStoreAndroidTest {
    private lateinit var context: Context
    private val namespace = "test_term_profiles_${System.nanoTime()}"

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        TermScheduleProfileStore.overrideNamespace = namespace
    }

    @After fun tearDown() {
        TermScheduleProfileStore.overrideNamespace = null
        context.getSharedPreferences("${namespace}_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun builtIn2026IsReturnedWithoutOverride() {
        val store = TermScheduleProfileStore(context)
        val profile = store.get("2026-2027-1")
        assertNotNull(profile)
        assertEquals("2026-2027-1", profile!!.termKey)
        assertEquals("Asia/Shanghai", profile.timezone)
        assertEquals(LocalDate.parse("2026-08-31"), profile.week1Monday)
        assertEquals(2, profile.phases.size)
        assertTrue(store.isBuiltIn("2026-2027-1"))
    }

    @Test fun userOverridePersistsAndReadsBackAfterRestart() {
        val store = TermScheduleProfileStore(context)
        val custom = BuiltInProfiles.term2026.copy(
            week1Monday = LocalDate.parse("2026-09-07"),
            phases = listOf(BuiltInProfiles.term2026.phases[0].copy(
                effectiveFrom = LocalDate.parse("2026-09-07"),
                periods = listOf(PeriodTime(1, LocalTime.of(9, 0), LocalTime.of(9, 45)))
            ))
        )
        store.put(custom)
        assertFalse(store.isBuiltIn("2026-2027-1"))

        // Simulate restart: new store instance over the same SharedPreferences.
        val reopened = TermScheduleProfileStore(context)
        val read = reopened.get("2026-2027-1")
        assertNotNull(read)
        assertEquals(LocalDate.parse("2026-09-07"), read!!.week1Monday)
        assertEquals(LocalTime.of(9, 0), read.phases[0].period(1)!!.start)
    }

    @Test fun resetRestoresBuiltInAfterOverride() {
        val store = TermScheduleProfileStore(context)
        store.put(BuiltInProfiles.term2026.copy(week1Monday = LocalDate.parse("2026-09-07")))
        assertFalse(store.isBuiltIn("2026-2027-1"))
        store.remove("2026-2027-1")
        assertTrue(store.isBuiltIn("2026-2027-1"))
        assertEquals(LocalDate.parse("2026-08-31"), store.get("2026-2027-1")!!.week1Monday)
    }

    @Test fun customFutureTermPersistsAndCanBeDeleted() {
        val store = TermScheduleProfileStore(context)
        val future = TermScheduleProfile("2099-2100-1", "Asia/Shanghai",
            LocalDate.parse("2099-08-31"),
            listOf(SchedulePhase(LocalDate.parse("2099-08-31"),
                listOf(PeriodTime(1, LocalTime.of(8, 0), LocalTime.of(8, 45))))))
        store.put(future)
        val read = TermScheduleProfileStore(context).get("2099-2100-1")
        assertNotNull(read)
        assertEquals("2099-2100-1", read!!.termKey)
        assertNull(BuiltInProfiles.builtIn("2099-2100-1"))
        store.remove("2099-2100-1")
        assertNull(TermScheduleProfileStore(context).get("2099-2100-1"))
    }

    @Test fun invalidProfileIsNotPersisted() {
        val store = TermScheduleProfileStore(context)
        try {
            store.put(BuiltInProfiles.term2026.copy(week1Monday = LocalDate.parse("2026-09-01")))
            fail("Expected invalid profile to be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
