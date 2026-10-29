package com.dormpanel.phone

/** Single-thread confined. A timeout retires publication rights, not the physical Store task.
 * At most two unreturned reads (the blocked original plus one probe) may occupy Room's queue.
 * Timers and duplicate/late callbacks can only affect the attempt that created them. */
internal class WidgetRefreshCoordinator<T>(
    private val present: () -> Boolean,
    private val later: (Long, () -> Unit) -> Unit,
    private val read: ((Result<T>) -> Unit) -> Unit,
    private val publish: (Result<T>) -> Unit,
    private val timedOut: () -> Unit,
    private val recover: () -> Unit,
    private val cancel: () -> Unit
) {
    private data class Attempt(val id: Long, val generation: Long)
    private class Waiter(val generation: Long, val done: () -> Unit) {
        var finished = false
        fun finish() { if (!finished) { finished = true; runCatching(done) } }
    }
    private var generation = 0L
    private var serial = 0L
    private var active: Attempt? = null
    private var dirty = false
    private val outstanding = mutableSetOf<Long>()
    private val waiters = mutableListOf<Waiter>()

    /** Cache availability demand joins an active/queued read without invalidating its generation.
     * Only actual update signals use request(), which must still supersede an in-flight result. */
    fun ensure(available: Boolean) {
        if (!present()) { removed(); return }
        if (available || active != null || dirty) return
        request()
    }
    fun request(done: () -> Unit = {}) {
        val waiter = Waiter(++generation, done)
        waiters.add(waiter)
        later(8000) { waiter.finish(); waiters.remove(waiter) }
        dirty = true
        if (!present()) { removed(); return }
        if (active == null) start()
    }
    private fun finishThrough(value: Long) {
        val completed = waiters.filter { it.generation <= value }
        waiters.removeAll(completed.toSet()); completed.forEach { it.finish() }
    }
    private fun removed() {
        active = null; dirty = false
        cancel(); finishThrough(generation)
        // Physical leases remain until their callbacks: removal cannot cancel Room work.
    }
    private fun start() {
        if (!present()) { removed(); return }
        if (!dirty || active != null) return
        if (outstanding.size >= 2) { recover(); return }
        val attempt = Attempt(++serial, generation)
        outstanding.add(attempt.id); active = attempt; dirty = false
        later(7000) {
            if (active != attempt) return@later
            active = null
            if (!present()) { removed(); return@later }
            dirty = generation != attempt.generation
            timedOut(); recover(); finishThrough(attempt.generation)
            // Newer requests get one bounded probe immediately; ordinary timeouts use alarm.
            if (generation != attempt.generation) start()
        }
        try { read { complete(attempt, it) } }
        catch (error: Exception) { complete(attempt, Result.failure(error)) }
    }
    private fun complete(attempt: Attempt, result: Result<T>) {
        if (!outstanding.remove(attempt.id)) return // duplicate completion
        if (!present()) { removed(); return }
        if (active != attempt) {
            // An expired read has no publication/finish/alarm rights. Its released slot may
            // admit the newest request only when no newer read is already active.
            if (active == null && dirty) start()
            return
        }
        active = null
        if (generation == attempt.generation) {
            try { publish(result) }
            catch (error: Exception) { runCatching { publish(Result.failure(error)) }; recover() }
            if (result.isFailure) recover()
            finishThrough(attempt.generation)
        } else dirty = true
        if (dirty) start()
    }
}
