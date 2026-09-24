package com.dormpanel.app.ha

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HaRelayIdentityAndroidTest {
    @Test fun identityPersistsAndSeparateInstallsDiffer() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        fun isolated(suffix: String) = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences("relay_test_${suffix}_$name", mode)
        }
        val firstContext = isolated("first")
        val secondContext = isolated("second")
        try {
            val first = HaRelayIdentityStore(firstContext)
            val restored = HaRelayIdentityStore(firstContext)
            val second = HaRelayIdentityStore(secondContext)
            assertEquals(first.installationId, restored.installationId)
            assertEquals(first.credentials().second, restored.credentials().second)
            assertNotEquals(first.installationId, second.installationId)
            assertNotEquals(first.credentials().second, second.credentials().second)
            assertFalse(first.toString().contains(first.credentials().second))
            assertEquals(43, first.credentials().second.length)
        } finally {
            firstContext.getSharedPreferences("ha_relay_identity", Context.MODE_PRIVATE).edit().clear().commit()
            secondContext.getSharedPreferences("ha_relay_identity", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
