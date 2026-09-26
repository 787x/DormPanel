package com.dormpanel.app.apps

import android.content.Context

/**
 * SharedPreferences-backed pending install metadata.
 *
 * The file is excluded from backup and device transfer: a restored pending
 * PackageInstaller session id would be meaningless and must not be trusted.
 *
 * Baseline encoding in prefs:
 * - key missing → [PendingInstallRecord.NO_BASELINE] (no baseline captured)
 * - value -1 → package was absent (`baselineVersionCode = null`)
 * - any other value → that installed versionCode
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
        val hasBaseline = prefs.contains("baseline_version_code")
        val rawBaseline = if (hasBaseline) prefs.getLong("baseline_version_code", ABSENT_BASELINE) else null
        // Prefs encoding → data-class encoding:
        // no key → NO_BASELINE; -1 → null (absent package); else version.
        val baselineVersionCode: Long? = when {
            !hasBaseline -> PendingInstallRecord.NO_BASELINE
            rawBaseline == ABSENT_BASELINE -> null
            else -> rawBaseline
        }
        return PendingInstallRecord(
            sessionId = sessionId,
            packageName = packageName,
            stagedPath = stagedPath,
            stagedSha256 = prefs.getString("staged_sha256", "").orEmpty(),
            candidateVersionCode = prefs.getLong("candidate_version_code", -1L),
            candidateSigningSha256 = prefs.getString("candidate_signing_sha256", "").orEmpty(),
            sourceKind = prefs.getString("source_kind", InstallRecoveryLogic.SOURCE_LOCAL).orEmpty(),
            haTransferId = prefs.getString("ha_transfer_id", null)?.takeIf { it.isNotBlank() },
            phase = phase,
            message = prefs.getString("message", "").orEmpty(),
            baselineVersionCode = baselineVersionCode,
            baselineLastUpdateTime = if (prefs.contains("baseline_last_update_time"))
                prefs.getLong("baseline_last_update_time", 0L) else null,
        )
    }

    override fun write(record: PendingInstallRecord): Boolean {
        val editor = prefs.edit()
            .putInt("session_id", record.sessionId)
            .putString("package_name", record.packageName)
            .putString("staged_path", record.stagedPath)
            .putString("staged_sha256", record.stagedSha256)
            .putLong("candidate_version_code", record.candidateVersionCode)
            .putString("candidate_signing_sha256", record.candidateSigningSha256)
            .putString("source_kind", record.sourceKind)
            .putString("ha_transfer_id", record.haTransferId)
            .putString("phase", record.phase.name)
            .putString("message", record.message)
        when (record.baselineVersionCode) {
            null -> editor.putLong("baseline_version_code", ABSENT_BASELINE)
            PendingInstallRecord.NO_BASELINE -> editor.remove("baseline_version_code")
            else -> editor.putLong("baseline_version_code", record.baselineVersionCode)
        }
        if (record.baselineLastUpdateTime == null) editor.remove("baseline_last_update_time")
        else editor.putLong("baseline_last_update_time", record.baselineLastUpdateTime)
        return editor.commit()
    }

    override fun clear() {
        prefs.edit().clear().commit()
    }

    companion object {
        private const val ABSENT_BASELINE = -1L
    }
}
