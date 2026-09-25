package com.dormpanel.app.startup

import org.junit.Assert.*
import org.junit.Test

class StartupPolicyTest {
    private class MemoryStore : StartupStore {
        var enabled = false
        var writes = 0
        override fun startAfterBoot() = enabled
        override fun setStartAfterBoot(enabled: Boolean): Boolean {
            this.enabled = enabled
            writes++
            return true
        }
    }

    @Test fun defaultsOffAndSurvivesOwnerRecreation() {
        val store = MemoryStore()
        assertFalse(StartupPolicy(store).startAfterBoot)
        assertTrue(StartupPolicy(store).setStartAfterBoot(true))
        assertTrue(StartupPolicy(store).startAfterBoot)
        assertTrue(StartupPolicy(store).setStartAfterBoot(false))
        assertFalse(StartupPolicy(store).startAfterBoot)
        assertEquals(2, store.writes)
    }
}
