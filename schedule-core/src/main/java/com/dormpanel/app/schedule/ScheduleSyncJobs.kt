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
                if (store is RoomScheduleStore && store.isV3()) {
                    val result = synchronized(SharedScheduleRunLock) { store.synchronizeV3(requireNotNull(WebDavSettings(this).account())) }
                    retry = result in listOf("BUDGET", "DISCOVERING", "ROTATED") && settings.retryAllowed()
                    settings.result(v3StatusMessage(result), false)
                    return@execute
                }
                if (settings.folder.isNotBlank() && store is ScheduleSyncStore) {
                    val result = synchronized(SharedScheduleRunLock) {
                        val local = store as ScheduleV2Store
                        if (local.v2DatasetPaused()) return@synchronized null
                        val frame = local.v2Frame()
                        if (frame.baseline == null && frame.pending == null) null else {
                            val account = requireNotNull(WebDavSettings(this).account())
                            val folder = WebDavClient().url(SharedScheduleTransport().folder(account, settings.folder)).newBuilder()
                                .addPathSegment("dormpanel-sync-v2").addPathSegment("").build().toString()
                            val visible = object : ScheduleV2Store by local {
                                override fun v2Apply(plan: ScheduleV2Plan) = SharedScheduleViews.changing { local.v2Apply(plan) }
                                override fun v2Receipt(bytes: ByteArray) = SharedScheduleViews.changing { local.v2Receipt(bytes) }
                            }
                            AppendOnlyScheduleSync(visible, WebDavCommitStore(account, folder)).synchronize()
                        }
                    }
                    result?.let {
                        val needsRetry = it.message.startsWith("有待上传") || it.message.contains("暂不可验证")
                        retry = needsRetry && settings.retryAllowed()
                        settings.result(it.message + if (needsRetry && !retry) " · 自动重试已暂停" else "",
                            it.conflicts.isEmpty() && it.message.startsWith("已检查观察到的版本"))
                    }
                }
            } catch (_: Exception) {
                val settings = SharedScheduleSettings(this)
                retry = settings.retryAllowed()
                settings.result(if (retry) "离线 / 同步失败，等待重试；本地修改已保留" else "同步失败；自动重试已暂停，请立即同步检查。所有本地修改已保留")
            } finally {
                store.close()
                SharedScheduleViews.reloaded()
                if (!stopped) jobFinished(params, retry)
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { stopped = true; return true }
    override fun onDestroy() { stopped = true; worker.shutdown(); super.onDestroy() }
}
