package com.dormpanel.app.apps

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ApkStagingAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun platformExtractsArchivePackageVersionAndSigningMetadata() {
        val original = File(context.packageCodePath)
        val staging = ApkStaging(context)
        val staged = original.inputStream().use { staging.stage(it, "fixture.apk", "Test fixture", original.length()) }
        try {
            val metadata = staging.validate(staged)
            assertEquals(context.packageName, metadata.packageName)
            assertTrue(metadata.versionCode > 0)
            assertTrue(metadata.signingSha256.length >= 64)
            assertEquals(true, metadata.signatureMatches)
            assertNotNull(metadata.installedVersion)
        } finally { staged.file.delete() }
    }

    @Test fun malformedZipIsRejectedAndRemoved() {
        val staging = ApkStaging(context)
        val staged = "not an APK".byteInputStream().use { staging.stage(it, "broken.apk", "Test") }
        try { staging.validate(staged); fail("Malformed package accepted") }
        catch (_: Exception) { assertFalse(staged.file.exists()) }
    }
}
