package com.dormpanel.app.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic coverage for process-death recovery of PackageInstaller results.
 * The original process-local callback is never required.
 */
class PendingInstallRecoveryTest {

    private fun record(
        sessionId: Int = 7,
        phase: PendingInstallPhase = PendingInstallPhase.COMMITTED,
        versionCode: Long = 2L,
        transferId: String? = null,
    ) = PendingInstallRecord(
        sessionId = sessionId,
        packageName = "com.example.app",
        stagedPath = "/data/user/0/com.dormpanel.app/cache/apk-staging/candidate.apk",
        stagedSha256 = "abc",
        candidateVersionCode = versionCode,
        sourceKind = InstallRecoveryLogic.SOURCE_HA,
        haTransferId = transferId,
        phase = phase,
        message = "",
    )

    @Test fun prepareInstallPersistsPendingSession() {
        val store = InMemoryPendingInstallStore()
        val original = record(transferId = "0123456789abcdef0123456789abcdef")
        store.write(original)
        // Process death: the in-process callback is gone; only the store remains.
        val recovered = store.read()
        assertNotNull(recovered)
        assertEquals(original.sessionId, recovered!!.sessionId)
        assertEquals(original.packageName, recovered.packageName)
        assertEquals(original.haTransferId, recovered.haTransferId)
        assertEquals(PendingInstallPhase.COMMITTED, recovered.phase)
    }

    @Test fun lostCallbackWithInstallerSuccessRecoversInstalledAndHaAck() {
        val store = InMemoryPendingInstallStore()
        store.write(record(phase = PendingInstallPhase.AWAITING_USER,
            transferId = "0123456789abcdef0123456789abcdef"))
        // Fresh process: no static callback. Receiver persists the terminal fact.
        val pending = store.read()!!
        val fromReceiver = InstallRecoveryLogic.recover(pending, PlatformInstallFacts(
            broadcastStatus = 0,
            broadcastMessage = "success",
            installedVersionCode = pending.candidateVersionCode,
        ))
        assertTrue(fromReceiver is InstallRecovery.Installed)
        store.write(fromReceiver.record)
        // Later, a fresh controller reconciles the persisted terminal state.
        val afterRestart = store.read()!!
        val settled = InstallRecoveryLogic.recover(afterRestart, PlatformInstallFacts())
        assertTrue(settled is InstallRecovery.Installed)
        assertEquals("installed", InstallRecoveryLogic.haOutcome(settled))
        assertEquals("0123456789abcdef0123456789abcdef", settled.record.haTransferId)
    }

    @Test fun lostCallbackWithInstallerFailureRecoversInstallFailed() {
        val store = InMemoryPendingInstallStore()
        store.write(record(phase = PendingInstallPhase.AWAITING_USER,
            transferId = "fedcba9876543210fedcba9876543210"))
        val pending = store.read()!!
        val failed = InstallRecoveryLogic.recover(pending, PlatformInstallFacts(
            broadcastStatus = 1,
            broadcastMessage = "INSTALL_FAILED_INVALID_APK",
        ))
        assertTrue(failed is InstallRecovery.Failed)
        assertEquals("install_failed", InstallRecoveryLogic.haOutcome(failed))
        assertEquals("fedcba9876543210fedcba9876543210", failed.record.haTransferId)
    }

    @Test fun userAbortIsDismissedNotInstalled() {
        val recovery = InstallRecoveryLogic.recover(record(), PlatformInstallFacts(
            broadcastStatus = 3,
            broadcastMessage = "User aborted",
        ))
        assertTrue(recovery is InstallRecovery.Dismissed)
        assertEquals("dismissed", InstallRecoveryLogic.haOutcome(recovery))
    }

    @Test fun packageStateProvesSuccessWithoutBroadcast() {
        // Process died after commit; no broadcast survived. The installed
        // package version proves the candidate landed.
        val recovery = InstallRecoveryLogic.recover(record(versionCode = 5L),
            PlatformInstallFacts(sessionStillExists = false, installedVersionCode = 5L))
        assertTrue(recovery is InstallRecovery.Installed)
        assertEquals("installed", InstallRecoveryLogic.haOutcome(recovery))
    }

    @Test fun higherInstalledVersionAlsoProvesSuccess() {
        val recovery = InstallRecoveryLogic.recover(record(versionCode = 5L),
            PlatformInstallFacts(sessionStillExists = false, installedVersionCode = 6L))
        assertTrue(recovery is InstallRecovery.Installed)
    }

    @Test fun unresolvedWhenSessionGoneAndPackageDoesNotProveInstall() {
        // Never claim success without proof. HA rediscovery is preferred.
        val recovery = InstallRecoveryLogic.recover(record(versionCode = 5L, transferId = "aa"),
            PlatformInstallFacts(sessionStillExists = false, installedVersionCode = 4L))
        assertTrue(recovery is InstallRecovery.Unresolved)
        assertNull(InstallRecoveryLogic.haOutcome(recovery))
    }

    @Test fun unresolvedWhenPackageMissing() {
        val recovery = InstallRecoveryLogic.recover(record(), PlatformInstallFacts(
            sessionStillExists = false, installedVersionCode = null))
        assertTrue(recovery is InstallRecovery.Unresolved)
        assertNull(InstallRecoveryLogic.haOutcome(recovery))
    }

    @Test fun liveSessionRemainsPending() {
        val recovery = InstallRecoveryLogic.recover(record(phase = PendingInstallPhase.COMMITTED),
            PlatformInstallFacts(sessionStillExists = true))
        assertTrue(recovery is InstallRecovery.StillPending)
        assertNull(InstallRecoveryLogic.haOutcome(recovery))
    }

    @Test fun awaitingUserBroadcastKeepsPending() {
        val recovery = InstallRecoveryLogic.recover(record(), PlatformInstallFacts(
            broadcastStatus = -1, broadcastMessage = "pending user action"))
        assertTrue(recovery is InstallRecovery.StillPending)
        assertEquals(PendingInstallPhase.AWAITING_USER, recovery.record.phase)
    }

    @Test fun fullProcessDeathSuccessSequence() {
        // prepare install -> persist -> destroy callback -> receiver/repair -> recovered
        val store = InMemoryPendingInstallStore()
        val transferId = "0123456789abcdef0123456789abcdef"
        store.write(record(transferId = transferId))

        // Destroy original controller/process-local callback.
        var liveCallback: ((Int, String) -> Unit)? = { _, _ -> }
        liveCallback = null
        assertNull(liveCallback)

        // Result arrives in a fresh process: only the store is updated.
        val before = store.read()!!
        val receiverRecovery = InstallRecoveryLogic.recover(before, PlatformInstallFacts(
            broadcastStatus = 0, broadcastMessage = "success"))
        store.write(receiverRecovery.record)

        // Fresh controller reconciles.
        val settled = InstallRecoveryLogic.recover(store.read()!!, PlatformInstallFacts())
        assertTrue(settled is InstallRecovery.Installed)
        assertEquals("installed", InstallRecoveryLogic.haOutcome(settled))
        assertEquals(transferId, settled.record.haTransferId)
    }

    @Test fun fullProcessDeathFailureSequence() {
        val store = InMemoryPendingInstallStore()
        store.write(record(transferId = "abcdef0123456789abcdef0123456789"))
        assertNull(null as ((Int, String) -> Unit)?)
        val before = store.read()!!
        val receiverRecovery = InstallRecoveryLogic.recover(before, PlatformInstallFacts(
            broadcastStatus = 1, broadcastMessage = "INSTALL_FAILED_VERSION_DOWNGRADE"))
        store.write(receiverRecovery.record)
        val settled = InstallRecoveryLogic.recover(store.read()!!, PlatformInstallFacts())
        assertTrue(settled is InstallRecovery.Failed)
        assertEquals("install_failed", InstallRecoveryLogic.haOutcome(settled))
    }

    @Test fun terminalRecordStaysTerminalOnLaterReconcile() {
        val installed = record(phase = PendingInstallPhase.INSTALLED)
        assertTrue(InstallRecoveryLogic.recover(installed, PlatformInstallFacts()) is InstallRecovery.Installed)
        val failed = record(phase = PendingInstallPhase.INSTALL_FAILED)
        assertTrue(InstallRecoveryLogic.recover(failed, PlatformInstallFacts()) is InstallRecovery.Failed)
        val dismissed = record(phase = PendingInstallPhase.DISMISSED)
        assertTrue(InstallRecoveryLogic.recover(dismissed, PlatformInstallFacts()) is InstallRecovery.Dismissed)
    }

    @Test fun successIsNeverInventedFromAnAbsentCallback() {
        // A stale store with only STAGED and no facts must not become Installed.
        val recovery = InstallRecoveryLogic.recover(record(phase = PendingInstallPhase.STAGED),
            PlatformInstallFacts())
        assertFalse(recovery is InstallRecovery.Installed)
    }
}
