package com.dormpanel.app.apps

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
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
class ApkInstallController(private val context: Context, private val installedApps: InstalledAppSource) {
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
    fun acceptIncoming(metadata: ApkMetadata, completion: (String) -> Unit) {
        candidate?.staged?.file?.delete()
        relayCompletion?.invoke("dismissed")
        candidate = metadata; relayCompletion = completion
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
        busy = true; error = null; installState = "Receiving APK…"; notifyChanged()
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
        candidate?.staged?.file?.delete(); candidate = null
        relayCompletion?.invoke("dismissed"); relayCompletion = null
        error = null; installState = ""; busy = false; notifyChanged()
    }

    fun install(): Boolean {
        val metadata = candidate ?: return false
        if (gate() != ApkInstallGate.READY) return false
        busy = true; installState = "Preparing Android installer…"; notifyChanged()
        val staged = metadata.staged
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
                            .putExtra("session_id", sessionId)
                        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                        val pending = PendingIntent.getBroadcast(context, sessionId, callback, flags)
                        ApkInstallResultReceiver.callback = { status, message -> result(status, message) }
                        session.commit(pending.intentSender)
                    }
                } catch (error: Exception) { installer.abandonSession(sessionId); throw error }
            }
            main.post { outcome.onFailure { result(PackageInstaller.STATUS_FAILURE, it.message ?: "Installer could not start.") }
                if (outcome.isSuccess && installState == "Preparing Android installer…") {
                    installState = "Waiting for Android confirmation…"; notifyChanged()
                } }
        }
        return true
    }

    private fun result(status: Int, message: String) {
        main.post {
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> { installState = "Confirm installation in Android"; notifyChanged() }
                PackageInstaller.STATUS_SUCCESS -> {
                    candidate?.staged?.file?.delete(); candidate = null
                    busy = false; installState = "Installed successfully"; relayCompletion?.invoke("installed"); relayCompletion = null
                    ApkInstallResultReceiver.callback = null
                    installedApps.refresh(); notifyChanged()
                }
                else -> {
                    candidate?.staged?.file?.delete(); candidate = null
                    busy = false; error = message; installState = "Installation failed"
                    relayCompletion?.invoke("install_failed"); relayCompletion = null; notifyChanged()
                    ApkInstallResultReceiver.callback = null
                }
            }
        }
    }
    fun close() { stopLan(); candidate?.staged?.file?.delete(); ApkInstallResultReceiver.callback = null
        worker.shutdownNow(); listeners.clear() }
}

class ApkInstallResultReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "com.dormpanel.app.APK_INSTALL_RESULT"
        @Volatile var callback: ((Int, String) -> Unit)? = null
    }
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirmation != null) try { context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (_: Exception) { callback?.invoke(PackageInstaller.STATUS_FAILURE, "Android installer is unavailable."); return }
            else { callback?.invoke(PackageInstaller.STATUS_FAILURE, "Android installer did not provide confirmation."); return }
        }
        callback?.invoke(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty())
    }
}
