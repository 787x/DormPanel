package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test

class SharedSyncUpdateTest {
    @Test fun v3RoundResultsPreserveProgressAndConflict() {
        val settled = SharedSchedulePresentation(mode = SharedMode.V3, ready = true)
        for (code in listOf("OBSERVED", "PUBLISHED")) {
            assertEquals(code, SharedSyncResult.SUCCEEDED, v3SyncResult(code) { settled })
            for (facts in listOf(settled.copy(pending = true), settled.copy(confirming = true),
                settled.copy(receipting = true), settled.copy(transition = true), SharedSchedulePresentation())) {
                assertEquals(code, SharedSyncResult.CONTINUE, v3SyncResult(code) { facts })
            }
            assertEquals(SharedSyncResult.NEEDS_ACTION, v3SyncResult(code) { settled.copy(recovery = true) })
            assertEquals(SharedSyncResult.NEEDS_ACTION, v3SyncResult(code) { settled.copy(conflict = true) })
        }
        for (code in listOf("BUDGET", "DISCOVERING", "ROTATED", "PARTIAL", "EQUIVALENT_FRONTIERS")) {
            assertEquals(code, SharedSyncResult.CONTINUE, v3SyncResult(code) { error("无需读取本机状态") })
        }
        assertEquals(SharedSyncResult.NEEDS_ACTION, v3SyncResult("CONFLICT") { error("无需读取本机状态") })
        assertEquals(SharedSyncResult.FAILED, v3SyncResult("ERROR") { error("无需读取本机状态") })
        assertEquals(SharedSyncResult.CONTINUE, ScheduleHistoryPending().syncFailureResult())
        assertEquals(SharedSyncResult.FAILED, java.net.SocketTimeoutException().syncFailureResult())
        assertEquals(SharedSyncResult.FAILED, WebDavException("HTTP 401").syncFailureResult())
        assertNull(SharedConfirmationRejected().syncFailureResult())
    }

    @Test fun syncAdmissionPreservesConflictRecheckAndSafeTransitions() {
        val v3 = SharedSchedulePresentation(mode = SharedMode.V3, ready = true)
        assertTrue(SharedAction.SYNC in v3.copy(conflict = true).actions)
        assertTrue(SharedAction.CONFLICT in v3.copy(conflict = true).actions)
        assertTrue(SharedAction.SYNC in v3.copy(transition = true).actions)
        assertFalse(SharedAction.SYNC in v3.copy(transition = true, recovery = true).actions)
        assertFalse(SharedAction.SYNC in v3.copy(mode = SharedMode.V2, transition = true).actions)
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
        runs.finished(2, SharedSyncResult.SUCCEEDED)
        assertEquals(1L, runs.latest)
        runs.finished(1, null)
        runs.finished(1, SharedSyncResult.FAILED)
        assertNull(runs.latest)
        assertEquals(listOf(SharedSyncUpdate.Started(1)), removed)
        assertEquals(listOf(SharedSyncUpdate.Started(1), SharedSyncUpdate.Started(2),
            SharedSyncUpdate.Finished(2, SharedSyncResult.SUCCEEDED), SharedSyncUpdate.Finished(1, null)), events)
    }
}
