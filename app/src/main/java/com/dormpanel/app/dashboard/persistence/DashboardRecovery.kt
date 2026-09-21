package com.dormpanel.app.dashboard.persistence

import com.dormpanel.app.dashboard.model.*

/** Raw spans deliberately have no model preconditions: corrupt records must survive loading. */
data class RawDashboardCard(
    val id: String, val providerType: String, val column: Int, val row: Int,
    val columnSpan: Int, val rowSpan: Int, val configurationJson: String,
) {
    fun toModel() = PlacedCard(id, providerType, column, row, CardSize(columnSpan, rowSpan), configurationJson)
    companion object {
        fun from(card: PlacedCard) = RawDashboardCard(card.id, card.providerType, card.column, card.row,
            card.size.columnSpan, card.size.rowSpan, card.configurationJson)
    }
}

enum class RecoveryReason { UNKNOWN_PROVIDER, NO_VALID_SIZE, NO_SPACE, DUPLICATE_ID, MALFORMED }
data class QuarantinedCard(val raw: RawDashboardCard, val reason: RecoveryReason)
