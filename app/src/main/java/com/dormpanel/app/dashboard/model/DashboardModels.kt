package com.dormpanel.app.dashboard.model

data class GridDefinition(
    val columns: Int,
    val rows: Int,
) {
    init {
        require(columns > 0 && rows > 0) { "Grid dimensions must be positive" }
        require(columns * rows <= Long.SIZE_BITS) { "The grid must fit in a 64-bit occupancy mask" }
    }
}

data class CardSize(
    val columnSpan: Int,
    val rowSpan: Int,
) {
    init {
        require(columnSpan > 0 && rowSpan > 0) { "Card spans must be positive" }
    }
}

/**
 * Persistent, renderer-independent state for one dashboard card.
 * [configurationJson] is deliberately opaque to the grid engine and belongs to the provider.
 */
data class PlacedCard(
    val id: String,
    val providerType: String,
    val column: Int,
    val row: Int,
    val size: CardSize,
    val configurationJson: String = "{}",
)

object DashboardGridPolicy {
    val definition = GridDefinition(columns = 8, rows = 6)
    const val GAP_DP = 10
}
