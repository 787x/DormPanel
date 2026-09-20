package com.dormpanel.app.ha

import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

fun haHttpClient(): OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).pingInterval(45, TimeUnit.SECONDS).build()

/** No entity state lives in REST; diagnostics only. Redirects never forward credentials elsewhere. */
class HaRestClient(private val http: OkHttpClient, private val scheduler: HaScheduler) {
    fun test(endpoint: HaEndpoint, token: String, callback: (String) -> Unit): Call {
        val call = http.newCall(Request.Builder().url(endpoint.api + "config").header("Authorization", "Bearer $token").build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { scheduler.execute { callback(networkError(e)) } }
            override fun onResponse(call: Call, response: Response) {
                val message = response.use { when { it.isSuccessful -> "REST authentication successful; Save/Reconnect to verify live WebSocket."
                    it.code == 401 -> "Authentication rejected. Re-enter the access token."; else -> "HTTP ${it.code}; check the base URL and server." } }
                scheduler.execute { callback(message) }
            }
        })
        return call
    }
}
internal fun networkError(error: Throwable): String = if (generateSequence(error) { it.cause }.any { it is javax.net.ssl.SSLException })
    "TLS verification failed. Use a valid certificate trusted by Android." else "Connection lost. Check HA URL, network and server."

/** Socket callbacks are serialized onto scheduler. Generation guards reject obsolete connections. */
class HaWebSocketClient(
    private val http: OkHttpClient, private val scheduler: HaScheduler,
    private val onStatus: (HaStatus) -> Unit,
    private val onReady: () -> Unit,
    private val onEvent: (JSONObject) -> Unit,
) {
    private var socket: WebSocket? = null
    private var generation = 0
    private var nextId = 0
    private var attempt = 0
    private var retry: (() -> Unit)? = null
    private var watchdog: (() -> Unit)? = null
    private var endpoint: HaEndpoint? = null
    private var token = ""
    private var running = false
    private val pending = mutableMapOf<Int, Pair<() -> Unit, (JSONObject) -> Unit>>()
    fun start(endpoint: HaEndpoint, token: String) { stop(); this.endpoint = endpoint; this.token = token; running = true; attempt = 0; connect() }
    fun stop() {
        running = false; generation++; retry?.invoke(); retry = null; watchdog?.invoke(); watchdog = null
        pending.values.forEach { it.first() }; pending.clear(); socket?.cancel(); socket = null; token = ""
    }
    fun synchronized() { attempt = 0; watchdog?.invoke(); watchdog = null; onStatus(HaStatus(HaConnectionState.CONNECTED)) }
    private fun connect() {
        val epoch = ++generation
        onStatus(HaStatus(if (attempt == 0) HaConnectionState.CONNECTING else HaConnectionState.RECONNECTING))
        watchdog = scheduler.after(25000) { disconnected(epoch, "Authentication/state synchronization timed out.") }
        socket = http.newWebSocket(Request.Builder().url(endpoint!!.websocket).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { scheduler.execute {
                if (epoch != generation || !running) return@execute
                try {
                    val message = JSONObject(text)
                    when (message.optString("type")) {
                        "auth_required" -> webSocket.send(JSONObject().put("type", "auth").put("access_token", token).toString())
                        "auth_invalid" -> { stop(); onStatus(HaStatus(HaConnectionState.AUTH_ERROR, "Authentication rejected; re-enter credentials.")) }
                        "auth_ok" -> { onStatus(HaStatus(HaConnectionState.SYNCING)); onReady() }
                        "result" -> pending.remove(message.optInt("id"))?.let { it.first(); it.second(message) }
                        "event" -> onEvent(message.getJSONObject("event"))
                    }
                } catch (_: Exception) { disconnected(epoch, "Invalid HA protocol response.") }
            } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { scheduler.execute { disconnected(epoch, networkError(t)) } }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { scheduler.execute { disconnected(epoch, "HA closed the connection.") } }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null); scheduler.execute { disconnected(epoch, "HA closed the connection.") } }
        })
    }
    private fun disconnected(epoch: Int, detail: String) {
        if (epoch != generation || !running) return
        generation++; socket?.cancel(); socket = null; watchdog?.invoke(); watchdog = null
        pending.values.forEach { it.first() }; pending.clear()
        onStatus(HaStatus(HaConnectionState.RECONNECTING, detail))
        retry = scheduler.after(reconnectDelay(attempt++)) { retry = null; connect() }
    }
    fun resyncFailed() { disconnected(generation, "Unable to synchronize HA state; retrying.") }
    fun request(message: JSONObject, callback: (JSONObject) -> Unit = {}): Boolean {
        val current = socket ?: return false
        if (pending.size >= 128) return false
        val id = ++nextId
        val cancel = scheduler.after(15000) {
            pending.remove(id)?.second?.invoke(JSONObject().put("success", false).put("error", JSONObject().put("code", "timeout")))
        }
        pending[id] = cancel to callback
        if (!current.send(message.put("id", id).toString())) { pending.remove(id); cancel(); return false }
        return true
    }
    fun service(domain: String, service: String, entity: String, data: JSONObject = JSONObject(), response: Boolean = false, callback: (JSONObject) -> Unit = {}) =
        request(JSONObject().put("type", "call_service").put("domain", domain).put("service", service)
            .put("target", JSONObject().put("entity_id", entity)).put("service_data", data).apply { if (response) put("return_response", true) }, callback)
}
