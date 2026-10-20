package com.dormpanel.app.schedule

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Briefly gates stale UI writes only while Room integrates, never during network I/O. */
internal object SharedScheduleViews {
    private val main = Handler(Looper.getMainLooper())
    private val sources = linkedSetOf<ScheduleSource>() // main thread only
    fun add(source: ScheduleSource) { sources += source }
    fun remove(source: ScheduleSource) { sources -= source }
    fun updated() { main.post { sources.toList().forEach { it.refresh() } } }
    private fun <T> onMain(action: () -> T): T {
        val result = CompletableFuture<T>()
        main.post { runCatching(action).onSuccess { result.complete(it) }.onFailure { result.completeExceptionally(it) } }
        return result.get(30, TimeUnit.SECONDS)
    }
    fun <T> changing(action: () -> T): T {
        val active = onMain { sources.filter { it.ready }.also { list -> list.forEach { it.pauseForSync() } } }
        try { return action() } finally {
            val loaded = CompletableFuture<Unit>()
            main.post {
                var remaining = active.size
                if (remaining == 0) loaded.complete(Unit)
                active.forEach { source -> source.reload { if (--remaining == 0) loaded.complete(Unit) } }
            }
            loaded.get(30, TimeUnit.SECONDS)
        }
    }
}

internal class VisibleScheduleSyncStore(private val store: ScheduleSyncStore) : ScheduleSyncStore by store {
    override fun establishBaseline(bytes: ByteArray) = SharedScheduleViews.changing { store.establishBaseline(bytes) }
    override fun integrateRemote(bytes: ByteArray) = SharedScheduleViews.changing { store.integrateRemote(bytes) }
    override fun resolveConflict(choice: ScheduleMerge.Choice) = SharedScheduleViews.changing { store.resolveConflict(choice) }
}
