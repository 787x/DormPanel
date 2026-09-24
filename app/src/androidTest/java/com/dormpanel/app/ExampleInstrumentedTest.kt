package com.dormpanel.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun instrumentationTargetsIsolatedTestbed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetPackage = instrumentation.targetContext.packageName
        assertEquals("com.dormpanel.app.testbed", targetPackage)
        assertNotEquals("com.dormpanel.app", targetPackage)
        assertEquals("com.dormpanel.app.testbed.test", instrumentation.context.packageName)
    }
}
