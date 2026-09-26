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

enum class WebDavMode { FILE, FOLDER_LATEST_ICS, FOLDER_LATEST_CSV }
data class WebDavBinding(val sourceId: String, val mode: WebDavMode, val remote: String,
    val autoSync: Boolean = false, val etag: String? = null, val lastModified: String? = null,
    val currentFile: String? = null, val lastSuccess: Long = 0, val lastAttempt: Long = 0,
    val lastError: String? = null,
    /** CSV-only: detected term key, last-used profile fingerprint, and format label. */
    val format: String? = null, val termKey: String? = null, val profileFingerprint: String? = null)

/** Preferences carry only non-secret state. The password is AES-GCM encrypted with an Android Keystore key. */
class WebDavSettings(context: Context) {
    companion object { @Volatile var overrideNamespace: String? = null }
    private val namespace = overrideNamespace ?: "webdav"
    private val prefs = context.getSharedPreferences("${namespace}_settings", Context.MODE_PRIVATE)
    private val secrets = context.getSharedPreferences("${namespace}_credentials", Context.MODE_PRIVATE)
    private val alias = "DormPanel.WebDAV.Password.$namespace.v1"
    private val cipher = TokenCipher {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false).build())
            generateKey()
        }
    }
    fun account(): WebDavAccount? {
        val base = prefs.getString("url", null) ?: return null
        val password = secrets.getString("payload", null)?.let(cipher::decrypt) ?: return null
        return WebDavAccount(base, prefs.getString("username", "").orEmpty(), password)
    }
    fun publicAccount(): Pair<String, String> = Pair(prefs.getString("url", "").orEmpty(), prefs.getString("username", "").orEmpty())
    fun saveAccount(account: WebDavAccount) {
        WebDavClient().url(account.baseUrl)
        secrets.edit().putString("payload", cipher.encrypt(account.password)).apply()
        prefs.edit().putString("url", account.baseUrl.trim()).putString("username", account.username).apply()
    }
    fun clearAccount() {
        prefs.edit().remove("url").remove("username").apply()
        secrets.edit().clear().apply()
    }
    fun bindings(): List<WebDavBinding> = runCatching {
        val array = JSONArray(prefs.getString("bindings", "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            WebDavBinding(item.getString("sourceId"), WebDavMode.valueOf(item.getString("mode")), item.getString("remote"),
                item.optBoolean("autoSync"), item.takeUnless { it.isNull("etag") }?.optString("etag")?.ifBlank { null },
                item.takeUnless { it.isNull("lastModified") }?.optString("lastModified")?.ifBlank { null },
                item.takeUnless { it.isNull("currentFile") }?.optString("currentFile")?.ifBlank { null },
                item.optLong("lastSuccess"), item.optLong("lastAttempt"),
                item.takeUnless { it.isNull("lastError") }?.optString("lastError")?.ifBlank { null },
                item.takeUnless { it.isNull("format") }?.optString("format")?.ifBlank { null },
                item.takeUnless { it.isNull("termKey") }?.optString("termKey")?.ifBlank { null },
                item.takeUnless { it.isNull("profileFingerprint") }?.optString("profileFingerprint")?.ifBlank { null })
        }
    }.getOrDefault(emptyList())
    fun saveBindings(bindings: List<WebDavBinding>) {
        val array = JSONArray()
        bindings.forEach { item -> array.put(JSONObject().put("sourceId", item.sourceId).put("mode", item.mode.name)
            .put("remote", item.remote).put("autoSync", item.autoSync).put("etag", item.etag)
            .put("lastModified", item.lastModified).put("currentFile", item.currentFile)
            .put("lastSuccess", item.lastSuccess).put("lastAttempt", item.lastAttempt).put("lastError", item.lastError)
            .put("format", item.format).put("termKey", item.termKey).put("profileFingerprint", item.profileFingerprint)) }
        prefs.edit().putString("bindings", array.toString()).apply()
    }
    // Account lifetime is independent of timetable bindings. Preserve the existing
    // preference names and Keystore alias so upgrades retain saved credentials.
}

/** Process-owned scheduler; source commits and callbacks return through the main thread. */
class WebDavSyncController(private val context: Context, private val source: ScheduleSource,
    val settings: WebDavSettings = WebDavSettings(context.applicationContext), private val client: WebDavClient = WebDavClient()) {
    companion object { const val INTERVAL = 60 * 60 * 1000L }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var closed = false
    private var started = false
    private val active = mutableSetOf<String>()
    private val listeners = linkedSetOf<() -> Unit>()
    private val sourceListener: () -> Unit = { if (source.ready) {
        cleanup()
        if (!started) { started = true }
        schedule()
    } }
    private val tick = Runnable { if (!closed) { due(); schedule() } }
    init { source.subscribe(sourceListener); if (source.ready) sourceListener() }
    fun observe(listener: () -> Unit) { listeners += listener }
    fun unobserve(listener: () -> Unit) { listeners -= listener }
    private fun notifyChanged() { listeners.toList().forEach { it() } }
    fun binding(id: String) = settings.bindings().firstOrNull { it.sourceId == id }
    fun isSyncing(id: String) = id in active
    fun bind(value: WebDavBinding) { if (closed) return; update(value); schedule() }
    fun remove(id: String) {
        if (closed) return
        settings.saveBindings(settings.bindings().filterNot { it.sourceId == id })
        notifyChanged(); schedule()
    }
    fun clearBindingsForAccountChange() {
        if (closed) return
        settings.saveBindings(emptyList())
        notifyChanged(); schedule()
    }
    private fun update(value: WebDavBinding) {
        settings.saveBindings(settings.bindings().filterNot { it.sourceId == value.sourceId } + value)
        notifyChanged()
    }
    private fun cleanup() {
        val valid = source.state.sources.map { it.id }.toSet()
        val old = settings.bindings()
        if (old.any { it.sourceId !in valid }) {
            settings.saveBindings(old.filter { it.sourceId in valid }); notifyChanged()
        }
    }
    private fun due() {
        val now = System.currentTimeMillis()
        settings.bindings().filter { it.autoSync && now - it.lastAttempt >= INTERVAL }.forEach { sync(it.sourceId) }
    }
    private fun schedule() {
        main.removeCallbacks(tick)
        if (closed || !source.ready) return
        val now = System.currentTimeMillis()
        val next = settings.bindings().filter { it.autoSync && it.sourceId !in active }
            .minOfOrNull { (it.lastAttempt + INTERVAL - now).coerceAtLeast(0) } ?: return
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
        callback: (Result<Pair<ImportPreview, WebDavItem>>) -> Unit) = background({
            val item = when (mode) {
                WebDavMode.FILE -> WebDavItem(remote, client.filename(client.url(remote)), false, null, null, null, null, null)
                WebDavMode.FOLDER_LATEST_CSV -> WebDavSelection.latest(client.list(account, remote), "csv")
                else -> WebDavSelection.latest(client.list(account, remote), "ics")
            }
            val download = client.download(account, item.url)
            Pair(TimetableImporter(profiles = TermScheduleProfileStore(context)).parse(
                download.artifact ?: throw WebDavException("Remote file was not downloaded.")),
                item.copy(etag = download.etag ?: item.etag, lastModified = download.lastModified ?: item.lastModified))
        }, callback)
    fun sync(id: String, callback: ((Result<Boolean>) -> Unit)? = null) {
        val binding = binding(id)
        if (closed || binding == null || !source.ready) {
            callback?.invoke(Result.failure(WebDavException("Timetable source is unavailable."))); return
        }
        if (!active.add(id)) {
            callback?.invoke(Result.failure(WebDavException("Sync already in progress."))); return
        }
        val account = settings.account()
        val attempt = System.currentTimeMillis()
        if (account == null) { fail(id, attempt, WebDavException("WebDAV credentials unavailable."), callback); return }
        update(binding.copy(lastAttempt = attempt))
        worker.execute {
            val result = runCatching {
                val selected = when (binding.mode) {
                    WebDavMode.FILE -> WebDavItem(binding.remote, client.filename(client.url(binding.remote)), false, null, null, null, null, null)
                    WebDavMode.FOLDER_LATEST_CSV -> WebDavSelection.latest(client.list(account, binding.remote), "csv")
                    else -> WebDavSelection.latest(client.list(account, binding.remote), "ics")
                }
                val sameFile = selected.url == (binding.currentFile ?: binding.remote)
                val knownEtag = selected.etag ?: if (sameFile) binding.etag else null
                val knownDate = selected.lastModified ?: if (sameFile) binding.lastModified else null
                // CSV: profile changes must force re-import even when remote validators are unchanged.
                val profileStore = TermScheduleProfileStore(context)
                val currentProfileFingerprint = binding.termKey?.let { term ->
                    profileStore.get(term)?.let { TimetableImporter.profileFingerprint(it) }
                }
                val profileChanged = binding.format == "csv" &&
                    (binding.profileFingerprint == null || binding.profileFingerprint != currentProfileFingerprint)
                val folderLatestUnchanged = (binding.mode == WebDavMode.FOLDER_LATEST_ICS || binding.mode == WebDavMode.FOLDER_LATEST_CSV) &&
                    sameFile && !profileChanged &&
                    (selected.etag != null && !selected.etag.startsWith("W/") && selected.etag == binding.etag)
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
                                TermScheduleProfileStore(context).get(term)?.let { TimetableImporter.profileFingerprint(it) }
                            }
                            binding(id)?.let { current ->
                                update(current.copy(format = if (isCsv) "csv" else "ics",
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
