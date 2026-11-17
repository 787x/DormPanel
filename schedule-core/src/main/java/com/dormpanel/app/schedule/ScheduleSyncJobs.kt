package com.dormpanel.app.schedule

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** One coalesced, network-constrained job, no periodic polling or permanent service. */
object ScheduleSyncJobs {
    private const val ID = 3201
    // Job lifecycle and observers are main-thread confined.
    internal var latestRunId = 0L
        private set
    internal fun nextRunId() = ++latestRunId
    internal val runs = BackgroundScheduleSyncRuns()
    internal val latestRunning get() = runs.latest
    internal fun addListener(listener: (SharedSyncUpdate) -> Unit) { runs.listeners.add(listener) }
    internal fun removeListener(listener: (SharedSyncUpdate) -> Unit) { runs.listeners.remove(listener) }

    fun schedule(context: Context, delay: Long = 1500) {
        // Test namespaces must never schedule a service against the real application database.
        if (SharedScheduleSettings.overrideNamespace != null || ScheduleStores.overrideFactory != null) return
        if (SharedScheduleSettings(context).folder.isBlank()) return
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val job = JobInfo.Builder(ID, ComponentName(context, ScheduleSyncJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
            .setMinimumLatency(delay).setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
        scheduler.schedule(job)
    }
}

/** The set tracks active workers, including stopped jobs still unwinding. */
internal class BackgroundScheduleSyncRuns {
    private val active = linkedSetOf<Long>()
    val latest get() = active.lastOrNull()
    val listeners = linkedSetOf<(SharedSyncUpdate) -> Unit>()

    fun started(id: Long) {
        if (active.add(id)) listeners.toList().forEach { it(SharedSyncUpdate.Started(id)) }
    }

    fun finished(id: Long, result: SharedSyncResult?) {
        if (active.remove(id)) listeners.toList().forEach { it(SharedSyncUpdate.Finished(id, result)) }
    }
}

class ScheduleSyncJobService : JobService() {
    private val worker = Executors.newSingleThreadExecutor()
    private class Invocation(val id: Long) {
        @Volatile var stopped = false
    }
    // JobService callbacks and finish() run on the main thread.
    private val jobs = mutableMapOf<Int, Invocation>()

    override fun onStartJob(params: JobParameters): Boolean {
        val invocation = Invocation(ScheduleSyncJobs.nextRunId())
        jobs.put(params.jobId, invocation)?.stopped = true
        try {
            worker.execute { synchronize(params, invocation) }
        } catch (_: RejectedExecutionException) {
            jobs.remove(params.jobId)
            return false
        }
        ScheduleSyncJobs.runs.started(invocation.id)
        return true
    }

    private fun synchronize(params: JobParameters, invocation: Invocation) {
        var retry = false
        var checkScope: String? = null
        var outcome: SharedSyncResult? = null
        var openedStore: ScheduleStore? = null
        try {
            if (invocation.stopped) return
            val store = ScheduleStores.create(this).also { openedStore = it }
            val settings = SharedScheduleSettings(this)
            if (store is RoomScheduleStore && store.isV3()) {
                val result = synchronized(SharedScheduleRunLock) {
                    if (invocation.stopped) return
                    val account = store.v3Account(WebDavSettings(this))
                    checkScope = sharedCheckScope(account, store.v3EndpointFolder())
                    store.synchronizeV3(account).also { code ->
                        val pending = code in listOf("OBSERVED", "PUBLISHED") && store.sharedPresentation(true).let {
                            it.pending || it.confirming || it.receipting || it.transition || it.recovery || it.conflict
                        }
                        outcome = v3SyncResult(code, pending)
                    }
                }
                retry = result in listOf("BUDGET", "DISCOVERING", "ROTATED") && settings.retryAllowed()
                val verified = result in listOf("OBSERVED", "PUBLISHED")
                settings.recordCheck(checkScope, result, verified)
                settings.result(v3StatusMessage(result), verified)
                return
            }
            if (settings.folder.isNotBlank() && store is ScheduleSyncStore) {
                val result = synchronized(SharedScheduleRunLock) {
                    if (invocation.stopped) return
                    val local = store as ScheduleV2Store
                    if (local.v2DatasetPaused()) return@synchronized null
                    if (store is RoomScheduleStore && !store.sharedPresentation(true).bound) return@synchronized null
                    val frame = local.v2Frame()
                    if (frame.baseline == null && frame.pending == null) null else {
                        val account = requireNotNull(WebDavSettings(this).account())
                        val parent = SharedScheduleTransport().folder(account, settings.folder)
                        checkScope = sharedCheckScope(account, parent)
                        val folder = WebDavClient().url(parent).newBuilder()
                            .addPathSegment("dormpanel-sync-v2").addPathSegment("").build().toString()
                        val visible = object : ScheduleV2Store by local {
                            override fun v2Apply(plan: ScheduleV2Plan) = SharedScheduleViews.changing { local.v2Apply(plan) }
                            override fun v2Receipt(bytes: ByteArray) = SharedScheduleViews.changing { local.v2Receipt(bytes) }
                        }
                        AppendOnlyScheduleSync(visible, WebDavCommitStore(account, folder)).synchronize().also {
                            val pending = it.checkState == "VERIFIED" && local.v2Frame().let { frame ->
                                frame.pending != null || frame.unverifiable.isNotEmpty() ||
                                    frame.baseline?.let { baseline -> !ScheduleMerge.same(frame.local, baseline.state) } != false
                            }
                            outcome = it.v2SyncResult(pending)
                        }
                    }
                }
                result?.let {
                    settings.recordCheck(checkScope, if (it.conflicts.isNotEmpty()) "CONFLICT" else it.checkState, it.checkState == "VERIFIED")
                    val needsRetry = it.waitingToPublish || it.checkState == "PARTIAL"
                    retry = needsRetry && settings.retryAllowed()
                    settings.result(it.message + if (needsRetry && !retry) " · 自动重试已暂停" else "",
                        it.conflicts.isEmpty() && it.checkState == "VERIFIED")
                }
            }
        } catch (error: Exception) {
            outcome = error.syncFailureResult()
            // Diagnostic storage can fail too. The scheduler and UI still need completion.
            runCatching {
                val settings = SharedScheduleSettings(this)
                retry = settings.retryAllowed()
                settings.recordCheck(checkScope, when (error) {
                    is ScheduleHistoryPending -> "DISCOVERING"
                    is java.net.UnknownHostException, is java.net.SocketTimeoutException -> "NETWORK"
                    is WebDavException -> if (error.reason.startsWith("Network")) "NETWORK" else "ERROR"
                    else -> "ERROR"
                })
                settings.result(if (retry) "离线 / 同步失败，等待重试；本地修改已保留" else "同步失败；自动重试已暂停，请立即同步检查。所有本地修改已保留")
            }
        } finally {
            val closed = runCatching { openedStore?.close() }
            val reloaded = runCatching { SharedScheduleViews.reloaded() }
            val result = if (closed.isSuccess && reloaded.isSuccess) outcome else SharedSyncResult.FAILED
            mainExecutor.execute { finish(params, invocation, result, retry) }
        }
    }

    private fun finish(params: JobParameters, invocation: Invocation, result: SharedSyncResult?, retry: Boolean) {
        val current = jobs[params.jobId] === invocation
        if (current) jobs.remove(params.jobId)
        try {
            if (current) jobFinished(params, retry)
        } finally {
            ScheduleSyncJobs.runs.finished(invocation.id, if (current) result else null)
        }
    }
    override fun onStopJob(params: JobParameters): Boolean {
        jobs.remove(params.jobId)?.stopped = true
        return true
    }
    override fun onDestroy() {
        jobs.values.forEach { it.stopped = true }
        jobs.clear()
        worker.shutdown()
        super.onDestroy()
    }
}
