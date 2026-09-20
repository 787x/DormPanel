package com.dormpanel.app.dashboard.layout

import com.dormpanel.app.dashboard.model.CardSize
import com.dormpanel.app.dashboard.model.GridDefinition
import com.dormpanel.app.dashboard.model.PlacedCard

fun interface CardSizeCatalog {
    fun supportedSizes(providerType: String): List<CardSize>?
}

enum class LayoutFailureReason {
    CARD_NOT_FOUND,
    DUPLICATE_CARD_ID,
    UNKNOWN_PROVIDER,
    UNSUPPORTED_SIZE,
    OUT_OF_BOUNDS,
    OVERLAP,
    NO_SPACE,
    INVALID_EXISTING_LAYOUT,
}

sealed interface LayoutMutationResult {
    val cards: List<PlacedCard>

    data class Success(override val cards: List<PlacedCard>) : LayoutMutationResult

    data class Failure(
        val reason: LayoutFailureReason,
        override val cards: List<PlacedCard>,
    ) : LayoutMutationResult
}

/** Pure Kotlin logical-grid placement and deterministic reflow. */
class DashboardLayoutEngine(
    private val grid: GridDefinition,
    private val sizeCatalog: CardSizeCatalog,
) {
    fun validate(cards: List<PlacedCard>): LayoutFailureReason? {
        if (cards.map { it.id }.toSet().size != cards.size) {
            return LayoutFailureReason.DUPLICATE_CARD_ID
        }

        var occupancy = 0L
        for (card in cards) {
            val supportedSizes = sizeCatalog.supportedSizes(card.providerType)
                ?: return LayoutFailureReason.UNKNOWN_PROVIDER
            if (card.size !in supportedSizes) return LayoutFailureReason.UNSUPPORTED_SIZE
            if (!isInsideGrid(card)) return LayoutFailureReason.OUT_OF_BOUNDS
            val mask = maskFor(card)
            if (occupancy and mask != 0L) return LayoutFailureReason.OVERLAP
            occupancy = occupancy or mask
        }
        return null
    }

    fun move(
        cards: List<PlacedCard>,
        cardId: String,
        column: Int,
        row: Int,
    ): LayoutMutationResult {
        val active = cards.firstOrNull { it.id == cardId }
            ?: return failure(LayoutFailureReason.CARD_NOT_FOUND, cards)
        return reflow(cards, active.copy(column = column, row = row), isNewCard = false)
    }

    fun resize(
        cards: List<PlacedCard>,
        cardId: String,
        size: CardSize,
    ): LayoutMutationResult {
        val active = cards.firstOrNull { it.id == cardId }
            ?: return failure(LayoutFailureReason.CARD_NOT_FOUND, cards)
        return reflow(cards, active.copy(size = size), isNewCard = false)
    }

    fun addAt(cards: List<PlacedCard>, card: PlacedCard): LayoutMutationResult {
        if (cards.any { it.id == card.id }) {
            return failure(LayoutFailureReason.DUPLICATE_CARD_ID, cards)
        }
        return reflow(cards, card, isNewCard = true)
    }

    /** Adds without disturbing existing cards, choosing the first free row-major cell. */
    fun addFirstAvailable(cards: List<PlacedCard>, card: PlacedCard): LayoutMutationResult {
        validate(cards)?.let { return failure(LayoutFailureReason.INVALID_EXISTING_LAYOUT, cards) }
        if (cards.any { it.id == card.id }) {
            return failure(LayoutFailureReason.DUPLICATE_CARD_ID, cards)
        }
        validateProviderAndSize(card)?.let { return failure(it, cards) }
        if (card.size.columnSpan > grid.columns || card.size.rowSpan > grid.rows) {
            return failure(LayoutFailureReason.OUT_OF_BOUNDS, cards)
        }

        for (row in 0..grid.rows - card.size.rowSpan) {
            for (column in 0..grid.columns - card.size.columnSpan) {
                val candidate = card.copy(column = column, row = row)
                if (cards.none { overlaps(it, candidate) }) {
                    return LayoutMutationResult.Success(cards + candidate)
                }
            }
        }
        return failure(LayoutFailureReason.NO_SPACE, cards)
    }

    fun delete(cards: List<PlacedCard>, cardId: String): LayoutMutationResult {
        validate(cards)?.let { return failure(LayoutFailureReason.INVALID_EXISTING_LAYOUT, cards) }
        if (cards.none { it.id == cardId }) {
            return failure(LayoutFailureReason.CARD_NOT_FOUND, cards)
        }
        return LayoutMutationResult.Success(cards.filterNot { it.id == cardId })
    }

    private fun reflow(
        previousCards: List<PlacedCard>,
        requestedActive: PlacedCard,
        isNewCard: Boolean,
    ): LayoutMutationResult {
        validate(previousCards)?.let {
            return failure(LayoutFailureReason.INVALID_EXISTING_LAYOUT, previousCards)
        }
        val activeCount = previousCards.count { it.id == requestedActive.id }
        if ((isNewCard && activeCount != 0) || (!isNewCard && activeCount != 1)) {
            val reason = if (activeCount > 0) {
                LayoutFailureReason.DUPLICATE_CARD_ID
            } else {
                LayoutFailureReason.CARD_NOT_FOUND
            }
            return failure(reason, previousCards)
        }
        val existingCards = previousCards.filterNot { it.id == requestedActive.id }

        validateProviderAndSize(requestedActive)?.let { return failure(it, previousCards) }
        if (!isInsideGrid(requestedActive)) {
            return failure(LayoutFailureReason.OUT_OF_BOUNDS, previousCards)
        }

        val activeMask = maskFor(requestedActive)
        val ordered = existingCards.sortedWith(
            compareBy<PlacedCard> { overlaps(it, requestedActive) }
                .thenBy { it.row }
                .thenBy { it.column }
                .thenBy { it.id },
        )
        val assignments = linkedMapOf(requestedActive.id to requestedActive)
        val failedStates = mutableSetOf<SearchState>()

        fun search(index: Int, occupancy: Long): Boolean {
            if (index == ordered.size) return true
            val state = SearchState(index, occupancy)
            if (!failedStates.add(state)) return false

            val card = ordered[index]
            for (candidate in candidatesFor(card)) {
                val mask = maskFor(candidate)
                if (occupancy and mask != 0L) continue
                assignments[card.id] = candidate
                if (search(index + 1, occupancy or mask)) return true
                assignments.remove(card.id)
            }
            return false
        }

        if (!search(index = 0, occupancy = activeMask)) {
            return failure(LayoutFailureReason.NO_SPACE, previousCards)
        }

        val resultOrder = if (isNewCard) previousCards + requestedActive else previousCards
        val result = resultOrder.map { assignments.getValue(it.id) }
        check(validate(result) == null) { "Layout search produced an invalid result" }
        return LayoutMutationResult.Success(result)
    }

    private fun candidatesFor(card: PlacedCard): Sequence<PlacedCard> = sequence {
        if (isInsideGrid(card)) yield(card)
        for (row in 0..grid.rows - card.size.rowSpan) {
            for (column in 0..grid.columns - card.size.columnSpan) {
                if (column == card.column && row == card.row) continue
                yield(card.copy(column = column, row = row))
            }
        }
    }

    private fun validateProviderAndSize(card: PlacedCard): LayoutFailureReason? {
        val sizes = sizeCatalog.supportedSizes(card.providerType)
            ?: return LayoutFailureReason.UNKNOWN_PROVIDER
        return if (card.size in sizes) null else LayoutFailureReason.UNSUPPORTED_SIZE
    }

    private fun isInsideGrid(card: PlacedCard): Boolean =
        card.column >= 0 && card.row >= 0 &&
            card.column + card.size.columnSpan <= grid.columns &&
            card.row + card.size.rowSpan <= grid.rows

    private fun overlaps(first: PlacedCard, second: PlacedCard): Boolean =
        first.column < second.column + second.size.columnSpan &&
            first.column + first.size.columnSpan > second.column &&
            first.row < second.row + second.size.rowSpan &&
            first.row + first.size.rowSpan > second.row

    private fun maskFor(card: PlacedCard): Long {
        var mask = 0L
        for (row in card.row until card.row + card.size.rowSpan) {
            for (column in card.column until card.column + card.size.columnSpan) {
                mask = mask or (1L shl (row * grid.columns + column))
            }
        }
        return mask
    }

    private fun failure(
        reason: LayoutFailureReason,
        cards: List<PlacedCard>,
    ) = LayoutMutationResult.Failure(reason, cards)

    private data class SearchState(val index: Int, val occupancy: Long)
}
