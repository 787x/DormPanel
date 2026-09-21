package com.dormpanel.app.productivity

/** A display-only, one-shot ticker. Rendering decides whether another tick is needed.
 * It never owns countdown state or performs persistence. */
class CountdownDisplayTicker(private val post: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit, private val render: () -> Unit) {
    private var pending = false
    private val tick = Runnable { if (pending) { pending = false; render() } }
    fun update(visible: Boolean, status: TimerStatus, remaining: Long) {
        stop()
        if (visible && status == TimerStatus.RUNNING && remaining > 0) {
            pending = true
            post(tick, ((remaining - 1) % 1000) + 1)
        }
    }
    fun stop() { if (pending) cancel(tick); pending = false }
}
