package com.dormpanel.app.apps

import android.content.Context

/**
 * SharedPreferences-backed pending install metadata.
 *
 * The file is excluded from backup and device transfer: a restored pending
 * PackageInstaller session id would be meaningless and must not be trusted.
 */
class PreferencesPendingInstallStore(context: Context) : PendingInstallStore {
    private val prefs = context.applicationContext.getSharedPreferences("pending_install", Context.MODE_PRIVATE)

    override fun read(): PendingInstallRecord? {
        val sessionId = prefs.getInt("session_id", -1)
        if (sessionId < 0) return null
        val packageName = prefs.getString("package_name", null) ?: return null
        val stagedPath = prefs.getString("staged_path", null) ?: return null
        val phaseName = prefs.getString("phase", PendingInstallPhase.STAGED.name) ?: PendingInstallPhase.STAGED.name
        val phase = runCatching { PendingInstallPhase.valueOf(phaseName) }.getOrDefault(PendingInstallPhase.STAGED)
        return PendingInstallRecord(
            sessionId = sessionId,
            packageName = packageName,
            stagedPath = stagedPath,
            stagedSha256 = prefs.getString("staged_sha256", "").orEmpty(),
            candidateVersionCode = prefs.getLong("candidate_version_code", -1L),
            sourceKind = prefs.getString("source_kind", InstallRecoveryLogic.SOURCE_LOCAL).orEmpty(),
            haTransferId = prefs.getString("ha_transfer_id", null)?.takeIf { it.isNotBlank() },
            phase = phase,
            message = prefs.getString("message", "").orEmpty(),
        )
    }

    override fun write(record: PendingInstallRecord) {
        prefs.edit()
            .putInt("session_id", record.sessionId)
            .putString("package_name", record.packageName)
            .putString("staged_path", record.stagedPath)
            .putString("staged_sha256", record.stagedSha256)
            .putLong("candidate_version_code", record.candidateVersionCode)
            .putString("source_kind", record.sourceKind)
            .putString("ha_transfer_id", record.haTransferId)
            .putString("phase", record.phase.name)
            .putString("message", record.message)
            .commit()
    }

    override fun clear() {
        prefs.edit().clear().commit()
    }
}
