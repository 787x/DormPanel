package com.dormpanel.app.schedule

/** Optional instrumentation sink. No retained samples, polling, or work when disconnected. */
internal object X08ePreviewTrace {
    @Volatile var observer: ((String, Long, Long) -> Unit)? = null
    inline fun <T> section(name: String, block: () -> T): T {
        val sink=observer ?: return block()
        val start=System.nanoTime()
        try { return block() } finally { sink(name,start,System.nanoTime()) }
    }
}
