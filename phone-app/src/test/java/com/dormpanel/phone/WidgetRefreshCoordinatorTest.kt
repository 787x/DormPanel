package com.dormpanel.phone

import org.junit.Assert.*
import org.junit.Test

class WidgetRefreshCoordinatorTest {
    private class Harness {
        var now = 0L
        var widgets = true
        val timers = mutableListOf<Triple<Long, Int, () -> Unit>>()
        var order = 0
        val reads = mutableListOf<(Result<String>) -> Unit>()
        val frames = mutableListOf<String>()
        var recovery = 0
        var cancelled = 0
        var thrown = false
        val machine = WidgetRefreshCoordinator<String>(
            { widgets }, { delay, task -> timers.add(Triple(now+delay,order++,task)) },
            { callback -> if (thrown) error("read failed") else reads.add(callback) },
            { result -> frames.add(result.getOrElse { "error" }) },
            { frames.add("timeout") }, { recovery++ }, { cancelled++ })
        fun advance(target: Long) {
            while (true) {
                val task = timers.filter { it.first <= target }.minWithOrNull(compareBy<Triple<Long,Int,()->Unit>> { it.first }.thenBy { it.second }) ?: break
                timers.remove(task); now = task.first; task.third()
            }
            now = target
        }
        fun answer(index: Int, value: String) = reads[index](Result.success(value))
    }
    @Test fun coldFactoriesShareOnePhysicalReadInEitherOrderAndWarmFactoriesReadNothing() {
        for(order in listOf(listOf("today","tomorrow"),listOf("tomorrow","today"))) {
            val h=Harness()
            order.forEach { h.machine.ensure(false) }
            assertEquals(1,h.reads.size)
            h.answer(0,"both columns")
            assertEquals(listOf("both columns"),h.frames)
            order.forEach { h.machine.ensure(true) }; assertEquals(1,h.reads.size)
        }
    }
    @Test fun actualCommitDuringFactoryReadStillSupersedesAndIncludesLatestData() {
        val h=Harness(); h.machine.ensure(false); h.machine.ensure(false)
        h.machine.request(); h.machine.ensure(false)
        assertEquals(1,h.reads.size)
        h.answer(0,"before commit"); assertTrue(h.frames.isEmpty()); assertEquals(2,h.reads.size)
        h.machine.ensure(false); h.answer(1,"after commit")
        assertEquals(listOf("after commit"),h.frames); assertEquals(2,h.reads.size)
    }
    @Test fun factoryFailureTimeoutRemovalAndReconstructionCanRecoverWithoutDuplicateReads() {
        val h=Harness(); h.machine.ensure(false); h.machine.ensure(false)
        h.reads[0](Result.failure(IllegalStateException()))
        h.machine.ensure(false); h.machine.ensure(false); assertEquals(2,h.reads.size)
        h.advance(7000); h.machine.ensure(false); h.machine.ensure(false); assertEquals(3,h.reads.size)
        h.answer(2,"recovered"); h.answer(1,"late"); assertEquals("recovered",h.frames.last())
        h.widgets=false; h.machine.ensure(false); assertEquals(3,h.reads.size)
        val rebuilt=Harness(); rebuilt.machine.ensure(false); rebuilt.machine.ensure(false); assertEquals(1,rebuilt.reads.size)
    }
    @Test fun timeoutWithNewCommitProbesAndNeverPublishesLateSnapshot() {
        val h=Harness(); var old=0; var fresh=0
        h.machine.request { old++ }; h.advance(2000)
        h.machine.request { fresh++ }; h.advance(7000)
        assertEquals(2,h.reads.size); assertEquals(listOf("timeout"),h.frames)
        assertEquals(1,old); assertEquals(0,fresh)
        h.answer(0,"old date"); assertEquals(0,fresh)
        assertEquals(listOf("timeout"),h.frames)
        h.answer(1,"latest committed courses")
        assertEquals(listOf("timeout","latest committed courses"),h.frames)
        h.advance(30000); h.answer(0,"duplicate")
        assertEquals(1,old); assertEquals(1,fresh); assertEquals(2,h.frames.size)
    }
    @Test fun lateResultCannotRollbackSuccessfulProbeOrItsAlarm() {
        val h=Harness(); h.machine.request(); h.machine.request(); h.advance(7000)
        h.answer(1,"new"); val alarms=h.recovery
        h.answer(0,"old"); h.advance(14000)
        assertEquals(listOf("timeout","new"),h.frames); assertEquals(alarms,h.recovery)
    }
    @Test fun repeatedRequestsCoalesceAndNormalRefreshKeepsExistingFrame() {
        val h=Harness(); h.machine.request(); h.answer(0,"first")
        h.machine.request(); repeat(100) { h.machine.request() }
        assertEquals(listOf("first"),h.frames); assertEquals(2,h.reads.size)
        h.answer(1,"obsolete"); assertEquals(3,h.reads.size)
        assertEquals(listOf("first"),h.frames)
        h.answer(2,"last"); assertEquals(listOf("first","last"),h.frames)
        h.advance(20000); assertEquals(3,h.reads.size)
    }
    @Test fun twoPhysicalLeasesBoundPermanentlyBlockedRoomQueue() {
        val h=Harness(); var finished=0
        h.machine.request { finished++ }; h.machine.request { finished++ }; h.advance(7000)
        repeat(100) { h.machine.request { finished++ } }
        h.advance(14000); repeat(100) { h.machine.request { finished++ } }
        h.advance(30000)
        assertEquals(2,h.reads.size); assertEquals(202,finished)
        h.answer(0,"expired"); assertEquals(3,h.reads.size)
        h.answer(1,"also expired"); assertFalse(h.frames.contains("also expired"))
        h.answer(2,"recovered"); assertEquals("recovered",h.frames.last())
    }
    @Test fun ordinaryTimeoutSchedulesRecoveryWithoutFastLoop() {
        val h=Harness(); h.machine.request(); h.advance(7000)
        assertEquals(listOf("timeout"),h.frames); assertEquals(1,h.recovery)
        h.answer(0,"late"); h.advance(60000); assertEquals(1,h.reads.size)
        h.machine.request(); h.answer(1,"recovered")
        assertEquals(listOf("timeout","recovered"),h.frames)
    }
    @Test fun requestImmediatelyAfterTimeoutStillGetsReadOpportunity() {
        val h=Harness(); h.machine.request(); h.advance(7000)
        h.machine.request(); assertEquals(2,h.reads.size)
        h.answer(0,"stale"); h.answer(1,"new"); assertEquals("new",h.frames.last())
    }
    @Test fun synchronousExceptionAndAsyncFailureAcceptLaterSuccess() {
        val h=Harness(); h.thrown=true; var finish=0
        h.machine.request { finish++ }; assertEquals(1,finish)
        h.thrown=false; h.machine.request { finish++ }
        h.reads[0](Result.failure(IllegalStateException()))
        h.machine.request { finish++ }; h.answer(1,"good"); h.advance(30000)
        assertEquals(listOf("error","error","good"),h.frames); assertEquals(3,finish)
    }
    @Test fun removalCancelsAndLateCallbacksCannotRescheduleOrFinishNewWaiters() {
        val h=Harness(); var finished=0
        h.machine.request { finished++ }; h.widgets=false; h.machine.request { finished++ }
        assertEquals(2,finished); assertEquals(1,h.cancelled)
        val alarms=h.recovery; h.answer(0,"deleted"); h.advance(20000)
        assertTrue(h.frames.isEmpty()); assertEquals(alarms,h.recovery)
        h.widgets=true; h.machine.request { finished++ }; h.answer(1,"new instance")
        assertEquals(listOf("new instance"),h.frames); assertEquals(3,finished)
    }
    @Test fun sameTickTimeoutAndRequestWorkInEitherQueueOrder() {
        for (before in listOf(true,false)) {
            val h=Harness()
            fun queueRequest() { h.timers.add(Triple(7000,h.order++, { h.machine.request() })) }
            if (before) queueRequest()
            h.machine.request()
            if (!before) queueRequest()
            h.advance(7000); assertEquals(2,h.reads.size)
            h.answer(0,"old"); h.answer(1,"current")
            assertEquals(listOf("timeout","current"),h.frames)
        }
    }
    @Test fun eachBroadcastExpiresOnceEvenWhileNewestReadRemainsBlocked() {
        val h=Harness(); var first=0; var second=0
        h.machine.request { first++ }; h.advance(6000); h.machine.request { second++ }
        h.advance(8000); assertEquals(1,first); assertEquals(0,second)
        h.answer(0,"late"); h.answer(1,"latest")
        h.advance(20000); assertEquals(1,first); assertEquals(1,second)
    }

    @Test fun offThenOnBeforeOldReadReturnsCannotPublishOldResult() {
        val h=Harness(); h.machine.request()
        h.widgets=false; h.machine.request()
        h.widgets=true; h.machine.request()
        h.answer(0,"before off"); assertTrue(h.frames.isEmpty())
        h.answer(1,"after on"); assertEquals(listOf("after on"),h.frames)
    }

    @Test fun publishExceptionIsRetransmittedAsSameThrowableForDeduplication() {
        // WidgetRefreshCoordinator retransmits the exact Throwable that publish() threw
        // as Result.failure, so the coordinator can deduplicate the second callback
        // without overwriting the specific failure stage.
        val timers = mutableListOf<Triple<Long, Int, () -> Unit>>()
        var order = 0
        var now = 0L
        val reads = mutableListOf<(Result<String>) -> Unit>()
        var recovery = 0
        val captures = mutableListOf<Throwable?>()
        val thrown = IllegalStateException("notify failed")
        val machine = WidgetRefreshCoordinator<String>(
            { true }, { ms, task -> timers.add(Triple(now + ms, order++, task)) },
            { cb -> reads.add(cb) },
            { result ->
                if (result.isSuccess) throw thrown
                captures.add(result.exceptionOrNull())
            },
            {}, { recovery++ }, {})
        machine.request()
        reads.single()(Result.success("value"))
        assertEquals(1, captures.size)
        assertSame(thrown, captures.single())
        assertEquals(1, recovery)
    }

    @Test fun recoveryStillRunsWhenRetransmittedFailureHandlingAlsoThrows() {
        // A second failure while handling the retransmitted Result.failure must not
        // escape or suppress the recovery path (bounded retry, no crash loop).
        val timers = mutableListOf<Triple<Long, Int, () -> Unit>>()
        var order = 0
        var now = 0L
        val reads = mutableListOf<(Result<String>) -> Unit>()
        var recovery = 0
        val machine = WidgetRefreshCoordinator<String>(
            { true }, { ms, task -> timers.add(Triple(now + ms, order++, task)) },
            { cb -> reads.add(cb) },
            { result ->
                if (result.isSuccess) throw IllegalStateException("publish failed")
                throw IllegalStateException("retransmitted failure handling failed")
            },
            {}, { recovery++ }, {})
        machine.request()
        reads.single()(Result.success("value"))
        assertEquals(1, recovery)
    }
}
