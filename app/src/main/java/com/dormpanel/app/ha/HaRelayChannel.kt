package com.dormpanel.app.ha

import org.json.JSONObject

/** Optional commands on the already synchronized entity WebSocket. */
interface HaRelayChannel {
    val origin: String
    val ready: Boolean
    fun request(message: JSONObject, callback: (JSONObject) -> Unit): Boolean
    fun addReadyListener(listener: () -> Unit)
    fun removeReadyListener(listener: () -> Unit)
    fun addEventListener(listener: (JSONObject) -> Unit)
    fun removeEventListener(listener: (JSONObject) -> Unit)
}
