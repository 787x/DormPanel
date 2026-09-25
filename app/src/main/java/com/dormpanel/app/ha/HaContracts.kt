package com.dormpanel.app.ha

import okhttp3.HttpUrl.Companion.toHttpUrl

enum class BackendMode { DEMO, HOME_ASSISTANT }
enum class HaConnectionState { NOT_CONFIGURED, CONNECTING, SYNCING, CONNECTED, RECONNECTING, AUTH_ERROR, ERROR }
data class HaStatus(val state: HaConnectionState, val detail: String = "")
data class HaConnectionSettings(
    val mode: BackendMode = BackendMode.DEMO, val baseUrl: String = "",
    val weatherEntity: String = "", val themeEntity: String = "", val opacityEntity: String = "",
    val displayBrightnessEntity: String = "", val mediaVolumeEntity: String = "",
)
data class HaEndpoint(val base: String) {
    val api get() = "$base/api/"
    val websocket get() = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/api/websocket"
    val cleartext get() = base.startsWith("http://")
    companion object {
        fun parse(input: String): HaEndpoint {
            var value = input.trim().replaceFirst(Regex("^ws://", RegexOption.IGNORE_CASE), "http://")
                .replaceFirst(Regex("^wss://", RegexOption.IGNORE_CASE), "https://")
            if (!value.contains("://")) value = "http://$value"
            val url = value.toHttpUrl()
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "Use a base URL without credentials, query or fragment" }
            var path = url.encodedPath.trimEnd('/')
            for (suffix in listOf("/api/websocket", "/api/config", "/api")) if (path.endsWith(suffix)) { path = path.removeSuffix(suffix); break }
            return HaEndpoint(url.newBuilder().encodedPath(path.ifEmpty { "/" }).build().toString().trimEnd('/'))
        }
    }
}

fun reconnectDelay(attempt: Int): Long = listOf(1000L, 2000L, 5000L, 10000L, 30000L)[attempt.coerceIn(0, 4)]

/** All runtime state is confined to this scheduler (Android's main looper in production). */
interface HaScheduler {
    fun execute(action: () -> Unit)
    fun after(delayMillis: Long, action: () -> Unit): () -> Unit
}

/** One pending value per entity/property; continuous drags flush every 180ms, including the tail. */
class LatestCommands(private val scheduler: HaScheduler, private val send: (String, String, Int) -> Unit) {
    private val values = mutableMapOf<Pair<String, String>, Int>()
    private val cancellations = mutableMapOf<Pair<String, String>, () -> Unit>()
    fun put(entity: String, property: String, value: Int) {
        val key = entity to property
        values[key] = value
        if (key !in cancellations) cancellations[key] = scheduler.after(180) {
            cancellations.remove(key)
            values.remove(key)?.let { send(entity, property, it) }
        }
    }
    fun cancel(entity: String) {
        cancellations.keys.filter { it.first == entity }.forEach { key -> cancellations.remove(key)?.invoke(); values.remove(key) }
    }
    fun clear() { cancellations.values.forEach { it() }; cancellations.clear(); values.clear() }
}
