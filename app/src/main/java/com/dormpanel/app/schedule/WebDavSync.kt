package com.dormpanel.app.schedule

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.dormpanel.app.ha.TokenCipher
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Process-owned scheduler; source commits and callbacks return through the main thread. */
class WebDavSyncController(private val context: Context, private val source: ScheduleSource,
    val settings: WebDavSettings = WebDavSettings(context.applicationContext), private val client: WebDavClient = WebDavClient(),
    private val profiles: TermScheduleProfileStore = TermScheduleProfileStore(context),
    private val automaticIntervalMillis: Long = INTERVAL.toLong()) {
    companion object { const val INTERVAL = 60 * 60 * 1000L }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var closed = false
    private var started = false
    private val active = mutableSetOf<String>()
    // Runtime scheduling only: never rewrite lastAttempt merely to postpone an overdue update.
    private val deferredAutomatic = mutableMapOf<String, Long>()
    private val listeners = linkedSetOf<() -> Unit>()
    private val sourceListener: () -> Unit = {
        if (!canReadLocal()) main.removeCallbacks(tick)
        else {
            if (source.retryingLocalRead) {
                val now = System.currentTimeMillis()
                settings.bindings().filter { it.autoSync && it.lastAttempt + automaticIntervalMillis <= now }
                    .forEach { deferredAutomatic[it.sourceId] = now + automaticIntervalMillis }
            } else cleanup()
            if (!started) { started = true }
            schedule()
        }
    }
    private val tick = Runnable { if (!closed) { due(); schedule() } }
    init { require(automaticIntervalMillis > 0); source.subscribe(sourceListener); if (source.ready) sourceListener() }
    fun observe(listener: () -> Unit) { listeners += listener }
    fun unobserve(listener: () -> Unit) { listeners -= listener }
    private fun notifyChanged() { listeners.toList().forEach { it() } }
    fun binding(id: String) = settings.bindings().firstOrNull { it.sourceId == id }
    fun isSyncing(id: String) = id in active
    fun bind(value: WebDavBinding) { if (closed) return; deferredAutomatic.remove(value.sourceId); update(value); schedule() }
    fun remove(id: String) {
        if (closed) return
        deferredAutomatic.remove(id)
        settings.saveBindings(settings.bindings().filterNot { it.sourceId == id })
        notifyChanged(); schedule()
    }
    fun clearBindingsForAccountChange() {
        if (closed) return
        deferredAutomatic.clear()
        settings.saveBindings(emptyList())
        notifyChanged(); schedule()
    }
    private fun update(value: WebDavBinding) {
        settings.saveBindings(settings.bindings().filterNot { it.sourceId == value.sourceId } + value)
        notifyChanged()
    }
    private fun cleanup() {
        val valid = source.state.sources.map { it.id }.toSet()
        deferredAutomatic.keys.retainAll(valid)
        val old = settings.bindings()
        if (old.any { it.sourceId !in valid }) {
            settings.saveBindings(old.filter { it.sourceId in valid }); notifyChanged()
        }
    }
    private fun due() {
        if (!canReadLocal() || source.retryingLocalRead) return
        val now = System.currentTimeMillis()
        settings.bindings().filter { it.autoSync && nextAutomatic(it) <= now }.forEach { sync(it.sourceId) }
    }
    private fun canReadLocal() = !closed && source.ready && !source.error && !source.applyingSnapshot
    private fun nextAutomatic(binding: WebDavBinding) = maxOf(binding.lastAttempt + automaticIntervalMillis, deferredAutomatic[binding.sourceId] ?: 0L)
    private fun schedule() {
        main.removeCallbacks(tick)
        if (!canReadLocal()) return
        val now = System.currentTimeMillis()
        val next = settings.bindings().filter { it.autoSync && it.sourceId !in active }
            .minOfOrNull { (nextAutomatic(it) - now).coerceAtLeast(0) } ?: return
        main.postDelayed(tick, next)
    }
    fun <T> background(action: () -> T, callback: (Result<T>) -> Unit) {
        if (closed) return
        worker.execute { val result = runCatching(action); main.post { if (!closed) callback(result) } }
    }
    fun test(account: WebDavAccount, callback: (Result<Unit>) -> Unit) = background({ client.list(account, account.baseUrl, 0); Unit }, callback)
    fun browse(account: WebDavAccount, folder: String, callback: (Result<List<WebDavItem>>) -> Unit) =
        background({ WebDavSelection.children(folder, client.list(account, folder)) }, callback)
    fun initial(account: WebDavAccount, mode: WebDavMode, remote: String,
        callback: (Result<Pair<TimetableImporter.ParseOutcome, WebDavItem>>) -> Unit) = background({
            val item = when (mode) {
                WebDavMode.FILE -> WebDavItem(remote, client.filename(client.url(remote)), false, null, null, null, null, null)
                WebDavMode.FOLDER_LATEST_XLS -> WebDavSelection.latest(client.list(account, remote), "xls")
                WebDavMode.FOLDER_LATEST_CSV -> WebDavSelection.latest(client.list(account, remote), "csv")
                else -> WebDavSelection.latest(client.list(account, remote), "ics")
            }
            val download = client.download(account, item.url)
            val outcome = TimetableImporter(profiles = profiles).parseOutcome(
                download.artifact ?: throw WebDavException("Remote file was not downloaded."))
            Pair(outcome, item.copy(etag = download.etag ?: item.etag, lastModified = download.lastModified ?: item.lastModified))
        }, callback)
    fun sync(id: String, callback: ((Result<Boolean>) -> Unit)? = null) {
        val binding = binding(id)
        if (!canReadLocal() || source.retryingLocalRead || binding == null) {
            callback?.invoke(Result.failure(WebDavException("Timetable source is unavailable."))); return
        }
        if (!active.add(id)) {
            callback?.invoke(Result.failure(WebDavException("Sync already in progress."))); return
        }
        deferredAutomatic.remove(id)
        val account = settings.account()
        val attempt = System.currentTimeMillis()
        if (account == null) { fail(id, attempt, WebDavException("WebDAV credentials unavailable."), callback); return }
        update(binding.copy(lastAttempt = attempt))
        worker.execute {
            val result = runCatching {
                val selected = when (binding.mode) {
                    WebDavMode.FILE -> WebDavItem(binding.remote, client.filename(client.url(binding.remote)), false, null, null, null, null, null)
                    WebDavMode.FOLDER_LATEST_XLS -> WebDavSelection.latest(client.list(account, binding.remote), "xls")
                    WebDavMode.FOLDER_LATEST_CSV -> WebDavSelection.latest(client.list(account, binding.remote), "csv")
                    else -> WebDavSelection.latest(client.list(account, binding.remote), "ics")
                }
                val sameFile = selected.url == (binding.currentFile ?: binding.remote)
                val knownEtag = selected.etag ?: if (sameFile) binding.etag else null
                val knownDate = selected.lastModified ?: if (sameFile) binding.lastModified else null
                // CSV: profile changes must force re-import even when remote validators are unchanged.
                val profileStore = profiles
                val currentProfileFingerprint = binding.termKey?.let { term ->
                    profileStore.get(term)?.let { TimetableImporter.profileFingerprint(it) }
                }
                val profileChanged = binding.format in listOf("csv", "xls") &&
                    (binding.profileFingerprint == null || binding.profileFingerprint != currentProfileFingerprint)
                val folderLatestUnchanged = (binding.mode != WebDavMode.FILE) &&
                    sameFile && !profileChanged &&
                    (selected.etag != null && !selected.etag.orEmpty().startsWith("W/") && selected.etag == binding.etag)
                if (folderLatestUnchanged)
                    Triple(null, selected, Pair(knownEtag, knownDate))
                else {
                    val download = client.download(account, selected.url,
                        if (sameFile && !profileChanged) binding.etag else null,
                        if (sameFile && !profileChanged) binding.lastModified else null)
                    val artifact = download.artifact
                    val parsed = artifact?.let {
                        when (val outcome = TimetableImporter(profiles = profileStore).parseOutcome(it)) {
                            is TimetableImporter.ParseOutcome.Ready -> outcome.preview
                            is TimetableImporter.ParseOutcome.NeedsProfile ->
                                throw WebDavException("No term profile for ${outcome.structure.termKey}. Configure the term, then sync again.")
                        }
                    }
                    Triple(parsed, selected,
                        Pair(download.etag ?: if (artifact == null) knownEtag else selected.etag,
                            download.lastModified ?: if (artifact == null) knownDate else selected.lastModified))
                }
            }
            main.post {
                if (closed) return@post
                result.onFailure { fail(id, attempt, it, callback) }.onSuccess { (preview, selected, validators) ->
                    if (preview == null) { success(id, attempt, selected.url, validators, callback, false); return@onSuccess }
                    val target = source.state.sources.firstOrNull { it.id == id }
                    if (target == null) { fail(id, attempt, WebDavException("Timetable source was deleted."), callback); return@onSuccess }
                    source.import(preview, target.displayName, id) { committed ->
                        committed.onSuccess {
                            val isCsv = preview.formatLabel != null
                            val profilePrint = preview.termKey?.let { term ->
                                profiles.get(term)?.let { TimetableImporter.profileFingerprint(it) }
                            }
                            binding(id)?.let { current ->
                                update(current.copy(format = if (preview.formatLabel?.endsWith("XLS") == true) "xls" else if (isCsv) "csv" else "ics",
                                    termKey = preview.termKey, profileFingerprint = profilePrint))
                            }
                            success(id, attempt, selected.url, validators, callback, !it.unchanged)
                        }.onFailure { fail(id, attempt, it, callback) }
                    }
                }
            }
        }
        schedule()
    }
    private fun success(id: String, attempt: Long, file: String, validators: Pair<String?, String?>,
        callback: ((Result<Boolean>) -> Unit)?, changed: Boolean) {
        binding(id)?.let { update(it.copy(currentFile = file, etag = validators.first, lastModified = validators.second,
            lastSuccess = System.currentTimeMillis(), lastAttempt = attempt, lastError = null)) }
        active.remove(id); schedule(); callback?.invoke(Result.success(changed))
    }
    private fun fail(id: String, attempt: Long, error: Throwable, callback: ((Result<Boolean>) -> Unit)?) {
        val message = when (error) {
            is WebDavException, is ScheduleImportException -> error.message ?: "Sync failed."
            is java.io.IOException -> "Network unavailable or timed out."
            else -> "Invalid timetable file or storage error."
        }
        binding(id)?.let { update(it.copy(lastAttempt = attempt, lastError = message)) }
        active.remove(id); schedule(); callback?.invoke(Result.failure(WebDavException(message)))
    }
    fun close() { if (closed) return; closed = true; main.removeCallbacks(tick); source.unsubscribe(sourceListener)
        listeners.clear(); worker.shutdownNow() }
}
