package com.dormpanel.app.schedule

enum class SharedSyncResult { SUCCEEDED, CONTINUE, FAILED, NEEDS_ACTION }

sealed interface SharedSyncUpdate {
    data object State : SharedSyncUpdate
    data class Started(val id: Long) : SharedSyncUpdate
    /** A stopped job or a check that declined to run has no result to display. */
    data class Finished(val id: Long, val result: SharedSyncResult?) : SharedSyncUpdate
}

internal fun v3SyncResult(code: String, pending: Boolean): SharedSyncResult = when (code) {
    "OBSERVED", "PUBLISHED" -> if (pending) SharedSyncResult.CONTINUE else SharedSyncResult.SUCCEEDED
    "BUDGET", "DISCOVERING", "ROTATED", "PARTIAL", "EQUIVALENT_FRONTIERS" -> SharedSyncResult.CONTINUE
    "CONFLICT" -> SharedSyncResult.NEEDS_ACTION
    else -> SharedSyncResult.FAILED
}

internal fun ScheduleSyncEngine.Outcome.v2SyncResult(pending: Boolean): SharedSyncResult? = when {
    conflicts.isNotEmpty() -> SharedSyncResult.NEEDS_ACTION
    checkState == "PARTIAL" || waitingToPublish || pending -> SharedSyncResult.CONTINUE
    checkState == "VERIFIED" -> SharedSyncResult.SUCCEEDED
    else -> null
}

internal fun Throwable.syncFailureResult(): SharedSyncResult? = when (this) {
    is SharedConfirmationRejected -> null
    is ScheduleHistoryPending -> SharedSyncResult.CONTINUE
    is ScheduleDatasetWriteProtected -> SharedSyncResult.NEEDS_ACTION
    else -> SharedSyncResult.FAILED
}
