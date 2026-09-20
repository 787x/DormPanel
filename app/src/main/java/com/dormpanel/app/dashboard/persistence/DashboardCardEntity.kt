package com.dormpanel.app.dashboard.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dashboard_cards")
data class DashboardCardEntity(
    @PrimaryKey val id: String,
    val providerType: String,
    val columnIndex: Int,
    val rowIndex: Int,
    val columnSpan: Int,
    val rowSpan: Int,
    val configurationJson: String,
)
