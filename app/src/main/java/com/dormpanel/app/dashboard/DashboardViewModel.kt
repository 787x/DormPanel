package com.dormpanel.app.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.dormpanel.app.dashboard.card.DashboardCardRegistry
import com.dormpanel.app.dashboard.persistence.RoomDashboardStore

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    val registry = DashboardCardRegistry.mock()
    val stateHolder = DashboardStateHolder(
        registry = registry,
        store = RoomDashboardStore(application),
    )

    override fun onCleared() {
        stateHolder.close()
    }
}
