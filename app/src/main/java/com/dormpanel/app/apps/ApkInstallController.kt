package com.dormpanel.app.apps

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.InputStream
import java.util.concurrent.Executors

enum class ApkInstallGate { NO_APK, WAIT, SIGNATURE_CONFLICT, ALLOW_UNKNOWN_SOURCES, READY }
object ApkInstallPolicy {
    fun gate(hasApk: Boolean, busy: Boolean, signatureMatches: Boolean?, permission: Boolean): ApkInstallGate = when {
        !hasApk -> ApkInstallGate.NO_APK
        busy -> ApkInstallGate.WAIT
        signatureMatches == false -> ApkInstallGate.SIGNATURE_CONFLICT
        !permission -> ApkInstallGate.ALLOW_UNKNOWN_SOURCES
        else -> ApkInstallGate.READY
    }
}

/** ViewModel-owned candidate and one worker. Views only observe and request actions. */
class ApkInstallController(
    private val context: Context,
    private val installedApps: InstalledAppSource,
    private val pendingStore: PendingInstallStore = PreferencesPendingInstallStore(context),
) {
    private val stage = ApkStaging(context)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    var candidate: ApkMetadata? = null; private set
    var busy = false; private set
    var error: String? = null; private set
    var installState = ""; private set
    private val listeners = linkedSetOf<() -> Unit>()
    private var relayCompletion: ((String) -> Unit)? = null
    private var relayTransferId: String? = null
    private var lanServer: TemporaryApkUploadServer? = null
    val lanUrl: String? get() = lanServer?.url
    val lanExpiresAt: Long get() = lanServer?.expiresAt ?: 0

    fun observe(listener: () -> Unit) { listeners += listener; listener() }
    fun unobserve(listener: () -> Unit) { listeners -= listener }
    private fun notifyChanged() = listeners.toList().forEach { it() }
    fun allowed(): Boolean = context.packageManager.canRequestPackageInstalls()
    fun gate(): ApkInstallGate = ApkInstallPolicy.gate(candidate != null, busy, candidate?.signatureMatches, allowed())
    fun refreshPermission() = notifyChanged()
    fun startLan(): String {
        stopLan()
        lateinit var created: TemporaryApkUploadServer
        created = TemporaryApkUploadServer.start(java.io.File(context.cacheDir, "apk-staging"), { input, size ->
            val metadata = runCatching { stageIncoming(input, "upload.apk", "LAN web upload", size) }.getOrNull()
                ?: return@start false
            main.post { if (lanServer === created) { lanServer = null; acceptIncoming(metadata) {} }
                else metadata.staged.file.delete() }
            true
        }, { main.post { if (lanServer === created) { lanServer = null; notifyChanged() } } })
        lanServer = created; notifyChanged(); return created.url
    }
    fun stopLan() { lanServer?.close(); lanServer = null; notifyChanged() }
    fun stageIncoming(input: InputStream, filename: String, source: String, size: Long): ApkMetadata =
        stage.validate(stage.stage(input, filename, source, size))
    fun acceptIncoming(metadata: ApkMetadata, haTransferId: String? = null, completion: (String) -> Unit) {
        candidate?.staged?.file?.delete()
        relayCompletion?.invoke("dismissed")
        candidate = metadata; relayCompletion = completion; relayTransferId = haTransferId
        installState = "APK received and validated"; error = null; notifyChanged()
    }

    fun receive(filename: String, source: String, length: Long = -1,
        input: () -> InputStream, onReady: (() -> Unit)? = null, onFailure: ((Throwable) -> Unit)? = null,
        completion: ((String) -> Unit)? = null) {
        receiveSource(onReady, onFailure, completion) { staging ->
            input().use { staging.validate(staging.stage(it, filename, source, length)) }
        }
    }

    fun receiveSource(onReady: (() -> Unit)? = null, onFailure: ((Throwable) -> Unit)? = null,
        completion: ((String) -> Unit)? = null, produce: (ApkStaging) -> ApkMetadata) {
        if (busy) { onFailure?.invoke(ApkRejected("Another APK is being received.")); return }
        val current = ++generation
        busy = true; error = null; installState = "Receiving APK"; notifyChanged()
        worker.execute {
            val result = runCatching { produce(stage) }
            main.post {
                if (current != generation) { result.getOrNull()?.staged?.file?.delete(); return@post }
                busy = false
                result.onSuccess {
                    candidate?.staged?.file?.delete()
                    relayCompletion?.invoke("dismissed")
                    candidate = it; relayCompletion = completion
                    installState = "APK received and validated"; onReady?.invoke()
                }.onFailure {
                    error = it.message ?: "APK could not be received."
                    installState = ""; onFailure?.invoke(it)
                }
                notifyChanged()
            }
        }
    }

    fun dismiss() {
        generation++
        val pending = pendingStore.read()
        val committed = pending?.phase == PendingInstallPhase.COMMITTED ||
            pending?.phase == PendingInstallPhase.AWAITING_USER
        if (!committed) {
            candidate?.staged?.file?.delete()
            pending?.stagedPath?.let { path -> runCatching { java.io.File(path).takeIf { it.exists() }?.delete() } }
            if (pending != null) pendingStore.clear()
        }
        candidate = null
        relayCompletion?.invoke("dismissed"); relayCompletion = null; relayTransferId = null
        error = null; installState = ""; busy = false; notifyChanged()
    }

    fun install(): Boolean {
        val metadata = candidate ?: return false
        if (gate() != ApkInstallGate.READY) return false
        busy = true; installState = "Preparing Android installer"; notifyChanged()
        val staged = metadata.staged
        val transferId = relayTransferId
        worker.execute {
            val outcome = runCatching {
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply { setAppPackageName(metadata.packageName); setSize(staged.size) }
                val sessionId = installer.createSession(params)
                try {
                    installer.openSession(sessionId).use { session ->
                        staged.file.inputStream().use { source ->
                            session.openWrite("base.apk", 0, staged.size).use { target ->
                                source.copyTo(target, 32 * 1024); session.fsync(target)
                            }
                        }
                        val callback = Intent(context, ApkInstallResultReceiver::class.java)
                            .setAction(ApkInstallResultReceiver.ACTION)
                            .putExtra(PendingInstallResultContract.EXTRA_SESSION_ID, sessionId)
                        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                        val pending = PendingIntent.getBroadcast(context, sessionId, callback, flags)
                        // Persist before commit so a process death during the Android
                        // confirmation UI can still be reconciled later.
                        pendingStore.write(PendingInstallRecord(
                            sessionId = sessionId,
                            packageName = metadata.packageName,
                            stagedPath = staged.file.absolutePath,
                            stagedSha256 = staged.sha256,
                            candidateVersionCode = metadata.versionCode,
                            sourceKind = sourceKindOf(staged.source),
                            haTransferId = transferId,
                            phase = PendingInstallPhase.COMMITTED,
                            message = "Waiting for Android confirmation",
                        ))
                        ApkInstallResultReceiver.callback = { status, message -> result(status, message) }
                        session.commit(pending.intentSender)
                    }
                } catch (error: Exception) {
                    installer.abandonSession(sessionId)
                    pendingStore.clear()
                    throw error
                }
            }
            main.post {
                outcome.onFailure {
                    result(PackageInstaller.STATUS_FAILURE, it.message ?: "Installer could not start.")
                }
                if (outcome.isSuccess && installState == "Preparing Android installer") {
                    installState = "Waiting for Android confirmation"; notifyChanged()
                }
            }
        }
        return true
    }

    private fun sourceKindOf(source: String): String = when {
        source.contains("Home Assistant", true) -> InstallRecoveryLogic.SOURCE_HA
        source.contains("WebDAV", true) -> InstallRecoveryLogic.SOURCE_WEBDAV
        source.contains("LAN", true) -> InstallRecoveryLogic.SOURCE_LAN
        else -> InstallRecoveryLogic.SOURCE_LOCAL
    }

    /**
     * Reconcile a pending install after process or Activity recreation.
     * Returns outcomes that still need a Home Assistant terminal ack.
     */
    fun reconcileRecovered(): List<RecoveredInstallOutcome> {
        val record = pendingStore.read() ?: return emptyList()
        val facts = PlatformInstallFacts(
            broadcastStatus = null,
            sessionStillExists = sessionExists(record.sessionId),
            installedVersionCode = installedVersionCode(record.packageName),
        )
        val recovery = InstallRecoveryLogic.recover(record, facts)
        return applyRecovery(recovery, fromReceiver = false)
    }

    private fun sessionExists(sessionId: Int): Boolean = runCatching {
        context.packageManager.packageInstaller.getSessionInfo(sessionId) != null
    }.getOrDefault(false)

    private fun installedVersionCode(packageName: String): Long? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(packageName, 0).longVersionCode
    }.getOrNull()

    private fun result(status: Int, message: String) {
        main.post {
            val record = pendingStore.read()
            val recovery = if (record != null) {
                InstallRecoveryLogic.recover(record, PlatformInstallFacts(
                    broadcastStatus = status, broadcastMessage = message,
                    sessionStillExists = false,
                    installedVersionCode = if (status == PackageInstaller.STATUS_SUCCESS) record.candidateVersionCode else null,
                ))
            } else null
            when {
                recovery != null -> applyRecovery(recovery, fromReceiver = true)
                status == PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    installState = "Confirm installation in Android"; notifyChanged()
                }
                status == PackageInstaller.STATUS_SUCCESS -> {
                    candidate?.staged?.file?.delete(); candidate = null
                    busy = false; installState = "Installed successfully"
                    relayCompletion?.invoke("installed"); relayCompletion = null; relayTransferId = null
                    ApkInstallResultReceiver.callback = null
                    installedApps.refresh(); notifyChanged()
                }
                else -> {
                    candidate?.staged?.file?.delete(); candidate = null
                    busy = false; error = message; installState = "Installation failed"
                    val outcome = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "dismissed" else "install_failed"
                    relayCompletion?.invoke(outcome); relayCompletion = null; relayTransferId = null
                    ApkInstallResultReceiver.callback = null
                    notifyChanged()
                }
            }
        }
    }

    private fun applyRecovery(recovery: InstallRecovery, fromReceiver: Boolean): List<RecoveredInstallOutcome> {
        val record = recovery.record
        when (recovery) {
            is InstallRecovery.StillPending -> {
                pendingStore.write(record)
                installState = if (record.phase == PendingInstallPhase.AWAITING_USER)
                    "Confirm installation in Android" else "Waiting for Android confirmation"
                busy = true; notifyChanged()
                return emptyList()
            }
            is InstallRecovery.Installed -> {
                finishTerminal(record, "Installed successfully", error = null)
                val outcome = InstallRecoveryLogic.haOutcome(recovery)!!
                val transferId = record.haTransferId
                val live = relayCompletion
                if (fromReceiver && live != null) {
                    live.invoke(outcome); relayCompletion = null; relayTransferId = null
                    return emptyList()
                }
                relayCompletion = null; relayTransferId = null
                return if (transferId != null) {
                    listOf(RecoveredInstallOutcome(record.packageName, outcome, record.message, transferId))
                } else emptyList()
            }
            is InstallRecovery.Failed, is InstallRecovery.Dismissed -> {
                val dismissed = recovery is InstallRecovery.Dismissed
                finishTerminal(record, if (dismissed) "Installation cancelled" else "Installation failed",
                    error = if (dismissed) null else record.message.ifBlank { "Installation failed." })
                val outcome = InstallRecoveryLogic.haOutcome(recovery)!!
                val transferId = record.haTransferId
                val live = relayCompletion
                if (fromReceiver && live != null) {
                    live.invoke(outcome); relayCompletion = null; relayTransferId = null
                    return emptyList()
                }
                relayCompletion = null; relayTransferId = null
                return if (transferId != null) {
                    listOf(RecoveredInstallOutcome(record.packageName, outcome, record.message, transferId))
                } else emptyList()
            }
            is InstallRecovery.Unresolved -> {
                pendingStore.write(record)
                busy = false
                error = record.message.ifBlank { "Installation result is unknown." }
                installState = "Installation result unknown"
                // Do not invent a terminal HA ack; prefer rediscovery / re-preview.
                relayCompletion = null; relayTransferId = null
                candidate?.staged?.file?.delete(); candidate = null
                notifyChanged()
                return emptyList()
            }
        }
    }

    private fun finishTerminal(record: PendingInstallRecord, state: String, error: String?) {
        runCatching { java.io.File(record.stagedPath).takeIf { it.exists() }?.delete() }
        candidate?.staged?.file?.delete(); candidate = null
        pendingStore.clear()
        ApkInstallResultReceiver.callback = null
        busy = false
        installState = state
        this.error = error
        installedApps.refresh()
        notifyChanged()
    }

    /**
     * Release process-owned resources. An in-flight PackageInstaller session is
     * intentionally left intact so a later process can reconcile it.
     */
    fun close() {
        stopLan()
        val pending = pendingStore.read()
        val inFlight = pending?.phase == PendingInstallPhase.COMMITTED ||
            pending?.phase == PendingInstallPhase.AWAITING_USER ||
            pending?.phase == PendingInstallPhase.STAGED
        if (!inFlight) {
            candidate?.staged?.file?.delete()
        }
        candidate = null
        ApkInstallResultReceiver.callback = null
        worker.shutdownNow(); listeners.clear()
    }
}

/** Keys shared by the receiver and the controller without a live callback. */
object PendingInstallResultContract {
    const val EXTRA_SESSION_ID = "session_id"
}

class ApkInstallResultReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "com.dormpanel.app.APK_INSTALL_RESULT"
        @Volatile var callback: ((Int, String) -> Unit)? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        val store = PreferencesPendingInstallStore(context)
        val record = store.read()
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            record?.let { store.write(it.copy(phase = PendingInstallPhase.AWAITING_USER, message = message)) }
            @Suppress("DEPRECATION")
            val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirmation != null) {
                try {
                    context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                    val failed = record?.copy(phase = PendingInstallPhase.INSTALL_FAILED, message = "Android installer is unavailable.")
                        ?: return
                    store.write(failed)
                    callback?.invoke(PackageInstaller.STATUS_FAILURE, failed.message)
                }
            } else {
                val failed = record?.copy(phase = PendingInstallPhase.INSTALL_FAILED, message = "Android installer did not provide confirmation.")
                    ?: return
                store.write(failed)
                callback?.invoke(PackageInstaller.STATUS_FAILURE, failed.message)
            }
            return
        }
        // Persist the terminal fact before any in-process callback so a crash
        // between the two still leaves a recoverable record.
        if (record != null) {
            val phase = when {
                status == PackageInstaller.STATUS_SUCCESS -> PendingInstallPhase.INSTALLED
                status == PackageInstaller.STATUS_FAILURE_ABORTED -> PendingInstallPhase.DISMISSED
                status == -1 -> PendingInstallPhase.AWAITING_USER
                else -> PendingInstallPhase.INSTALL_FAILED
            }
            store.write(record.copy(phase = phase, message = message))
        }
        callback?.invoke(status, message)
            ?: run {
                // Fresh process after the original controller died: the record
                // alone is the recovery source. Do not invent additional state.
            }
    }
}
