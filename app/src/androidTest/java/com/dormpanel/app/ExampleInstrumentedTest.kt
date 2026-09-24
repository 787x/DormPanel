package com.dormpanel.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun instrumentationTargetsSelectedVariant() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetPackage = instrumentation.targetContext.packageName
        val testPackage = instrumentation.context.packageName
        val expectedTarget = when (testPackage) {
            "com.dormpanel.app.test" -> "com.dormpanel.app"
            "com.dormpanel.app.testbed.test" -> "com.dormpanel.app.testbed"
            else -> throw AssertionError("Unexpected instrumentation package: $testPackage")
        }
        assertEquals(expectedTarget, targetPackage)
    }
}
