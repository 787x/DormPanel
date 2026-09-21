package com.dormpanel.app.dashboard.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dashboard_quarantine")
data class DashboardQuarantineEntity(
    @PrimaryKey(autoGenerate = true) val recoveryId: Long = 0,
    val id: String, val providerType: String, val columnIndex: Int, val rowIndex: Int,
    val columnSpan: Int, val rowSpan: Int, val configurationJson: String, val reason: String,
) {
    fun toModel() = QuarantinedCard(
        RawDashboardCard(id, providerType, columnIndex, rowIndex, columnSpan, rowSpan, configurationJson),
        RecoveryReason.entries.firstOrNull { it.name == reason } ?: RecoveryReason.MALFORMED,
    )
    companion object {
        fun from(card: QuarantinedCard) = with(card.raw) {
            DashboardQuarantineEntity(id = id, providerType = providerType, columnIndex = column, rowIndex = row,
                columnSpan = columnSpan, rowSpan = rowSpan, configurationJson = configurationJson, reason = card.reason.name)
        }
    }
}
