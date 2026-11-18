package com.dormpanel.app.ha

import org.json.JSONObject

/** Retries a connection-scoped request until its response is accepted or the connection ends. */
internal class HaRetryRequest(private val channel: HaRelayChannel, private val scheduler: HaScheduler) {
    private var generation = 0
    private var cancelRetry: (() -> Unit)? = null

    fun start(message: () -> JSONObject, finished: (JSONObject) -> Boolean) {
        cancel()
        val epoch = generation
        var failures = 0
        fun attempt() {
            if (epoch != generation || !channel.ready) return
            var completed = false
            val reply: (JSONObject) -> Unit = reply@{ result ->
                if (epoch != generation || !channel.ready || completed) return@reply
                completed = true
                if (!finished(result) && epoch == generation && channel.ready) {
                    cancelRetry = scheduler.after(reconnectDelay(failures)) {
                        cancelRetry = null
                        attempt()
                    }
                    failures = (failures + 1).coerceAtMost(4)
                }
            }
            if (!channel.request(message(), reply)) reply(JSONObject().put("success", false))
        }
        attempt()
    }

    fun cancel() {
        generation++
        cancelRetry?.invoke()
        cancelRetry = null
    }
}
