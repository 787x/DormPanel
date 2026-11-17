package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test

class SharedSyncUpdateTest {
    @Test fun v3RoundResultsPreserveProgressAndConflict() {
        for (code in listOf("OBSERVED", "PUBLISHED")) {
            assertEquals(code, SharedSyncResult.SUCCEEDED, v3SyncResult(code, pending = false))
            assertEquals(code, SharedSyncResult.CONTINUE, v3SyncResult(code, pending = true))
        }
        for (code in listOf("BUDGET", "DISCOVERING", "ROTATED", "PARTIAL", "EQUIVALENT_FRONTIERS")) {
            assertEquals(code, SharedSyncResult.CONTINUE, v3SyncResult(code, pending = false))
        }
        assertEquals(SharedSyncResult.NEEDS_ACTION, v3SyncResult("CONFLICT", pending = false))
        assertEquals(SharedSyncResult.FAILED, v3SyncResult("ERROR", pending = false))
        assertEquals(SharedSyncResult.CONTINUE, ScheduleHistoryPending().syncFailureResult())
        assertEquals(SharedSyncResult.FAILED, java.net.SocketTimeoutException().syncFailureResult())
        assertEquals(SharedSyncResult.FAILED, WebDavException("HTTP 401").syncFailureResult())
        assertNull(SharedConfirmationRejected().syncFailureResult())
    }

    @Test fun v2RoundUsesStructuredEvidence() {
        val verified = ScheduleSyncEngine.Outcome("ignored", checkState = "VERIFIED")
        assertEquals(SharedSyncResult.SUCCEEDED, verified.v2SyncResult(pending = false))
        assertEquals(SharedSyncResult.CONTINUE, verified.v2SyncResult(pending = true))
        assertEquals(SharedSyncResult.CONTINUE, verified.copy(checkState = "PARTIAL").v2SyncResult(pending = false))
        val unchecked = ScheduleSyncEngine.Outcome("ignored")
        assertNull(unchecked.v2SyncResult(pending = false))
        assertEquals(SharedSyncResult.CONTINUE, unchecked.copy(waitingToPublish = true).v2SyncResult(pending = false))
        val conflict = ScheduleMerge.Conflict("test", "conflict")
        assertEquals(SharedSyncResult.NEEDS_ACTION, verified.copy(conflicts = listOf(conflict)).v2SyncResult(pending = true))
    }

    @Test fun workerCompletionIsIdempotentAndObserversAreIndependent() {
        val runs = BackgroundScheduleSyncRuns()
        val events = mutableListOf<SharedSyncUpdate>()
        val removed = mutableListOf<SharedSyncUpdate>()
        val listener: (SharedSyncUpdate) -> Unit = { removed += it }
        runs.listeners.add { events += it }
        runs.listeners.add(listener)
        runs.started(1)
        runs.started(1)
        runs.listeners.remove(listener)
        runs.started(2)
        runs.finished(1, null)
        runs.finished(1, SharedSyncResult.FAILED)
        assertEquals(2L, runs.latest)
        runs.finished(2, SharedSyncResult.SUCCEEDED)
        assertNull(runs.latest)
        assertEquals(listOf(SharedSyncUpdate.Started(1)), removed)
        assertEquals(listOf(SharedSyncUpdate.Started(1), SharedSyncUpdate.Started(2),
            SharedSyncUpdate.Finished(1, null), SharedSyncUpdate.Finished(2, SharedSyncResult.SUCCEEDED)), events)
    }
}
