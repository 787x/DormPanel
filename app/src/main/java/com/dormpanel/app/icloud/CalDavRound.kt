package com.dormpanel.app.icloud

import okhttp3.Call
import java.util.concurrent.TimeUnit

/** Single worker owns the counters. The main thread can cancel the registered call. */
internal class CalDavRound(
    timeoutMillis: Long = 45_000,
    private val maxRequests: Int = 80,
    private val maxBytes: Long = 12L * 1024 * 1024,
) {
    private val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    @Volatile private var cancelled = false
    private var call: Call? = null
    private var requests = 0
    private var bytes = 0L

    fun check() {
        if (cancelled) throw CalDavFailure(CalDavProblem.CANCELLED)
        if (System.nanoTime() >= deadline) exhausted()
    }

    @Synchronized fun begin(next: Call) {
        check()
        if (++requests > maxRequests) exhausted()
        val timeout = next.timeout()
        val remaining = (deadline - System.nanoTime()).coerceAtLeast(1)
        val normal = timeout.timeoutNanos()
        timeout.timeout(if (normal == 0L) remaining else minOf(normal, remaining), TimeUnit.NANOSECONDS)
        call = next
    }

    @Synchronized fun end() { call = null }

    @Synchronized fun cancel() {
        cancelled = true
        call?.cancel()
    }

    fun bodyLimit(perResponse: Long): Long {
        check()
        val remaining = maxBytes - bytes
        if (remaining <= 0) exhausted()
        return minOf(remaining, perResponse)
    }

    fun received(count: Long) {
        check()
        bytes += count
        if (bytes > maxBytes) exhausted()
    }

    fun oversized(limit: Long, perResponse: Long): Nothing {
        if (limit < perResponse) exhausted()
        throw CalDavFailure(CalDavProblem.CAPACITY)
    }

    private fun exhausted(): Nothing = throw CalDavFailure(CalDavProblem.BUDGET)
}
