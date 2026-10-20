package com.dormpanel.app.schedule

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.Executors

/** One coalesced, network-constrained job, no periodic polling or permanent service. */
object ScheduleSyncJobs {
    private const val ID = 3201
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

class ScheduleSyncJobService : JobService() {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var stopped = false
    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        worker.execute {
            var retry = false
            val store = ScheduleStores.create(this)
            try {
                val settings = SharedScheduleSettings(this)
                if (settings.folder.isNotBlank() && store is ScheduleSyncStore) {
                    val result = synchronized(SharedScheduleRunLock) {
                        val frame = store.syncFrame()
                        if (frame.conflict != null) null else if (frame.baseline != null || frame.pending != null || settings.publishedRevision != null || settings.pendingRevision != null) {
                            ScheduleSyncEngine(VisibleScheduleSyncStore(store), SharedScheduleTransport(),
                                requireNotNull(WebDavSettings(this).account()), settings.folder).synchronize(legacy = settings.legacy())
                        } else null
                    }
                    result?.let {
                        retry = it.message.startsWith("有待上传")
                        settings.result(it.message, it.conflicts.isEmpty() && !retry)
                    }
                }
            } catch (_: Exception) {
                val settings = SharedScheduleSettings(this)
                retry = settings.retryAllowed()
                settings.result(if (retry) "离线 / 同步失败，等待重试；本地修改已保留" else "同步失败；自动重试已暂停，请立即同步检查。所有本地修改已保留")
            } finally {
                store.close()
                SharedScheduleViews.updated()
                if (!stopped) jobFinished(params, retry)
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { stopped = true; return true }
    override fun onDestroy() { stopped = true; worker.shutdown(); super.onDestroy() }
}
