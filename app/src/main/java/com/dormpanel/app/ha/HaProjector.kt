package com.dormpanel.app.ha

import com.dormpanel.app.data.*
import com.dormpanel.app.home.HomeControlState

/** Projection boundary for invocation-count tests; the raw store remains single-owned. */
interface HaProjector {
    fun dashboard(store: HaEntityStore, connected: Boolean, weatherId: String): DashboardData
    fun home(store: HaEntityStore, connected: Boolean, lights: Map<String, LightState>): HomeControlState
}
object DefaultHaProjector : HaProjector {
    override fun dashboard(store: HaEntityStore, connected: Boolean, weatherId: String) = store.normalized(connected, weatherId)
    override fun home(store: HaEntityStore, connected: Boolean, lights: Map<String, LightState>) = store.home(connected, lights)
}
