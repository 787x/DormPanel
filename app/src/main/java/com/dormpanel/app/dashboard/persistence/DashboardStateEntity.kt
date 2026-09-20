package com.dormpanel.app.dashboard.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dashboard_state")
data class DashboardStateEntity(
    @PrimaryKey val id: Int,
    val revision: Long,
)
