package com.dormpanel.app.apps

/**
 * Minimal non-secret metadata for one PackageInstaller session.
 * Secrets (HA device secret, signed download URLs, WebDAV password) and APK
 * bytes are never stored here.
 *
 * [baselineVersionCode] / [baselineLastUpdateTime] are captured before
 * `PackageInstaller.Session.commit` so a later reconcile can distinguish an
 * actual install/update of this candidate from a pre-existing package.
 * `baselineVersionCode == null` means the package was absent.
 */
data class PendingInstallRecord(
    val sessionId: Int,
    val packageName: String,
    val stagedPath: String,
    val stagedSha256: String,
    val candidateVersionCode: Long,
    val candidateSigningSha256: String,
    val sourceKind: String,
    val haTransferId: String? = null,
    val phase: PendingInstallPhase = PendingInstallPhase.STAGED,
    val message: String = "",
    /**
     * Pre-install installed version for this package.
     * `null` = package was absent. `NO_BASELINE` = no baseline was captured
     * (fallback evidence is then impossible). Any other value is the version.
     */
    val baselineVersionCode: Long? = null,
    val baselineLastUpdateTime: Long? = null,
) {
    companion object {
        /** Sentinel: no pre-install baseline was captured. */
        const val NO_BASELINE = Long.MIN_VALUE
    }
}

enum class PendingInstallPhase {
    /** APK staged locally; PackageInstaller session not yet committed. */
    STAGED,
    /** Session committed; waiting for the Android installer UI or result. */
    COMMITTED,
    /** Android asked the user to confirm. */
    AWAITING_USER,
    /** Terminal: package installed. */
    INSTALLED,
    /** Terminal: install failed. */
    INSTALL_FAILED,
    /** Terminal: user cancelled / dismissed. */
    DISMISSED,
    /**
     * After process death the platform could not prove success or failure.
     * Never reported as installed; kept so a later reconcile or HA
     * rediscovery can settle it.
     */
    UNRESOLVED,
}

/** Facts the platform can prove at reconcile time. Absent facts stay null. */
data class PlatformInstallFacts(
    val broadcastStatus: Int? = null,
    val broadcastMessage: String = "",
    val sessionStillExists: Boolean = false,
    val installedVersionCode: Long? = null,
    val installedLastUpdateTime: Long? = null,
    val installedSigningSha256: String? = null,
)

sealed class InstallRecovery {
    abstract val record: PendingInstallRecord

    data class Installed(override val record: PendingInstallRecord) : InstallRecovery()
    data class Failed(override val record: PendingInstallRecord) : InstallRecovery()
    data class Dismissed(override val record: PendingInstallRecord) : InstallRecovery()
    data class StillPending(override val record: PendingInstallRecord) : InstallRecovery()
    data class Unresolved(override val record: PendingInstallRecord) : InstallRecovery()
}

/**
 * Deterministic recovery state machine. A lost in-process callback must never
 * invent a success.
 *
 * Authoritative success is the PackageInstaller terminal success broadcast.
 * Package-state fallback requires real evidence of *this* install (absent→present
 * with matching signing identity, a genuine version advance from the captured
 * baseline, or a same-version replacement proven by lastUpdateTime + signing).
 * An unchanged or merely newer pre-existing package is never enough.
 */
object InstallRecoveryLogic {
    const val SOURCE_LOCAL = "local"
    const val SOURCE_LAN = "lan"
    const val SOURCE_WEBDAV = "webdav"
    const val SOURCE_HA = "ha"

    fun recover(record: PendingInstallRecord, facts: PlatformInstallFacts): InstallRecovery {
        if (record.phase == PendingInstallPhase.INSTALLED ||
            record.phase == PendingInstallPhase.INSTALL_FAILED ||
            record.phase == PendingInstallPhase.DISMISSED) {
            return when (record.phase) {
                PendingInstallPhase.INSTALLED -> InstallRecovery.Installed(record)
                PendingInstallPhase.INSTALL_FAILED -> InstallRecovery.Failed(record)
                PendingInstallPhase.DISMISSED -> InstallRecovery.Dismissed(record)
                else -> InstallRecovery.Unresolved(record)
            }
        }
        val status = facts.broadcastStatus
        if (status != null) {
            return when {
                // Terminal SUCCESS from PackageInstaller is authoritative.
                status == 0 -> InstallRecovery.Installed(
                    record.copy(phase = PendingInstallPhase.INSTALLED, message = facts.broadcastMessage))
                status == 3 -> InstallRecovery.Dismissed(
                    record.copy(phase = PendingInstallPhase.DISMISSED,
                        message = facts.broadcastMessage.ifBlank { "Installation cancelled." }))
                status == -1 -> InstallRecovery.StillPending(
                    record.copy(phase = PendingInstallPhase.AWAITING_USER, message = facts.broadcastMessage))
                else -> InstallRecovery.Failed(
                    record.copy(phase = PendingInstallPhase.INSTALL_FAILED,
                        message = facts.broadcastMessage.ifBlank { "Installation failed." }))
            }
        }
        if (facts.sessionStillExists) {
            val phase = if (record.phase == PendingInstallPhase.AWAITING_USER) PendingInstallPhase.AWAITING_USER
            else PendingInstallPhase.COMMITTED
            return InstallRecovery.StillPending(record.copy(phase = phase))
        }
        if (provenPackageInstall(record, facts)) {
            return InstallRecovery.Installed(record.copy(phase = PendingInstallPhase.INSTALLED,
                message = "Verified from pre-install baseline and installed package state."))
        }
        // Session gone without a terminal broadcast and without install evidence.
        // Prefer an unresolved record (and later HA rediscovery) over a false installed.
        return InstallRecovery.Unresolved(record.copy(phase = PendingInstallPhase.UNRESOLVED,
            message = "Installation result is unknown after process restart."))
    }

    /**
     * True only when package state proves *this* candidate replaced or created
     * the installed package. VersionCode alone is never sufficient.
     */
    fun provenPackageInstall(record: PendingInstallRecord, facts: PlatformInstallFacts): Boolean {
        val now = facts.installedVersionCode ?: return false
        val baseline = record.baselineVersionCode
        // No captured baseline: cannot prove this install created the package.
        if (baseline == PendingInstallRecord.NO_BASELINE) return false
        val signingOk = signingMatches(record, facts)

        // New install: package was absent before and is present now as candidate.
        if (baseline == null) {
            return now == record.candidateVersionCode && signingOk
        }
        // A pre-existing newer (or different) package does not prove this
        // candidate installed — e.g. cancel of v5 while v6 is already present.
        if (now != record.candidateVersionCode) return false
        if (now > baseline) {
            // Genuine version advance from the captured pre-install version.
            return signingOk
        }
        // Same version as the baseline: only a replacement proven by
        // lastUpdateTime advancement (plus signing identity) counts.
        // A cancelled same-version reinstall leaves lastUpdateTime unchanged.
        val baseTime = record.baselineLastUpdateTime ?: return false
        val nowTime = facts.installedLastUpdateTime ?: return false
        return nowTime > baseTime && signingOk
    }

    private fun signingMatches(record: PendingInstallRecord, facts: PlatformInstallFacts): Boolean {
        val installed = facts.installedSigningSha256
        // Without an installed fingerprint we cannot confirm identity; require it.
        if (installed.isNullOrBlank()) return false
        if (record.candidateSigningSha256.isBlank()) return false
        return installed.equals(record.candidateSigningSha256, ignoreCase = true)
    }

    fun haOutcome(recovery: InstallRecovery): String? = when (recovery) {
        is InstallRecovery.Installed -> "installed"
        is InstallRecovery.Failed -> "install_failed"
        is InstallRecovery.Dismissed -> "dismissed"
        is InstallRecovery.StillPending -> null
        is InstallRecovery.Unresolved -> null
    }
}

/** Terminal or pending outcome that may still need a Home Assistant ack. */
data class RecoveredInstallOutcome(
    val packageName: String,
    val outcome: String,
    val message: String,
    val haTransferId: String?,
)

/**
 * Stale PackageInstaller result policy. A broadcast must belong to the
 * currently persisted session; anything else is ignored entirely.
 */
object ApkInstallResultPolicy {
    fun isStale(incomingSessionId: Int, record: PendingInstallRecord?): Boolean = when {
        // No current pending session: only a missing id is not provably stale.
        record == null -> incomingSessionId >= 0
        // Cannot prove the result belongs to the current session.
        incomingSessionId < 0 -> true
        else -> record.sessionId != incomingSessionId
    }
}

/** Persistence for one in-flight install session. Not a secret store. */
interface PendingInstallStore {
    fun read(): PendingInstallRecord?
    /** Returns false when the record was not durably stored. */
    fun write(record: PendingInstallRecord): Boolean
    fun clear()
}

/** In-memory store for unit tests and for holding the record during a live session. */
class InMemoryPendingInstallStore : PendingInstallStore {
    private var record: PendingInstallRecord? = null
    var failWrites = false
    override fun read(): PendingInstallRecord? = record
    override fun write(record: PendingInstallRecord): Boolean {
        if (failWrites) return false
        this.record = record
        return true
    }
    override fun clear() { record = null }
}
