package com.dormpanel.app.device

import org.junit.Assert.*
import org.junit.Test

class DeviceControlControllerTest {
    private class MemoryStore : DeviceControlStore {
        var brightness: Int? = 50
        var follow: Boolean? = null
        var awake = true
        var audible = 6
        override fun brightness() = brightness
        override fun keepAwake() = awake
        override fun lastAudibleVolume() = audible
        override fun saveBrightness(value: Int) { brightness = value }
        override fun followSystem() = follow ?: (brightness == null)
        override fun saveFollowSystem(value: Boolean) { follow = value }
        override fun saveKeepAwake(value: Boolean) { awake = value }
        override fun saveLastAudibleVolume(value: Int) { audible = value }
    }
    private class FakeAudio : MediaVolumePort {
        override val max = 20
        override var muteAvailable = true
        var value = 6
        var silent = false
        override fun current() = value
        override fun set(value: Int) { this.value = value }
        override fun muted() = silent
        override fun mute(value: Boolean) { silent = value }
    }
    private class FakeSystem : SystemBrightnessPort {
        var observed = SystemBrightnessState(canWrite = true, automatic = true, raw = 128)
        var brightnessWrites = 0
        var modeWrites = 0
        var failWrites = false
        override fun read() = observed
        override fun setBrightness(raw: Int): Boolean {
            brightnessWrites++
            if (failWrites) return false
            observed = observed.copy(raw = raw)
            return true
        }
        override fun setAutomatic(enabled: Boolean): Boolean {
            modeWrites++
            if (failWrites) return false
            observed = observed.copy(automatic = enabled)
            return true
        }
    }

    @Test fun legacyModeAndRememberedOverrideSurviveRecreation() {
        val store = MemoryStore().apply { brightness = null }
        val control = DeviceControlController(store, FakeAudio())
        assertTrue(control.state.useSystemBrightness)
        control.setBrightness(25)
        assertFalse(control.state.useSystemBrightness)
        control.setFollowSystem(true)
        assertEquals(25, control.state.brightness)
        assertTrue(control.state.useSystemBrightness)
        val recreated = DeviceControlController(store, FakeAudio())
        assertTrue(recreated.state.useSystemBrightness)
        assertEquals(25, recreated.state.brightness)
        recreated.setFollowSystem(false)
        assertEquals(25, recreated.state.brightness)
        control.setBrightness(30, remote = true)
        assertFalse(control.state.useSystemBrightness)
        assertEquals(30, control.state.brightness)
    }

    @Test fun systemWritesRequirePermissionAndManualMode() {
        val system = FakeSystem()
        val control = DeviceControlController(MemoryStore(), FakeAudio(), system)
        val sent = mutableListOf<Int>()
        control.systemBrightnessCommand = sent::add
        assertTrue(control.state.system.automatic)
        assertFalse(control.setSystemBrightness(40))
        assertEquals(0, system.brightnessWrites)
        assertEquals(0, system.modeWrites)
        system.observed = system.observed.copy(canWrite = false)
        control.refreshSystemBrightness()
        assertFalse(control.setSystemAutomatic(false))
        system.observed = system.observed.copy(canWrite = true)
        control.refreshSystemBrightness()
        assertTrue(control.setSystemAutomatic(false))
        assertTrue(control.setSystemBrightness(40))
        assertEquals(1, system.brightnessWrites)
        assertEquals(40, control.state.system.percent)
        assertEquals(listOf(40), sent)
        assertTrue(control.setSystemBrightness(45, remote = true))
        assertEquals(listOf(40), sent)
        assertTrue(control.setSystemAutomatic(true))
        assertFalse(control.setSystemBrightness(50, remote = true))
        system.observed = system.observed.copy(raw = 20)
        control.refreshSystemBrightness()
        assertEquals(2, system.brightnessWrites)
        assertEquals(listOf(40), sent)
        assertEquals(2, system.modeWrites)
    }

    @Test fun automaticCommandEmitsOnlyAfterRealSystemChangeAndNeverEchoesRemote() {
        val system = FakeSystem()
        val control = DeviceControlController(MemoryStore(), FakeAudio(), system)
        val commands = mutableListOf<Boolean>()
        control.systemAutomaticCommand = commands::add
        system.observed = system.observed.copy(canWrite = false)
        control.refreshSystemBrightness()
        assertFalse(control.setSystemAutomatic(false))
        assertTrue(commands.isEmpty())
        system.observed = system.observed.copy(canWrite = true)
        control.refreshSystemBrightness()
        assertTrue(control.setSystemAutomatic(false))
        assertEquals(listOf(false), commands)
        assertEquals(1, system.modeWrites)
        assertTrue(control.setSystemAutomatic(false))
        assertEquals(listOf(false), commands)
        assertTrue(control.setSystemAutomatic(true, remote = true))
        assertEquals(listOf(false), commands)
        assertEquals(2, system.modeWrites)
        assertTrue(control.state.system.automatic)
        system.failWrites = true
        system.observed = system.observed.copy(automatic = false)
        control.refreshSystemBrightness()
        assertFalse(control.setSystemAutomatic(true))
        assertFalse(control.state.system.automatic)
        assertEquals(listOf(false), commands)
    }

    @Test fun followSystemLocalAndRemoteShareOwnerWithoutEcho() {
        val store = MemoryStore()
        val control = DeviceControlController(store, FakeAudio())
        val commands = mutableListOf<Boolean>()
        control.followSystemCommand = commands::add
        control.setFollowSystem(true)
        assertEquals(listOf(true), commands)
        control.setFollowSystem(true)
        assertEquals(listOf(true), commands)
        control.setFollowSystem(false, remote = true)
        assertEquals(listOf(true), commands)
        assertFalse(control.state.useSystemBrightness)
        assertEquals(false, store.follow)
        control.setFollowSystem(true, remote = true)
        assertEquals(listOf(true), commands)
        assertTrue(control.state.useSystemBrightness)
        control.setFollowSystem(false)
        assertEquals(listOf(true, false), commands)
    }

    @Test fun keepAwakeLocalAndRemoteShareOwnerWithoutEcho() {
        val store = MemoryStore()
        val control = DeviceControlController(store, FakeAudio())
        val commands = mutableListOf<Boolean>()
        control.keepAwakeCommand = commands::add
        val observed = mutableListOf<Boolean>()
        control.addListener { observed += it.keepAwake }
        control.setKeepAwake(false)
        assertEquals(listOf(false), commands)
        assertEquals(listOf(true, false), observed)
        control.setKeepAwake(false)
        assertEquals(listOf(false), commands)
        control.setKeepAwake(true, remote = true)
        assertEquals(listOf(false), commands)
        assertEquals(listOf(true, false, true), observed)
        assertTrue(store.awake)
        control.setKeepAwake(true)
        assertEquals(listOf(false), commands)
        control.setKeepAwake(false, remote = true)
        assertEquals(listOf(false), commands)
        assertEquals(listOf(true, false, true, false), observed)
    }

    @Test fun mappingRoundTripsAcrossX08eRange() {
        val state = SystemBrightnessState(minimum = 1, maximum = 255)
        for (percent in 1..100) {
            assertTrue(kotlin.math.abs(state.copy(raw = state.rawFor(percent)).percent - percent) <= 1)
        }
        assertEquals(1, state.rawFor(1))
        assertEquals(255, state.rawFor(100))
    }

    @Test fun blackoutRemoteAndDuplicateEventsDoNotEcho() {
        val control = DeviceControlController(MemoryStore(), FakeAudio())
        val calls = mutableListOf<Boolean>()
        control.blackoutCommand = calls::add
        control.enterBlackout()
        control.enterBlackout(remote = true)
        control.exitBlackout(remote = true)
        assertEquals(listOf(true), calls)
        assertFalse(control.state.blackout)
    }

    @Test fun brightnessAndAwakePersistWhileBlackoutDoesNot() {
        val store = MemoryStore()
        val audio = FakeAudio()
        val first = DeviceControlController(store, audio)
        assertTrue(first.state.keepAwake)
        first.setBrightness(32)
        first.setKeepAwake(false)
        first.enterBlackout()
        assertTrue(first.state.blackout)
        val second = DeviceControlController(store, audio)
        assertEquals(32, second.state.brightness)
        assertFalse(second.state.keepAwake)
        assertFalse(second.state.blackout)
        first.exitBlackout()
        assertEquals(32, first.state.brightness)
    }

    @Test fun mediaUsesActualMaximumAndObservedState() {
        val audio = FakeAudio()
        val control = DeviceControlController(MemoryStore(), audio)
        assertEquals(30, control.state.mediaPercent)
        control.setMediaPercent(45)
        assertEquals(9, audio.value)
        audio.value = 15
        control.refreshAudio()
        assertEquals(75, control.state.mediaPercent)
        control.toggleMute()
        assertTrue(audio.silent)
        assertEquals(0, control.state.mediaPercent)
        control.toggleMute()
        assertFalse(audio.silent)
        assertEquals(15, audio.value)
    }

    @Test fun remoteCommandsNeverEchoAndInvalidValuesAreIgnored() {
        val audio = FakeAudio()
        val control = DeviceControlController(MemoryStore(), audio)
        var sent = 0
        control.brightnessCommand = { sent++ }
        control.volumeCommand = { sent++ }
        control.setBrightness(0)
        control.setMediaPercent(101)
        assertEquals(0, sent)
        control.setBrightness(40, remote = true)
        control.setMediaPercent(50, remote = true)
        assertEquals(0, sent)
        control.setBrightness(60)
        control.setMediaPercent(70)
        assertEquals(2, sent)
    }

    @Test fun unsupportedMuteCannotClaimSuccessOrMoveObservedVolume() {
        val audio = FakeAudio().apply { muteAvailable = false }
        val control = DeviceControlController(MemoryStore(), audio)
        control.toggleMute()
        control.setMediaPercent(0)
        assertFalse(control.state.muteAvailable)
        assertEquals(6, control.state.mediaVolume)
        assertFalse(control.state.muted)
    }
}
