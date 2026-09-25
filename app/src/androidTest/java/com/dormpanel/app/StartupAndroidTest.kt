package com.dormpanel.app

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.startup.BootReceiver
import com.dormpanel.app.startup.PreferencesStartupStore
import com.dormpanel.app.startup.StartupPolicy
import com.dormpanel.app.startup.SystemHomeNavigator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupAndroidTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun preferenceDefaultsOffAndPersistsAcrossActivityRecreation() {
        val preferences = context.getSharedPreferences("startup_policy", Context.MODE_PRIVATE)
        val previous = preferences.all.toMap()
        try {
            preferences.edit().clear().commit()
            assertFalse(StartupPolicy(PreferencesStartupStore(context)).startAfterBoot)
            val owner = StartupPolicy(PreferencesStartupStore(context))
            assertTrue(owner.setStartAfterBoot(true))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.recreate()
                scenario.onActivity {
                    assertTrue(StartupPolicy(PreferencesStartupStore(it)).startAfterBoot)
                }
            }
            assertTrue(StartupPolicy(PreferencesStartupStore(context)).setStartAfterBoot(false))
            assertFalse(StartupPolicy(PreferencesStartupStore(context)).startAfterBoot)
        } finally {
            val edit = preferences.edit().clear()
            previous.forEach { (key, value) -> if (value is Boolean) edit.putBoolean(key, value) }
            edit.commit()
        }
    }

    @Test fun receiverLaunchesExactlyOnceOnlyForEnabledBoot() {
        val preferences = context.getSharedPreferences("startup_policy", Context.MODE_PRIVATE)
        val previous = preferences.all.toMap()
        val launches = mutableListOf<Intent>()
        val recordingContext = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { launches += intent }
        }
        try {
            preferences.edit().putBoolean("start_after_boot", false).commit()
            val receiver = BootReceiver()
            receiver.onReceive(recordingContext, Intent(Intent.ACTION_BOOT_COMPLETED))
            assertTrue(launches.isEmpty())
            preferences.edit().putBoolean("start_after_boot", true).commit()
            receiver.onReceive(recordingContext, Intent("unrelated.action"))
            receiver.onReceive(recordingContext, null)
            assertTrue(launches.isEmpty())
            receiver.onReceive(recordingContext, Intent(Intent.ACTION_BOOT_COMPLETED))
            assertEquals(1, launches.size)
            val launch = launches.single()
            assertEquals(ComponentName(context, MainActivity::class.java), launch.component)
            assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, launch.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
            assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP, launch.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP)
            assertEquals(Intent.FLAG_ACTIVITY_SINGLE_TOP, launch.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP)
            assertEquals(0, launch.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            receiver.onReceive(recordingContext, Intent(Intent.ACTION_BOOT_COMPLETED))
            assertEquals(2, launches.size) // One launch per broadcast; no persistent worker.
        } finally {
            val edit = preferences.edit().clear()
            previous.forEach { (key, value) -> if (value is Boolean) edit.putBoolean(key, value) }
            edit.commit()
        }
    }

    @Test fun homeIntentIsGenericAndFailuresLeaveCurrentActivityUsable() {
        val intent = SystemHomeNavigator.homeIntent()
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_HOME))
        assertNull(intent.component)
        assertNull(intent.`package`)
        var launches = 0
        val missing = SystemHomeNavigator(context, resolves = { false }, launches = { launches++ })
        assertFalse(missing.openHome())
        assertEquals(0, launches)
        val rejected = SystemHomeNavigator(context, resolves = { true }, launches = { throw SecurityException("test") })
        assertFalse(rejected.openHome())
        var launched: Intent? = null
        assertTrue(SystemHomeNavigator(context, resolves = { true }, launches = { launched = it }).openHome())
        assertEquals(Intent.ACTION_MAIN, launched?.action)
        assertTrue(launched?.hasCategory(Intent.CATEGORY_HOME) == true)
    }

    @Test fun failedBootLaunchReturnsWithoutRetry() {
        val preferences = context.getSharedPreferences("startup_policy", Context.MODE_PRIVATE)
        val previous = preferences.all.toMap()
        var attempts = 0
        val rejectingContext = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) {
                attempts++
                throw SecurityException("test")
            }
        }
        try {
            preferences.edit().putBoolean("start_after_boot", true).commit()
            BootReceiver().onReceive(rejectingContext, Intent(Intent.ACTION_BOOT_COMPLETED))
            assertEquals(1, attempts)
        } finally {
            val edit = preferences.edit().clear()
            previous.forEach { (key, value) -> if (value is Boolean) edit.putBoolean(key, value) }
            edit.commit()
        }
    }

    @Test fun actualManifestRetainsOnlyOrdinaryLauncherAndDisablesTestbedBoot() {
        val manager = context.packageManager
        val homeHandlers = manager.queryIntentActivities(SystemHomeNavigator.homeIntent(), 0)
        assertTrue(homeHandlers.none { it.activityInfo.packageName == context.packageName })
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
        assertTrue(manager.queryIntentActivities(launch, 0).any { it.activityInfo.name == MainActivity::class.java.name })
        val receiver = manager.getReceiverInfo(ComponentName(context, BootReceiver::class.java),
            PackageManager.MATCH_DISABLED_COMPONENTS)
        if (context.packageName.endsWith(".testbed")) assertFalse(receiver.enabled) else assertTrue(receiver.enabled)
    }
}
