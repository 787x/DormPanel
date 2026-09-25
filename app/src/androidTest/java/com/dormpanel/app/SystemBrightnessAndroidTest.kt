package com.dormpanel.app

import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.dashboard.DashboardViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemBrightnessAndroidTest {
    @Test fun observedSystemStateAndWindowFollowMode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val prefs = context.getSharedPreferences("device_controls", Context.MODE_PRIVATE)
        val oldBrightness = if (prefs.contains("brightness")) prefs.getInt("brightness", 50) else null
        val oldFollow = if (prefs.contains("follow_system")) prefs.getBoolean("follow_system", true) else null
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val control = ViewModelProvider(activity)[DashboardViewModel::class.java].deviceControls
                control.refreshSystemBrightness()
                assertEquals(Settings.System.canWrite(context), control.state.system.canWrite)
                assertEquals(Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE),
                    if (control.state.system.automatic) 1 else 0)
                assertEquals(Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS), control.state.system.raw)
                val priorMode = control.state.useSystemBrightness
                try {
                    control.setFollowSystem(true)
                    assertEquals(-1f, activity.window.attributes.screenBrightness, .001f)
                    control.setBrightness(20)
                    assertEquals(.20f, activity.window.attributes.screenBrightness, .001f)
                    control.setFollowSystem(true)
                    assertEquals(-1f, activity.window.attributes.screenBrightness, .001f)
                    control.setFollowSystem(false)
                    assertEquals(.20f, activity.window.attributes.screenBrightness, .001f)
                } finally {
                    control.setFollowSystem(priorMode)
                }
            }
        } } finally {
            val edit = prefs.edit()
            if (oldBrightness == null) edit.remove("brightness") else edit.putInt("brightness", oldBrightness)
            if (oldFollow == null) edit.remove("follow_system") else edit.putBoolean("follow_system", oldFollow)
            edit.commit()
        }
    }

    /** Run after granting WRITE_SETTINGS to the isolated testbed. All writes restore in finally. */
    @Test fun grantedManualBrightnessWritesAndRestoresGlobalState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // The complete testbed suite intentionally has no special access. The granted
        // case is invoked separately after using the standard Settings UI to grant it.
        if (!Settings.System.canWrite(context)) return
        val resolver = context.contentResolver
        val initialMode = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
        val initialRaw = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
        val prefs = context.getSharedPreferences("device_controls", Context.MODE_PRIVATE)
        val oldBrightness = if (prefs.contains("brightness")) prefs.getInt("brightness", 50) else null
        val oldFollow = if (prefs.contains("follow_system")) prefs.getBoolean("follow_system", true) else null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val control = ViewModelProvider(activity)[DashboardViewModel::class.java].deviceControls
                    control.refreshSystemBrightness()
                    assertTrue(control.state.system.canWrite)
                    assertTrue(control.setSystemAutomatic(false))
                    control.setFollowSystem(true)
                    for (percent in listOf(100, 50, 10)) {
                        assertTrue(control.setSystemBrightness(percent))
                        assertEquals(control.state.system.rawFor(percent),
                            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS))
                        assertEquals(-1f, activity.window.attributes.screenBrightness, .001f)
                    }
                    control.setBrightness(20)
                    assertEquals(.20f, activity.window.attributes.screenBrightness, .001f)
                    assertTrue(control.setSystemBrightness(60))
                    assertEquals(.20f, activity.window.attributes.screenBrightness, .001f)
                }
                // External Settings changes are delivered by ContentObserver, without polling.
                assertTrue(Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 100))
                val deadline = System.currentTimeMillis() + 3000
                var observed = false
                while (!observed && System.currentTimeMillis() < deadline) {
                    scenario.onActivity { activity ->
                        observed = ViewModelProvider(activity)[DashboardViewModel::class.java]
                            .deviceControls.state.system.raw == 100
                    }
                    if (!observed) Thread.sleep(20)
                }
                assertTrue("External system brightness change was not observed", observed)
            }
        } finally {
            // This test changed the manual value, so restore it before restoring Automatic.
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, initialRaw)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, initialMode)
            val edit = prefs.edit()
            if (oldBrightness == null) edit.remove("brightness") else edit.putInt("brightness", oldBrightness)
            if (oldFollow == null) edit.remove("follow_system") else edit.putBoolean("follow_system", oldFollow)
            edit.commit()
        }
        assertEquals(initialMode, Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE))
        if (initialMode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            assertEquals(initialRaw, Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS))
    }
}
