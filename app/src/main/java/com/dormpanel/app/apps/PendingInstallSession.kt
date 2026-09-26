package com.dormpanel.app.apps

/**
 * Minimal non-secret metadata for one PackageInstaller session.
 * Secrets (HA device secret, signed download URLs, WebDAV password) and APK
 * bytes are never stored here.
 */
data class PendingInstallRecord(
    val sessionId: Int,
    val packageName: String,
    val stagedPath: String,
    val stagedSha256: String,
    val candidateVersionCode: Long,
    val sourceKind: String,
    val haTransferId: String? = null,
    val phase: PendingInstallPhase = PendingInstallPhase.STAGED,
    val message: String = "",
)

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
 * invent a success: only an installer broadcast or package version proof can
 * produce [InstallRecovery.Installed].
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
            val terminal = when {
                status == 0 -> InstallRecovery.Installed(record.copy(phase = PendingInstallPhase.INSTALLED, message = facts.broadcastMessage))
                status == 3 -> InstallRecovery.Dismissed(record.copy(phase = PendingInstallPhase.DISMISSED, message = facts.broadcastMessage.ifBlank { "Installation cancelled." }))
                status == -1 -> InstallRecovery.StillPending(record.copy(phase = PendingInstallPhase.AWAITING_USER, message = facts.broadcastMessage))
                else -> InstallRecovery.Failed(record.copy(phase = PendingInstallPhase.INSTALL_FAILED, message = facts.broadcastMessage.ifBlank { "Installation failed." }))
            }
            return terminal
        }
        if (facts.sessionStillExists) {
            val phase = if (record.phase == PendingInstallPhase.AWAITING_USER) PendingInstallPhase.AWAITING_USER
            else PendingInstallPhase.COMMITTED
            return InstallRecovery.StillPending(record.copy(phase = phase))
        }
        val installed = facts.installedVersionCode
        if (installed != null && installed >= record.candidateVersionCode) {
            return InstallRecovery.Installed(record.copy(phase = PendingInstallPhase.INSTALLED, message = "Verified from installed package state."))
        }
        // Session gone and the target package does not prove the candidate.
        // Prefer an unresolved record (and later HA rediscovery) over a false installed.
        return InstallRecovery.Unresolved(record.copy(phase = PendingInstallPhase.UNRESOLVED,
            message = "Installation result is unknown after process restart."))
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

/** Persistence for one in-flight install session. Not a secret store. */
interface PendingInstallStore {
    fun read(): PendingInstallRecord?
    fun write(record: PendingInstallRecord)
    fun clear()
}

/** In-memory store for unit tests and for holding the record during a live session. */
class InMemoryPendingInstallStore : PendingInstallStore {
    private var record: PendingInstallRecord? = null
    override fun read(): PendingInstallRecord? = record
    override fun write(record: PendingInstallRecord) { this.record = record }
    override fun clear() { record = null }
}
