package com.dormpanel.app.schedule

import com.dormpanel.app.ha.HaRelayChannel
import com.dormpanel.app.ha.HaRelayIdentityStore
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.Executors
import com.dormpanel.app.apps.ApkInstallController
import com.dormpanel.app.apps.ApkMetadata
import com.dormpanel.app.apps.ApkStaging

data class RelayPreview(val transferId: String, val preview: ImportPreview)
class HaRelayIntegrityException(message: String) : IOException(message)

/** Signed paths never escape the configured HA origin, even through redirects. */
class HaRelayDownloader(http: OkHttpClient) {
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    fun url(origin: String, signedPath: String): okhttp3.HttpUrl {
        require(signedPath.startsWith("/api/dormpanel/transfers/") && !signedPath.startsWith("//") &&
            '\\' !in signedPath && '#' !in signedPath && ".." !in signedPath &&
            !signedPath.contains("%2f", true) && !signedPath.contains("%5c", true)) { "Invalid signed path" }
        val base = origin.toHttpUrl()
        val resolved = base.newBuilder().encodedPath("/").build().resolve(signedPath)
            ?: throw IllegalArgumentException("Invalid signed path")
        require(resolved.scheme == base.scheme && resolved.host == base.host && resolved.port == base.port &&
            resolved.username.isEmpty() && resolved.password.isEmpty() &&
            resolved.encodedPath.startsWith("/api/dormpanel/transfers/") &&
            resolved.queryParameterNames == setOf("authSig") && resolved.queryParameter("authSig") != null) {
            "Signed path must remain on Home Assistant"
        }
        return resolved
    }

    fun download(origin: String, claim: JSONObject): ScheduleArtifact {
        val transferId = claim.getString("transfer_id")
        require(Regex("[0-9a-f]{32}").matches(transferId))
        val size = claim.getInt("size")
        require(size in 1..ImportLimits.BYTES)
        val path = url(origin, claim.getString("signed_path"))
        val request = Request.Builder().url(path).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HA download HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty HA response")
            if (body.contentLength() > ImportLimits.BYTES) throw HaRelayIntegrityException("HA file exceeds size limit")
            val artifact = ScheduleArtifact.read(claim.getString("filename"), body.byteStream(),
                "text/calendar", "ha_relay", "ha_relay:$transferId")
            if (artifact.bytes().size != size || !digest(artifact.bytes()).equals(claim.getString("sha256"), true))
                throw HaRelayIntegrityException("HA transfer SHA-256 mismatch")
            return artifact
        }
    }
    fun downloadApk(origin: String, claim: JSONObject, apk: ApkInstallController): ApkMetadata {
        val transferId = claim.getString("transfer_id")
        require(Regex("[0-9a-f]{32}").matches(transferId))
        val size = claim.getLong("size")
        require(size in 1..ApkStaging.LIMIT)
        val request = Request.Builder().url(url(origin, claim.getString("signed_path"))).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HA download HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty HA response")
            if (body.contentLength() > ApkStaging.LIMIT) throw HaRelayIntegrityException("HA APK exceeds limit")
            val metadata = body.byteStream().use { apk.stageIncoming(it, claim.getString("filename"), "Home Assistant", size) }
            if (metadata.staged.size != size || !metadata.staged.sha256.equals(claim.getString("sha256"), true)) {
                metadata.staged.file.delete()
                throw HaRelayIntegrityException("HA APK SHA-256 mismatch")
            }
            return metadata
        }
    }
}

/** Main-thread state machine; network and parsing run on one bounded worker. */
class HaScheduleRelayController(private val channel: HaRelayChannel, private val identity: HaRelayIdentityStore,
    http: OkHttpClient, private val status: (String) -> Unit = {}, private val apk: ApkInstallController? = null,
    private val appVersion: () -> String = { "unknown" }) {
    private val downloader = HaRelayDownloader(http)
    private val importer = IcsScheduleImporter()
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val queued = ArrayDeque<String>()
    private val known = mutableSetOf<String>()
    private val terminalAwaitingAck = mutableSetOf<String>()
    /**
     * Recovered APK terminal outcomes (transferId → outcome) that must be
     * acknowledged without downloading/re-previewing the APK. Ordered; may be
     * filled before registration and flushed afterwards.
     */
    private val recoveredTerminals = LinkedHashMap<String, String>()
    private var recoveredInFlight: String? = null
    /** True only after register() succeeds; recovered acks wait for this. */
    private var registered = false
    private val retries = mutableMapOf<String, Int>()
    private var downloading = false
    private var inFlightId: String? = null
    private var connectionGeneration = 0
    private var active: RelayPreview? = null
    private var activeApkId: String? = null
    private var presenter: ((RelayPreview) -> Unit)? = null
    private var closed = false
    private val readyListener: () -> Unit = { register() }
    private val eventListener: (JSONObject) -> Unit = { event ->
        val targets = event.optJSONArray("target_installation_ids")
        if (targets != null && (0 until targets.length()).any { targets.optString(it) == identity.installationId })
            event.optString("transfer_id").takeIf { Regex("[0-9a-f]{32}").matches(it) }?.let(::enqueue)
    }

    init { channel.addEventListener(eventListener); channel.addReadyListener(readyListener) }

    private fun message(type: String, transferId: String? = null, outcome: String? = null) =
        JSONObject().put("type", "dormpanel/$type").put("installation_id", identity.credentials().first)
            .put("device_secret", identity.credentials().second).apply {
                if (transferId != null) put("transfer_id", transferId)
                if (outcome != null) put("outcome", outcome)
            }

    private fun register() {
        if (closed || !channel.ready) return
        connectionGeneration++
        val generation = connectionGeneration
        registered = false
        // The socket discards outstanding callbacks when it disconnects. An
        // unconfirmed terminal ack must be offered again by list_pending.
        terminalAwaitingAck.forEach(known::remove)
        terminalAwaitingAck.clear()
        recoveredInFlight = null
        inFlightId?.let { queued.addFirst(it); inFlightId = null; downloading = false }
        val request = message("register").put("display_name", identity.displayName).put("app_version", appVersion())
            .put("capabilities", org.json.JSONArray().put("schedule_relay_v1").apply { if (apk != null) put("apk_install_v1") })
        if (!channel.request(request) { result ->
            if (closed || generation != connectionGeneration) return@request
            if (!result.optBoolean("success")) {
                status("Relay unavailable: administrator permission or DormPanel integration required")
                return@request
            }
            registered = true
            status("Relay ready")
            flushRecoveredTerminals(generation)
            next()
            channel.request(message("list_pending")) { pending ->
                if (!closed && generation == connectionGeneration && pending.optBoolean("success")) {
                    val entries = pending.optJSONArray("result") ?: return@request
                    for (index in 0 until entries.length()) entries.optJSONObject(index)?.optString("transfer_id")?.let(::enqueue)
                }
            }
        }) status("Relay unavailable: WebSocket is not ready")
    }

    private fun enqueue(id: String) {
        if (closed || !Regex("[0-9a-f]{32}").matches(id) || !known.add(id)) return
        // A recovered terminal already knows the outcome: ack it instead of
        // downloading and re-previewing the same APK again.
        if (recoveredTerminals.containsKey(id)) {
            flushRecoveredTerminals(connectionGeneration)
            return
        }
        queued.addLast(id)
        next()
    }

    private fun next() {
        if (closed || active != null || activeApkId != null || downloading || queued.isEmpty() || !channel.ready) return
        val id = queued.removeFirst()
        downloading = true
        inFlightId = id
        val generation = connectionGeneration
        if (!channel.request(message("claim_transfer", id)) { claim ->
            if (closed || generation != connectionGeneration) return@request
            if (!claim.optBoolean("success")) { failed(id); return@request }
            val payload = claim.optJSONObject("result") ?: run { failed(id); return@request }
            val origin = channel.origin
            val kind = payload.optString("kind", "schedule_ics")
            val apkController = apk
            if (kind == "apk" && apkController != null) {
                worker.execute {
                    val staged = runCatching { downloader.downloadApk(origin, payload, apkController) }
                    handler.post {
                        if (closed || generation != connectionGeneration) { staged.getOrNull()?.staged?.file?.delete(); return@post }
                        downloading = false; inFlightId = null
                        staged.onSuccess { metadata ->
                            activeApkId = id; retries.remove(id); acknowledge(id, "preview_ready")
                            apkController.acceptIncoming(metadata, haTransferId = id) { outcome -> resolve(id, outcome) }
                        }.onFailure { error ->
                            if (error is com.dormpanel.app.apps.ApkRejected || error is HaRelayIntegrityException)
                                acknowledge(id, "rejected_invalid")
                            status("APK relay failed: ${error.message ?: "retry after reconnect"}")
                            known.remove(id); next()
                        }
                    }
                }
                return@request
            }
            if (kind != "schedule_ics") { acknowledge(id, "rejected_invalid"); failed(id); return@request }
            worker.execute {
                val artifact = runCatching { downloader.download(origin, payload) }
                val parsed = artifact.mapCatching(importer::parse)
                handler.post {
                    if (closed || generation != connectionGeneration) return@post
                    downloading = false
                    inFlightId = null
                    parsed.onSuccess { preview ->
                        active = RelayPreview(id, preview)
                        retries.remove(id)
                        acknowledge(id, "preview_ready")
                        presenter?.invoke(active!!)
                    }.onFailure { error ->
                        if (artifact.isSuccess && error is ScheduleImportException) acknowledge(id, "rejected_invalid")
                        if (artifact.isFailure && error is IOException && error !is HaRelayIntegrityException &&
                            retries.getOrDefault(id, 0) < 1 && channel.ready) {
                            retries[id] = 1
                            queued.addFirst(id)
                            next()
                            return@post
                        }
                        status("Relay delivery failed: ${error.message ?: "retry after reconnect"}")
                        retries.remove(id)
                        known.remove(id)
                        next()
                    }
                }
            }
        }) failed(id)
    }

    private fun failed(id: String) {
        downloading = false
        inFlightId = null
        known.remove(id)
        next()
    }

    private fun acknowledge(id: String, outcome: String) {
        channel.request(message("ack_transfer", id, outcome)) { /* HA keeps pending on failed acknowledgement. */ }
    }

    /**
     * Queue a recovered APK terminal outcome from a previous process. Safe to
     * call before HA is connected/registered. Acknowledges after successful
     * registration without re-downloading the APK. A failed acknowledgement is
     * not treated as completion; HA rediscovery remains the recovery path.
     */
    fun queueRecoveredTerminal(transferId: String, outcome: String) {
        if (closed) return
        if (!Regex("[0-9a-f]{32}").matches(transferId)) return
        if (outcome !in setOf("installed", "install_failed", "dismissed")) return
        // Normal active-path resolve handles a live preview/transfer.
        if (activeApkId == transferId || active?.transferId == transferId) {
            resolve(transferId, outcome)
            return
        }
        recoveredTerminals[transferId] = outcome
        // Prevent a later list_pending/event from downloading this transfer again.
        known.add(transferId)
        flushRecoveredTerminals(connectionGeneration)
    }

    private fun flushRecoveredTerminals(generation: Int) {
        if (closed || recoveredInFlight != null) return
        if (!channel.ready || !registered) return
        if (recoveredTerminals.isEmpty()) return
        val entry = recoveredTerminals.entries.firstOrNull() ?: return
        val id = entry.key
        val outcome = entry.value
        recoveredInFlight = id
        terminalAwaitingAck += id
        val accepted = channel.request(message("ack_transfer", id, outcome)) { result ->
            if (closed || generation != connectionGeneration) {
                // Reconnect: keep the recovered terminal for the next register().
                if (recoveredInFlight == id) recoveredInFlight = null
                terminalAwaitingAck.remove(id)
                return@request
            }
            recoveredInFlight = null
            terminalAwaitingAck.remove(id)
            if (result.optBoolean("success")) {
                // Confirmed: drop the recovered terminal and keep the id known
                // so list_pending does not trigger a needless re-download.
                recoveredTerminals.remove(id)
                known.add(id)
            } else {
                // Not confirmed: retain the recovered terminal for retry and
                // allow conservative HA rediscovery.
                known.remove(id)
            }
        }
        if (!accepted) {
            recoveredInFlight = null
            terminalAwaitingAck.remove(id)
            known.remove(id)
        }
    }

    fun attach(presenter: (RelayPreview) -> Unit) {
        this.presenter = presenter
        active?.let(presenter)
    }
    fun detach() { presenter = null }
    fun resolve(transferId: String, outcome: String) {
        val scheduleDelivery = active?.transferId == transferId
        val apkDelivery = activeApkId == transferId
        if ((!scheduleDelivery && !apkDelivery) || outcome !in
            (if (apkDelivery) setOf("installed", "dismissed", "rejected_invalid", "install_failed") else setOf("imported", "dismissed"))) return
        // Keep the ID known while acknowledgement is uncertain. HA remains the
        // source of truth and will offer it again through list_pending on reconnect.
        terminalAwaitingAck += transferId
        val generation = connectionGeneration
        val accepted = channel.request(message("ack_transfer", transferId, outcome)) { result ->
            if (!closed && generation == connectionGeneration) {
                terminalAwaitingAck.remove(transferId)
                if (!result.optBoolean("success")) known.remove(transferId)
            }
        }
        if (!accepted) { terminalAwaitingAck.remove(transferId); known.remove(transferId) }
        active = null
        activeApkId = null
        retries.remove(transferId)
        handler.post { next() }
    }
    fun close() {
        if (closed) return
        closed = true; presenter = null
        channel.removeReadyListener(readyListener); channel.removeEventListener(eventListener)
        worker.shutdownNow()
    }
}
