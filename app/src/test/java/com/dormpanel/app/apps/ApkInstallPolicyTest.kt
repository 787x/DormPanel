package com.dormpanel.app.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class ApkInstallPolicyTest {
    @Test fun installationRequiresCandidatePermissionAndMatchingSignature() {
        assertEquals(ApkInstallGate.NO_APK, ApkInstallPolicy.gate(false, false, null, true))
        assertEquals(ApkInstallGate.WAIT, ApkInstallPolicy.gate(true, true, true, true))
        assertEquals(ApkInstallGate.ALLOW_UNKNOWN_SOURCES, ApkInstallPolicy.gate(true, false, true, false))
        assertEquals(ApkInstallGate.SIGNATURE_CONFLICT, ApkInstallPolicy.gate(true, false, false, true))
        assertEquals(ApkInstallGate.READY, ApkInstallPolicy.gate(true, false, true, true))
    }
    @Test fun permissionRefreshChangesGateWithoutRestaging() {
        val denied = ApkInstallPolicy.gate(true, false, null, false)
        val granted = ApkInstallPolicy.gate(true, false, null, true)
        assertEquals(ApkInstallGate.ALLOW_UNKNOWN_SOURCES, denied)
        assertEquals(ApkInstallGate.READY, granted)
    }
}
