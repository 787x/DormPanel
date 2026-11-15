package com.dormpanel.app.ha

import org.json.JSONArray
import org.json.JSONObject

/** One HA subscription (or one fallback timer) per entity and forecast type. */
internal class HaForecastSubscriptions(private val socket: HaWebSocketClient, private val scheduler: HaScheduler) {
    data class Result(val forecast: JSONArray? = null, val error: String? = null)
    private data class Key(val entity: String, val type: String)
    private class Feed {
        val listeners = linkedSetOf<(Result) -> Unit>()
        var latest: Result? = null
        var unsubscribe: (() -> Unit)? = null
        var timer: (() -> Unit)? = null
        var generation = 0
    }

    private val feeds = mutableMapOf<Key, Feed>()
    private var connected = false

    fun connection(ready: Boolean) {
        if (connected == ready) return
        connected = ready
        feeds.toMap().forEach { (key, feed) ->
            reset(feed)
            if (ready) connect(key, feed) else publish(feed, Result(error = "offline"))
        }
    }

    fun observe(entity: String, type: String, listener: (Result) -> Unit): () -> Unit {
        val key = Key(entity, type)
        val feed = feeds.getOrPut(key, ::Feed)
        val first = feed.listeners.isEmpty()
        feed.listeners += listener
        if (first && connected) connect(key, feed)
        else feed.latest?.let(listener)
        return {
            feed.listeners -= listener
            if (feed.listeners.isEmpty()) {
                feeds.remove(key)
                reset(feed)
            }
        }
    }

    private fun reset(feed: Feed) {
        feed.generation++
        feed.unsubscribe?.invoke()
        feed.unsubscribe = null
        feed.timer?.invoke()
        feed.timer = null
        feed.latest = null
    }

    private fun publish(feed: Feed, value: Result) {
        feed.latest = value
        feed.listeners.toList().forEach { it(value) }
    }

    private fun connect(key: Key, feed: Feed) {
        val generation = feed.generation
        fun active() = connected && feed.generation == generation && feed.listeners.isNotEmpty()
        fun fallback() {
            if (!active()) return
            feed.timer?.invoke()
            feed.unsubscribe?.invoke()
            feed.unsubscribe = null
            poll(key, feed, generation)
        }
        feed.timer = scheduler.after(25_000, ::fallback)
        feed.unsubscribe = socket.subscribe(
            JSONObject().put("type", "weather/subscribe_forecast").put("entity_id", key.entity)
                .put("forecast_type", key.type),
            onResult = { if (active() && !it.optBoolean("success")) fallback() },
            onEvent = {
                if (active() && it.optString("type") == key.type && it.has("forecast")) {
                    feed.timer?.invoke()
                    feed.timer = null
                    publish(feed, Result(it.optJSONArray("forecast") ?: JSONArray()))
                }
            },
        )
        if (feed.unsubscribe == null) fallback()
    }

    private fun poll(key: Key, feed: Feed, generation: Int) {
        if (!connected || feed.generation != generation || feed.listeners.isEmpty()) return
        fun schedule(delay: Long) {
            if (connected && feed.generation == generation && feed.listeners.isNotEmpty())
                feed.timer = scheduler.after(delay) { poll(key, feed, generation) }
        }
        val sent = socket.service("weather", "get_forecasts", key.entity, JSONObject().put("type", key.type), true) { result ->
            if (feed.generation != generation || !connected) return@service
            if (result.optBoolean("success")) {
                val response = result.optJSONObject("result")
                val forecast = (response?.optJSONObject("response") ?: response)?.optJSONObject(key.entity)?.optJSONArray("forecast")
                publish(feed, Result(forecast ?: JSONArray()))
                schedule(3_600_000)
            } else {
                publish(feed, Result(error = result.optJSONObject("error")?.optString("code") ?: "failed"))
                schedule(900_000)
            }
        }
        if (!sent) {
            publish(feed, Result(error = "offline"))
            schedule(900_000)
        }
    }
}
