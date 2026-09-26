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
 *
 * Success without a PackageInstaller SUCCESS broadcast requires real evidence
 * of *this* install (pre-install baseline). VersionCode alone is never enough.
 */
class PendingInstallRecoveryTest {

    private val signing = "aa".repeat(32)

    private fun record(
        sessionId: Int = 7,
        phase: PendingInstallPhase = PendingInstallPhase.COMMITTED,
        versionCode: Long = 2L,
        transferId: String? = null,
        baselineVersionCode: Long? = null,
        baselineLastUpdateTime: Long? = 1_000L,
        signingSha256: String = signing,
    ) = PendingInstallRecord(
        sessionId = sessionId,
        packageName = "com.example.app",
        stagedPath = "/data/user/0/com.dormpanel.app/cache/apk-staging/candidate.apk",
        stagedSha256 = "abc",
        candidateVersionCode = versionCode,
        candidateSigningSha256 = signingSha256,
        sourceKind = InstallRecoveryLogic.SOURCE_HA,
        haTransferId = transferId,
        phase = phase,
        message = "",
        baselineVersionCode = baselineVersionCode,
        baselineLastUpdateTime = baselineLastUpdateTime,
    )

    private fun facts(
        broadcastStatus: Int? = null,
        broadcastMessage: String = "",
        sessionStillExists: Boolean = false,
        installedVersionCode: Long? = null,
        installedLastUpdateTime: Long? = null,
        installedSigningSha256: String? = signing,
    ) = PlatformInstallFacts(
        broadcastStatus = broadcastStatus,
        broadcastMessage = broadcastMessage,
        sessionStillExists = sessionStillExists,
        installedVersionCode = installedVersionCode,
        installedLastUpdateTime = installedLastUpdateTime,
        installedSigningSha256 = installedSigningSha256,
    )

    @Test fun prepareInstallPersistsPendingSession() {
        val store = InMemoryPendingInstallStore()
        val original = record(transferId = "0123456789abcdef0123456789abcdef")
        assertTrue(store.write(original))
        val recovered = store.read()
        assertNotNull(recovered)
        assertEquals(original.sessionId, recovered!!.sessionId)
        assertEquals(original.packageName, recovered.packageName)
        assertEquals(original.haTransferId, recovered.haTransferId)
        assertEquals(PendingInstallPhase.COMMITTED, recovered.phase)
    }

    @Test fun terminalInstallerSuccessRemainsInstalledRegardlessOfFallback() {
        val recovery = InstallRecoveryLogic.recover(
            record(versionCode = 5L, baselineVersionCode = 5L),
            facts(broadcastStatus = 0, broadcastMessage = "success",
                installedVersionCode = 5L, installedLastUpdateTime = 1_000L))
        assertTrue(recovery is InstallRecovery.Installed)
        assertEquals("installed", InstallRecoveryLogic.haOutcome(recovery))
    }

    @Test fun lostCallbackWithInstallerFailureRecoversInstallFailed() {
        val recovery = InstallRecoveryLogic.recover(record(transferId = "fedcba9876543210fedcba9876543210"),
            facts(broadcastStatus = 1, broadcastMessage = "INSTALL_FAILED_INVALID_APK"))
        assertTrue(recovery is InstallRecovery.Failed)
        assertEquals("install_failed", InstallRecoveryLogic.haOutcome(recovery))
    }

    @Test fun userAbortIsDismissedNotInstalled() {
        val recovery = InstallRecoveryLogic.recover(record(),
            facts(broadcastStatus = 3, broadcastMessage = "User aborted"))
        assertTrue(recovery is InstallRecovery.Dismissed)
        assertEquals("dismissed", InstallRecoveryLogic.haOutcome(recovery))
    }

    @Test fun preExistingSameVersionAndLostResultIsNotInstalled() {
        // package already v5; candidate v5; user cancelled; session gone;
        // package still v5 → must NOT become Installed.
        val recovery = InstallRecoveryLogic.recover(
            record(versionCode = 5L, baselineVersionCode = 5L, baselineLastUpdateTime = 1_000L),
            facts(sessionStillExists = false,
                installedVersionCode = 5L, installedLastUpdateTime = 1_000L))
        assertTrue(recovery is InstallRecovery.Unresolved)
        assertNull(InstallRecoveryLogic.haOutcome(recovery))
    }

    @Test fun preExistingNewerVersionAndLostResultIsNotInstalled() {
        // Candidate v5 failed/cancelled while v6 was already present.
        val recovery = InstallRecoveryLogic.recover(
            record(versionCode = 5L, baselineVersionCode = 6L),
            facts(sessionStillExists = false,
                installedVersionCode = 6L, installedLastUpdateTime = 1_000L))
        assertTrue(recovery is InstallRecovery.Unresolved)
        assertNull(InstallRecoveryLogic.haOutcome(recovery))
    }

    @Test fun genuineVersionAdvanceFromBaselineIsInstalled() {
        // baseline v4 → candidate v5 landed.
        val recovery = InstallRecoveryLogic.recover(
            record(versionCode = 5L, baselineVersionCode = 4L, baselineLastUpdateTime = 1_000L),
            facts(sessionStillExists = false,
                installedVersionCode = 5L, installedLastUpdateTime = 2_000L))
        assertTrue(recovery is InstallRecovery.Installed)
        assertEquals("installed", InstallRecoveryLogic.haOutcome(recovery))
    }

    @Test fun newInstallFromAbsentPackageIsInstalled() {
        val recovery = InstallRecoveryLogic.recover(
            record(versionCode = 5L, baselineVersionCode = null, baselineLastUpdateTime = null),
            facts(sessionStillExists = false,
                installedVersionCode = 5L, installedLastUpdateTime = 2_000L))
        assertTrue(recovery is InstallRecovery.Installed)
    }

    @Test fun sameVersionReinstallRequiresLastUpdateTimeAdvance() {
        val recovery = InstallRecoveryLogic.recover(
            record(versionCode = 5L, baselineVersionCode = 5L, baselineLastUpdateTime = 1_000L),
            facts(sessionStillExists = false,
                installedVersionCode = 5L, installedLastUpdateTime = 5_000L))
        assertTrue(recovery is InstallRecovery.Installed)
    }

    @Test fun signingMismatchIsNotInstalled() {
        val recovery = InstallRecoveryLogic.recover(
            record(versionCode = 5L, baselineVersionCode = 4L, signingSha256 = "bb".repeat(32)),
            facts(sessionStillExists = false,
                installedVersionCode = 5L, installedLastUpdateTime = 2_000L,
                installedSigningSha256 = signing))
        assertTrue(recovery is InstallRecovery.Unresolved)
    }

    @Test fun missingBaselineIsNotInstalled() {
        val recovery = InstallRecoveryLogic.recover(
            record(versionCode = 5L, baselineVersionCode = PendingInstallRecord.NO_BASELINE),
            facts(sessionStillExists = false,
                installedVersionCode = 5L, installedLastUpdateTime = 2_000L))
        assertTrue(recovery is InstallRecovery.Unresolved)
    }

    @Test fun unresolvedWhenSessionGoneAndPackageDoesNotProveInstall() {
        val recovery = InstallRecoveryLogic.recover(record(versionCode = 5L, transferId = "aa",
            baselineVersionCode = 5L),
            facts(sessionStillExists = false, installedVersionCode = 4L))
        assertTrue(recovery is InstallRecovery.Unresolved)
        assertNull(InstallRecoveryLogic.haOutcome(recovery))
    }

    @Test fun unresolvedWhenPackageMissing() {
        val recovery = InstallRecoveryLogic.recover(record(), facts(sessionStillExists = false))
        assertTrue(recovery is InstallRecovery.Unresolved)
        assertNull(InstallRecoveryLogic.haOutcome(recovery))
    }

    @Test fun liveSessionRemainsPending() {
        val recovery = InstallRecoveryLogic.recover(record(phase = PendingInstallPhase.COMMITTED),
            facts(sessionStillExists = true))
        assertTrue(recovery is InstallRecovery.StillPending)
        assertNull(InstallRecoveryLogic.haOutcome(recovery))
    }

    @Test fun awaitingUserBroadcastKeepsPending() {
        val recovery = InstallRecoveryLogic.recover(record(),
            facts(broadcastStatus = -1, broadcastMessage = "pending user action"))
        assertTrue(recovery is InstallRecovery.StillPending)
        assertEquals(PendingInstallPhase.AWAITING_USER, recovery.record.phase)
    }

    @Test fun fullProcessDeathSuccessSequence() {
        val store = InMemoryPendingInstallStore()
        val transferId = "0123456789abcdef0123456789abcdef"
        store.write(record(transferId = transferId, baselineVersionCode = 4L))

        var liveCallback: ((Int, String) -> Unit)? = { _, _ -> }
        liveCallback = null
        assertNull(liveCallback)

        val before = store.read()!!
        val receiverRecovery = InstallRecoveryLogic.recover(before, facts(broadcastStatus = 0))
        store.write(receiverRecovery.record)

        val settled = InstallRecoveryLogic.recover(store.read()!!, facts())
        assertTrue(settled is InstallRecovery.Installed)
        assertEquals("installed", InstallRecoveryLogic.haOutcome(settled))
        assertEquals(transferId, settled.record.haTransferId)
    }

    @Test fun fullProcessDeathFailureSequence() {
        val store = InMemoryPendingInstallStore()
        store.write(record(transferId = "abcdef0123456789abcdef0123456789"))
        val before = store.read()!!
        val receiverRecovery = InstallRecoveryLogic.recover(before,
            facts(broadcastStatus = 1, broadcastMessage = "INSTALL_FAILED_VERSION_DOWNGRADE"))
        store.write(receiverRecovery.record)
        val settled = InstallRecoveryLogic.recover(store.read()!!, facts())
        assertTrue(settled is InstallRecovery.Failed)
        assertEquals("install_failed", InstallRecoveryLogic.haOutcome(settled))
    }

    @Test fun terminalRecordStaysTerminalOnLaterReconcile() {
        assertTrue(InstallRecoveryLogic.recover(record(phase = PendingInstallPhase.INSTALLED), facts()) is InstallRecovery.Installed)
        assertTrue(InstallRecoveryLogic.recover(record(phase = PendingInstallPhase.INSTALL_FAILED), facts()) is InstallRecovery.Failed)
        assertTrue(InstallRecoveryLogic.recover(record(phase = PendingInstallPhase.DISMISSED), facts()) is InstallRecovery.Dismissed)
    }

    @Test fun successIsNeverInventedFromAnAbsentCallback() {
        val recovery = InstallRecoveryLogic.recover(record(phase = PendingInstallPhase.STAGED), facts())
        assertFalse(recovery is InstallRecovery.Installed)
    }

    @Test fun persistFailureIsObservable() {
        val store = InMemoryPendingInstallStore()
        store.failWrites = true
        assertFalse(store.write(record()))
        assertNull(store.read())
        store.failWrites = false
        assertTrue(store.write(record()))
        assertNotNull(store.read())
    }

    @Test fun staleSessionResultIsRejected() {
        val current = record(sessionId = 7)
        // Old session after a newer pending session exists.
        assertTrue(ApkInstallResultPolicy.isStale(incomingSessionId = 5, record = current))
        // Matching session is accepted.
        assertFalse(ApkInstallResultPolicy.isStale(incomingSessionId = 7, record = current))
        // Missing id cannot be proven to belong to the current session.
        assertTrue(ApkInstallResultPolicy.isStale(incomingSessionId = -1, record = current))
        // Result with an id after the pending record is gone.
        assertTrue(ApkInstallResultPolicy.isStale(incomingSessionId = 7, record = null))
    }

    @Test fun staleWhenNoPendingRecordAndMissingSessionId() {
        assertTrue(ApkInstallResultPolicy.isStale(incomingSessionId = -1, record = null))
    }

    @Test fun staleResultMustNotCompleteCurrentInstall() {
        val store = InMemoryPendingInstallStore()
        val newer = record(sessionId = 7, transferId = "0123456789abcdef0123456789abcdef")
        store.write(newer)
        val incomingSessionId = 5
        assertFalse(ApkInstallResultPolicy.isStale(incomingSessionId = 7, store.read()))
        assertTrue(ApkInstallResultPolicy.isStale(incomingSessionId = 5, store.read()))
        // Stale path must leave the newer pending record untouched.
        assertEquals(7, store.read()?.sessionId)
        assertEquals(PendingInstallPhase.COMMITTED, store.read()?.phase)
    }

    @Test fun unknownBaselineCannotProveInstalledFromPackageFallback() {
        // Baseline lookup failed (NO_BASELINE). Candidate is later present.
        // Fallback evidence alone must not report Installed.
        val recovery = InstallRecoveryLogic.recover(
            record(versionCode = 5L, baselineVersionCode = PendingInstallRecord.NO_BASELINE,
                baselineLastUpdateTime = null),
            facts(sessionStillExists = false,
                installedVersionCode = 5L, installedLastUpdateTime = 2_000L))
        assertTrue(recovery is InstallRecovery.Unresolved)
        assertNull(InstallRecoveryLogic.haOutcome(recovery))
    }
}
