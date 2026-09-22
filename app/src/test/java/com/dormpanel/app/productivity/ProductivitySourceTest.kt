package com.dormpanel.app.productivity

import org.junit.Assert.*
import org.junit.Test

class ProductivitySourceTest {
    private class Clock(var wall: Long = 100_000, var elapsed: Long = 5000) : ProductivityClock {
        override fun wallMillis() = wall
        override fun elapsedMillis() = elapsed
        fun advance(ms: Long) { wall += ms; elapsed += ms }
    }
    private class Store : ProductivityStore {
        val rows = mutableMapOf<String, ProductivityState>()
        var writes = 0
        var loadCallback: ((Result<Map<String, ProductivityState>>) -> Unit)? = null
        var saveCallback: ((Result<Unit>) -> Unit)? = null
        var delayLoad = false
        override fun load(callback: (Result<Map<String, ProductivityState>>) -> Unit) {
            if (delayLoad) loadCallback = callback else callback(Result.success(rows.toMap()))
        }
        override fun save(owner: String, state: ProductivityState, callback: (Result<Unit>) -> Unit) {
            rows[owner] = state; writes++; saveCallback = callback
        }
        override fun close() = Unit
    }
    @Test fun todoCommandsAreIndependentOrderedAndPersisted() {
        val store = Store(); val clock = Clock(); val source = ProductivitySource(store, clock)
        source.addTodo("a", "  "); assertEquals(0, store.writes)
        source.addTodo("a", " first "); source.addTodo("a", "second"); source.addTodo("b", "separate")
        val first = source.state("a").todos.first()
        assertEquals("first", first.text)
        source.toggleTodo("a", first.id); assertTrue(source.state("a").todos.first().completed)
        source.toggleTodo("a", first.id); assertFalse(source.state("a").todos.first().completed)
        source.editTodo("a", first.id, " changed "); source.editTodo("a", first.id, " ")
        assertEquals(listOf("changed", "second"), source.state("a").todos.map { it.text })
        source.close()
        val restored = ProductivitySource(store, clock)
        assertEquals(listOf("changed", "second"), restored.state("a").todos.map { it.text })
        restored.deleteTodo("a", first.id)
        assertEquals(listOf("second"), restored.state("a").todos.map { it.text })
        assertEquals("separate", restored.state("b").todos.single().text)
    }
    @Test fun memoMultilineSaveCancelAndClear() {
        val store = Store(); val source = ProductivitySource(store, Clock())
        source.saveMemo("a", "line 1\nline 2"); source.saveMemo("b", "other")
        // An editor draft is never sent to the source until Save.
        val draft = source.state("a").memo + "discarded"
        assertNotEquals(draft, source.state("a").memo)
        source.close()
        val restored = ProductivitySource(store, Clock())
        assertEquals("line 1\nline 2", restored.state("a").memo)
        restored.saveMemo("a", ""); assertEquals("", restored.state("a").memo)
        assertEquals("other", restored.state("b").memo)
    }
    @Test fun timerPauseResumeResetExpiryAndIndependentOwners() {
        val clock = Clock(); val store = Store(); val source = ProductivitySource(store, clock)
        assertEquals(TimerStatus.READY, source.state("a").timer.status)
        assertEquals(0, store.writes)
        source.configureTimer("a", 10_000); source.configureTimer("b", 20_000)
        source.startTimer("a"); source.startTimer("b"); clock.advance(3000)
        source.pauseTimer("a"); clock.advance(4000)
        assertEquals(7000L, source.state("a").timer.remaining)
        assertEquals(13000L, source.state("b").timer.remaining)
        source.startTimer("a"); clock.advance(2000)
        assertEquals(5000L, source.state("a").timer.remaining)
        source.resetTimer("b"); assertEquals(TimerStatus.READY, source.state("b").timer.status)
        clock.advance(5000); assertEquals(TimerStatus.FINISHED, source.state("a").timer.status)
        source.resetTimer("a"); assertEquals(10000L, source.state("a").timer.remaining)
    }
    @Test fun monotonicWithinProcessWallDeadlineOnRecreationAndNoTickWrites() {
        val clock = Clock(); val store = Store(); var source = ProductivitySource(store, clock)
        source.configureTimer("a", 10_000); source.startTimer("a")
        val writes = store.writes
        repeat(4) { clock.advance(1000); source.state("a") }
        assertEquals(writes, store.writes)
        clock.wall += 3000
        assertEquals(6000L, source.state("a").timer.remaining)
        source.close(); source = ProductivitySource(store, clock)
        assertEquals(3000L, source.state("a").timer.remaining)
        source.close(); clock.advance(4000); source = ProductivitySource(store, clock)
        assertEquals(TimerStatus.FINISHED, source.state("a").timer.status)
        assertEquals(writes, store.writes)
    }
    @Test fun ownerNotificationsAndLateCallbacksAreSafe() {
        val store = Store(); val source = ProductivitySource(store, Clock())
        var a = 0; var b = 0
        source.subscribe("a") { a++ }; source.subscribe("b") { b++ }
        source.saveMemo("a", "note"); assertEquals(1, a); assertEquals(0, b)
        source.close(); store.saveCallback!!(Result.failure(Exception("late")))
        source.saveMemo("a", "closed"); assertEquals(1, a); assertFalse(source.error)
        val delayed = Store().apply { delayLoad = true }
        val closed = ProductivitySource(delayed, Clock()); closed.subscribe("a") { a++ }; closed.close()
        delayed.loadCallback!!(Result.success(mapOf("a" to ProductivityState(memo = "late"))))
        assertFalse(closed.ready); assertEquals(1, a)
    }
    @Test fun displayTickerNeverSchedulesIdleAndCancelsHiddenOrDetachedWork() {
        val pending = mutableSetOf<Runnable>(); var renders = 0; var delay = 0L
        val ticker = CountdownDisplayTicker({ task, ms -> pending += task; delay = ms }, { pending -= it }) { renders++ }
        listOf(TimerStatus.READY, TimerStatus.PAUSED, TimerStatus.FINISHED).forEach { ticker.update(true, it, 9000); assertTrue(pending.isEmpty()) }
        ticker.update(true, TimerStatus.RUNNING, 9500)
        assertEquals(500L, delay); assertEquals(1, pending.size)
        val late = pending.single()
        ticker.update(false, TimerStatus.RUNNING, 9000)
        assertTrue(pending.isEmpty()); late.run(); assertEquals(0, renders)
        ticker.update(true, TimerStatus.RUNNING, 8000)
        assertEquals(1000L, delay); ticker.stop(); assertTrue(pending.isEmpty())
        ticker.update(true, TimerStatus.RUNNING, 1000)
        pending.single().run(); assertEquals(1, renders)
    }
    @Test fun loadFailureDoesNotOverwriteExistingStorage() {
        val store = Store().apply { delayLoad = true }; val source = ProductivitySource(store, Clock())
        source.saveMemo("a", "too early"); assertEquals(0, store.writes)
        store.loadCallback!!(Result.failure(Exception("disk")))
        source.saveMemo("a", "unavailable"); assertEquals(0, store.writes); assertTrue(source.error)
    }
}
