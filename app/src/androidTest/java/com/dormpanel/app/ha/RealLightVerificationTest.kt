package com.dormpanel.app.ha

import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.MainActivity
import com.dormpanel.app.dashboard.DashboardViewModel
import com.dormpanel.app.dashboard.card.*
import com.dormpanel.app.data.LightState
import org.junit.Assert.*
import org.junit.Test

/** Opt-in hardware probe. Never operates household devices in the regression suite.
 * Run only with an explicitly selected realLightName and one realLightStep. Logs no credentials.
 * Result checks use incoming HA state, not service acknowledgements; visual confirmation is separate. */
class RealLightVerificationTest {
    @Test fun selectedLightStep() {
        val args = InstrumentationRegistry.getArguments()
        val name = args.getString("realLightName")
        val step = args.getString("realLightStep")
        if (name.isNullOrBlank() || step !in listOf("inspect", "baseline", "off", "pending", "on", "brightness", "sliders", "restore")) {
            Log.i("PR12RealLight", "NOT RUN: provide an explicit realLightName and realLightStep")
            return
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var backend: DashboardBackend
            var id = ""
            var light: LightState? = null
            fun await(condition: (LightState) -> Boolean) {
                val deadline = System.currentTimeMillis() + 20000
                do {
                    scenario.onActivity {
                        backend = ViewModelProvider(it)[DashboardViewModel::class.java].dataSource
                        light = backend.state.lights.values.singleOrNull { value -> value.name.filterNot(Char::isWhitespace).equals(name.filterNot(Char::isWhitespace), ignoreCase = true) }
                        id = light?.id.orEmpty()
                    }
                    if (light?.let(condition) == true) return
                    Thread.sleep(100)
                } while (System.currentTimeMillis() < deadline)
                scenario.onActivity {
                    Log.i("PR12RealLight", "LOOKUP name=$name status=${backend.ha.status} lights=${backend.state.lights.values.map { value -> value.name to value.availability }}")
                }
                fail("Selected light did not reach expected state: $light")
            }
            await { it.availability == com.dormpanel.app.data.Availability.AVAILABLE }
            Log.i("PR12RealLight", "BEFORE $step $light")
            lateinit var dialog: LightQuickControls
            scenario.onActivity { activity ->
                when (step) {
                    "baseline" -> { backend.setBrightness(id, 65); backend.setColorTemperature(id, 4000) }
                    "off" -> backend.setLightPower(id, false)
                    "pending" -> assertFalse(light!!.isOn)
                    "on" -> backend.setLightPower(id, true)
                    "brightness" -> assertFalse(light!!.isOn)
                    "sliders" -> assertTrue(light!!.isOn)
                    "restore" -> {
                        val brightness = requireNotNull(args.getString("restoreBrightness")?.toIntOrNull())
                        val kelvin = requireNotNull(args.getString("restoreKelvin")?.toIntOrNull())
                        require(brightness in 1..100 && kelvin in light!!.capabilities.colorTemperature!!)
                        backend.setBrightness(id, brightness)
                        backend.setColorTemperature(id, kelvin)
                    }
                }
                dialog = LightQuickControls(activity, backend, ViewModelProvider(activity)[DashboardViewModel::class.java].appearance,
                    id, CardInteractionScope(true) {}) {}
                dialog.show()
            }
            if (step == "sliders") {
                fun drag(description: String, end: Float) {
                    androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription(description))
                        .perform(androidx.test.espresso.action.GeneralSwipeAction(androidx.test.espresso.action.Swipe.SLOW,
                            { view -> val xy = IntArray(2); view.getLocationOnScreen(xy); floatArrayOf(xy[0] + view.width * .3f, xy[1] + view.height * .5f) },
                            { view -> val xy = IntArray(2); view.getLocationOnScreen(xy); floatArrayOf(xy[0] + view.width * end, xy[1] + view.height * .5f) },
                            androidx.test.espresso.action.Press.FINGER))
                }
                drag("Light brightness", .75f)
                Thread.sleep(3000)
                await { it.isOn && it.brightness in 72..79 }
                Log.i("PR12RealLight", "SLIDER brightness settled $light")
                drag("Light color temperature", .4f)
                Thread.sleep(3000)
                await { it.isOn && it.colorTemperature in 3900..4100 }
            }
            if (step == "pending" || step == "brightness") {
                val prefix = if (step == "pending") "Color temperature" else "Brightness"
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(org.hamcrest.Matchers.startsWith(prefix)))
                    .perform(androidx.test.espresso.action.ViewActions.click())
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.widget.EditText::class.java))
                    .perform(androidx.test.espresso.action.ViewActions.replaceText(if (step == "pending") "5100" else "37"), androidx.test.espresso.action.ViewActions.closeSoftKeyboard())
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(android.R.string.ok))
                    .perform(androidx.test.espresso.action.ViewActions.click())
            }
            when (step) {
                "baseline" -> await { it.isOn && it.brightness == 65 && it.colorTemperature in 3900..4100 }
                "off" -> await { !it.isOn }
                "pending" -> { Thread.sleep(1500); await { !it.isOn && it.controlTemperature == 5100 } }
                "on" -> await { it.isOn && it.colorTemperature in 5000..5200 }
                "brightness" -> await { it.isOn && it.brightness == 37 }
                "restore" -> await { it.isOn && it.brightness == args.getString("restoreBrightness")!!.toInt() && kotlin.math.abs((it.colorTemperature ?: 0) - args.getString("restoreKelvin")!!.toInt()) <= 100 }
            }
            Log.i("PR12RealLight", "AFTER $step $light")
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val directory = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "pr12-real").apply { mkdirs() }
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(directory, "$step.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            scenario.onActivity { dialog.dismiss() }
        }
    }
}
