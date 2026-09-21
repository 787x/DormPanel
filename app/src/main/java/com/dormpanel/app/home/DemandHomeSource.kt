package com.dormpanel.app.home

/** A switching relay with no upstream subscription until its first consumer arrives. */
class DemandHomeSource(initial: HomeControlSource, private val onState: (HomeControlState) -> Unit = {}) : HomeControlSource {
    private var source = initial
    private val listeners = linkedSetOf<(HomeControlState) -> Unit>()
    private val relay: (HomeControlState) -> Unit = { value ->
        onState(value)
        listeners.toList().forEach { it(value) }
    }
    override val homeState get() = source.homeState
    override fun addHomeListener(listener: (HomeControlState) -> Unit) {
        if (!listeners.add(listener)) return
        if (listeners.size == 1) source.addHomeListener(relay)
        else listener(homeState)
    }
    override fun removeHomeListener(listener: (HomeControlState) -> Unit) {
        if (listeners.remove(listener) && listeners.isEmpty()) source.removeHomeListener(relay)
    }
    fun activate(next: HomeControlSource, prepare: (() -> Unit)? = null) {
        if (source === next && prepare == null) return
        if (listeners.isNotEmpty()) source.removeHomeListener(relay)
        prepare?.invoke()
        source = next
        if (listeners.isNotEmpty()) source.addHomeListener(relay)
    }
    fun close() {
        if (listeners.isNotEmpty()) source.removeHomeListener(relay)
        listeners.clear()
    }
    override fun activateEntity(id: String) = source.activateEntity(id)
}
