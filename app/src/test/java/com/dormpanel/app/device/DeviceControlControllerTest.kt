package com.dormpanel.app.device

import org.junit.Assert.*
import org.junit.Test

class DeviceControlControllerTest {
    private class MemoryStore : DeviceControlStore {
        var brightness = 50
        var awake = true
        var audible = 6
        override fun brightness() = brightness
        override fun keepAwake() = awake
        override fun lastAudibleVolume() = audible
        override fun saveBrightness(value: Int) { brightness = value }
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
