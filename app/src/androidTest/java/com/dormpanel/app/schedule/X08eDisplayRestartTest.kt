package com.dormpanel.app.schedule

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Two instrumentation processes separated by force-stop; UUID namespace owns only disposable UI preferences. */
class X08eDisplayRestartTest {
    @Test fun displayPreferencesSurviveProcessDeath() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val token = args.getString("displayRun") ?: UUID.randomUUID().toString()
        require(UUID.fromString(token).toString() == token)
        val namespace = "test-x08e-display-$token"
        val store = X08eSchedulePreferences(context, namespace)
        val expected = X08eScheduleStyle(X08eScheduleLayout.PERIODS, true, true, true)
        val phase = args.getString("displayPhase")
        try {
            if (phase != "verify") assertTrue(store.write(expected))
            if (phase != "seed") assertEquals(expected, store.read())
        } finally {
            if (phase != "seed") context.getSharedPreferences(namespace, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
