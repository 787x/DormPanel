package com.dormpanel.app.dashboard.persistence

import android.content.Context

/** Installed before Activity creation by instrumentation; production always uses Room. */
object DashboardStores {
    @Volatile var overrideFactory: ((Context) -> DashboardStore)? = null
    fun create(context: Context): DashboardStore = overrideFactory?.invoke(context) ?: RoomDashboardStore(context)
}
