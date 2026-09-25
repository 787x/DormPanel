package com.dormpanel.app

import android.content.Context
import android.media.AudioManager
import android.view.WindowManager
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.swipeDown
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.action.ViewActions.swipeRight
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.dormpanel.app.dashboard.DashboardViewModel
import com.dormpanel.app.dashboard.ui.CardPickerDialog
import com.dormpanel.app.ha.HaSettingsDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceControlAndroidTest {
    @get:Rule val isolation = IsolatedDashboardRule()

    @Test fun activityRecreationAwakeAndBlackoutRestore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("device_controls", Context.MODE_PRIVATE)
        val before = prefs.all.toMap()
        assertFalse(android.provider.Settings.System.canWrite(context))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var settings: androidx.appcompat.app.AlertDialog
                scenario.onActivity { activity ->
                    val control = ViewModelProvider(activity)[DashboardViewModel::class.java].deviceControls
                    control.setBrightness(10)
                    assertEquals(.10f, activity.window.attributes.screenBrightness, .001f)
                    settings = HaSettingsDialog(activity, ViewModelProvider(activity)[DashboardViewModel::class.java].dataSource).show()
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    assertEquals(.10f, settings.window!!.attributes.screenBrightness, .001f)
                    settings.dismiss()
                    val control = ViewModelProvider(activity)[DashboardViewModel::class.java].deviceControls
                    val model = ViewModelProvider(activity)[DashboardViewModel::class.java]
                    val picker = CardPickerDialog(activity, model.catalog, model.appearance) { false }
                    picker.show()
                    assertEquals(.10f, picker.window!!.attributes.screenBrightness, .001f)
                    picker.dismiss()
                    control.setKeepAwake(false)
                    assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    control.enterBlackout()
                    assertEquals(.01f, activity.window.attributes.screenBrightness, .001f)
                    assertEquals("Blackout screen. Tap to restore.", activity.findViewById<android.view.ViewGroup>(R.id.page_container)
                        .getChildAt(1).contentDescription)
                    control.exitBlackout()
                    assertEquals(.10f, activity.window.attributes.screenBrightness, .001f)
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val control = ViewModelProvider(activity)[DashboardViewModel::class.java].deviceControls
                    assertEquals(10, control.state.brightness)
                    assertEquals(.10f, activity.window.attributes.screenBrightness, .001f)
                    assertFalse(control.state.keepAwake)
                    assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        } finally {
            val edit = prefs.edit().clear()
            before.forEach { (key, value) -> when (value) {
                is Int -> edit.putInt(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is String -> edit.putString(key, value)
            } }
            edit.commit()
        }
    }

    @Test fun mediaUsesPhysicalMusicStreamAndRestoresIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val original = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val wasMuted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
        val prefs = context.getSharedPreferences("device_controls", Context.MODE_PRIVATE)
        val audibleBefore = prefs.getInt("last_audible_volume", 6)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val control = ViewModelProvider(activity)[DashboardViewModel::class.java].deviceControls
                    assertEquals(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), control.state.mediaMax)
                    control.setMediaPercent(50)
                    assertEquals(audio.getStreamVolume(AudioManager.STREAM_MUSIC), control.state.mediaVolume)
                    assertFalse(control.state.muteAvailable)
                    val beforeAttempt = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    control.toggleMute()
                    assertEquals(beforeAttempt, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                }
            }
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0)
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                if (wasMuted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
            prefs.edit().putInt("last_audible_volume", audibleBefore).commit()
        }
    }

    @Test fun bothDeviceSlidersClaimSwipesAndExternalAudioRefreshes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("device_controls", Context.MODE_PRIVATE)
        val originalBrightness = if (prefs.contains("brightness")) prefs.getInt("brightness", 50) else null
        val originalAudible = if (prefs.contains("last_audible_volume")) prefs.getInt("last_audible_volume", 6) else null
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                onView(withId(R.id.page_container)).perform(swipeDown())
                onView(withText("Control Center")).check(matches(isDisplayed()))
                onView(withContentDescription("Display brightness")).perform(swipeRight())
                onView(withText("Control Center")).check(matches(isDisplayed()))
                onView(withContentDescription("Media volume")).perform(swipeLeft())
                onView(withText("Control Center")).check(matches(isDisplayed()))
                scenario.onActivity {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 8, 0)
                }
                val deadline = System.currentTimeMillis() + 3000
                var refreshed = false
                while (!refreshed && System.currentTimeMillis() < deadline) {
                    scenario.onActivity { refreshed = ViewModelProvider(it)[DashboardViewModel::class.java].deviceControls.state.mediaVolume == 8 }
                    if (!refreshed) Thread.sleep(20)
                }
                assertTrue("Content observer did not refresh external music volume", refreshed)
            }
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            if (originalBrightness == null) prefs.edit().remove("brightness").commit()
            else prefs.edit().putInt("brightness", originalBrightness).commit()
            if (originalAudible == null) prefs.edit().remove("last_audible_volume").commit()
            else prefs.edit().putInt("last_audible_volume", originalAudible).commit()
        }
    }
}
