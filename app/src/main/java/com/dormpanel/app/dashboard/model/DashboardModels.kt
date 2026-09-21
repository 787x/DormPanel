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

sealed interface CardSizePolicy {
    fun allows(size: CardSize): Boolean

    /** Returns the closest allowed logical size that also fits [maximum], if one exists. */
    fun snap(candidate: CardSize, maximum: CardSize): CardSize?

    fun hasAlternative(current: CardSize, maximum: CardSize): Boolean
}

data class RangeCardSizePolicy(
    val minimum: CardSize,
    val maximum: CardSize,
) : CardSizePolicy {
    init {
        require(minimum.columnSpan <= maximum.columnSpan) { "Minimum width exceeds maximum width" }
        require(minimum.rowSpan <= maximum.rowSpan) { "Minimum height exceeds maximum height" }
    }

    override fun allows(size: CardSize): Boolean =
        size.columnSpan in minimum.columnSpan..maximum.columnSpan &&
            size.rowSpan in minimum.rowSpan..maximum.rowSpan

    override fun snap(candidate: CardSize, maximum: CardSize): CardSize? {
        val effectiveMaximumColumns = minOf(this.maximum.columnSpan, maximum.columnSpan)
        val effectiveMaximumRows = minOf(this.maximum.rowSpan, maximum.rowSpan)
        if (effectiveMaximumColumns < minimum.columnSpan || effectiveMaximumRows < minimum.rowSpan) {
            return null
        }
        return CardSize(
            columnSpan = candidate.columnSpan.coerceIn(minimum.columnSpan, effectiveMaximumColumns),
            rowSpan = candidate.rowSpan.coerceIn(minimum.rowSpan, effectiveMaximumRows),
        )
    }

    override fun hasAlternative(current: CardSize, maximum: CardSize): Boolean {
        val snappedMinimum = snap(minimum, maximum) ?: return false
        val snappedMaximum = snap(this.maximum, maximum) ?: return false
        return current != snappedMinimum || current != snappedMaximum
    }
}

data class ExplicitCardSizePolicy(
    val allowedSizes: List<CardSize>,
) : CardSizePolicy {
    init {
        require(allowedSizes.isNotEmpty()) { "Explicit size policy must allow at least one size" }
        require(allowedSizes.distinct().size == allowedSizes.size) { "Explicit sizes must be unique" }
    }

    override fun allows(size: CardSize): Boolean = size in allowedSizes

    override fun snap(candidate: CardSize, maximum: CardSize): CardSize? = allowedSizes
        .asSequence()
        .filter { it.columnSpan <= maximum.columnSpan && it.rowSpan <= maximum.rowSpan }
        .minWithOrNull(
            compareBy<CardSize> { size ->
                val columnDistance = size.columnSpan.toLong() - candidate.columnSpan
                val rowDistance = size.rowSpan.toLong() - candidate.rowSpan
                columnDistance * columnDistance + rowDistance * rowDistance
            }.thenBy { allowedSizes.indexOf(it) },
        )

    override fun hasAlternative(current: CardSize, maximum: CardSize): Boolean = allowedSizes.any {
        it != current && it.columnSpan <= maximum.columnSpan && it.rowSpan <= maximum.rowSpan
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
