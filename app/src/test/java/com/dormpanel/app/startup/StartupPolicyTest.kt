package com.dormpanel.app.startup

import org.junit.Assert.*
import org.junit.Test

class StartupPolicyTest {
    private class MemoryStore : StartupStore {
        var enabled = false
        var writes = 0
        var failWrites = false
        override fun startAfterBoot() = enabled
        override fun setStartAfterBoot(enabled: Boolean): Boolean {
            writes++
            if (failWrites) return false
            this.enabled = enabled
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

    @Test fun localSuccessfulChangeEmitsCommandAndRemotePersistsWithoutEcho() {
        val store = MemoryStore()
        val policy = StartupPolicy(store)
        val commands = mutableListOf<Boolean>()
        policy.startAfterBootCommand = commands::add
        assertTrue(policy.setStartAfterBoot(true))
        assertEquals(listOf(true), commands)
        assertTrue(policy.startAfterBoot)
        assertTrue(policy.setStartAfterBoot(true))
        assertEquals(listOf(true), commands)
        assertTrue(policy.setStartAfterBoot(false, remote = true))
        assertFalse(policy.startAfterBoot)
        assertEquals(listOf(true), commands)
        assertTrue(policy.setStartAfterBoot(true, remote = true))
        assertTrue(policy.startAfterBoot)
        assertEquals(listOf(true), commands)
        assertTrue(policy.setStartAfterBoot(false))
        assertEquals(listOf(true, false), commands)
    }

    @Test fun failedPreferenceWriteKeepsPreviousStateAndDoesNotEmit() {
        val store = MemoryStore()
        val policy = StartupPolicy(store)
        val commands = mutableListOf<Boolean>()
        val observed = mutableListOf<Boolean>()
        policy.startAfterBootCommand = commands::add
        policy.addListener { observed += it }
        store.failWrites = true
        assertFalse(policy.setStartAfterBoot(true))
        assertFalse(policy.startAfterBoot)
        assertTrue(commands.isEmpty())
        assertEquals(listOf(false), observed)
        store.failWrites = false
        assertTrue(policy.setStartAfterBoot(true, remote = true))
        assertTrue(policy.startAfterBoot)
        assertTrue(commands.isEmpty())
        assertEquals(listOf(false, true), observed)
    }

    @Test fun listenersSeeRemoteChangesForOpenControlCenter() {
        val store = MemoryStore()
        val policy = StartupPolicy(store)
        val observed = mutableListOf<Boolean>()
        val listener: (Boolean) -> Unit = { observed += it }
        policy.addListener(listener)
        assertEquals(listOf(false), observed)
        policy.setStartAfterBoot(true, remote = true)
        assertEquals(listOf(false, true), observed)
        policy.setStartAfterBoot(false)
        assertEquals(listOf(false, true, false), observed)
        policy.removeListener(listener)
        policy.setStartAfterBoot(true, remote = true)
        assertEquals(listOf(false, true, false), observed)
    }
}
