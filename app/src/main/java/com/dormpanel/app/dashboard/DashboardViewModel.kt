package com.dormpanel.app.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.dormpanel.app.dashboard.card.coreCardRegistry
import com.dormpanel.app.data.FakeDashboardDataSource
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.appearance.PreferencesAppearanceStore
import com.dormpanel.app.dashboard.persistence.RoomDashboardStore

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    val dataSource = FakeDashboardDataSource()
    val appearance = AppearanceController(PreferencesAppearanceStore(application))
    val registry = coreCardRegistry(dataSource, appearance)
    val stateHolder = DashboardStateHolder(
        registry = registry,
        store = RoomDashboardStore(application),
    )

    override fun onCleared() {
        stateHolder.close()
    }
}
