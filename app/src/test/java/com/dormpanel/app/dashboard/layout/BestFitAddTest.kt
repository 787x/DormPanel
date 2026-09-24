package com.dormpanel.app.dashboard.layout

import com.dormpanel.app.dashboard.model.*
import org.junit.Assert.*
import org.junit.Test

class BestFitAddTest {
    private val range = RangeCardSizePolicy(CardSize(2, 1), CardSize(4, 3))
    private val explicit = ExplicitCardSizePolicy(range.legalSizes(CardSize(4, 3)).reversed())
    private fun verify(policy: CardSizePolicy, freeWidth: Int, freeHeight: Int, expected: CardSize?) {
        val engine = DashboardLayoutEngine(GridDefinition(8, 6)) { type ->
            if (type == "new") policy else ExplicitCardSizePolicy(listOf(CardSize(1, 1)))
        }
        val existing = buildList {
            for (row in 0..5) for (column in 0..7) {
                if (row >= freeHeight || column >= freeWidth)
                    add(PlacedCard("$column:$row", "block", column, row, CardSize(1, 1)))
            }
        }
        val card = PlacedCard("new", "new", 7, 5, CardSize(4, 3))
        val result = engine.addBestFit(existing, card)
        assertEquals(existing, result.cards.filter { it.id != "new" })
        if (expected == null) assertEquals(LayoutFailureReason.NO_SPACE, (result as LayoutMutationResult.Failure).reason)
        else assertEquals(card.copy(column = 0, row = 0, size = expected), (result as LayoutMutationResult.Success).cards.last())
        assertEquals(result, engine.addBestFit(existing, card))
    }
    @Test fun defaultAndAllFallbacksAreStationaryForBothPolicies() {
        listOf(range, explicit).forEach { policy ->
            verify(policy, 4, 3, CardSize(4, 3))
            verify(policy, 3, 3, CardSize(3, 3))
            verify(policy, 4, 2, CardSize(4, 2))
            verify(policy, 2, 1, CardSize(2, 1))
            verify(policy, 1, 6, null)
        }
    }
    @Test fun equalShrinkPrefersAreaAndNeverEnlarges() {
        val engine = DashboardLayoutEngine(GridDefinition(8, 6)) { range }
        val existing = listOf(PlacedCard("block", "clock", 0, 0, CardSize(4, 3)),
            PlacedCard("block2", "clock", 4, 0, CardSize(4, 3)),
            PlacedCard("block3", "clock", 3, 3, CardSize(2, 3)))
        val result = engine.addBestFit(existing, PlacedCard("new", "clock", 0, 0, CardSize(4, 3))) as LayoutMutationResult.Success
        assertEquals(CardSize(3, 3), result.cards.last().size)
        assertEquals(0, result.cards.last().column); assertEquals(3, result.cards.last().row)
        val small = engine.addBestFit(emptyList(), PlacedCard("new", "clock", 0, 0, CardSize(2, 1)))
        assertEquals(CardSize(2, 1), small.cards.single().size)
    }
    @Test fun largerAreaWinsEvenWhenSmallerAreaHasEarlierFreePosition() {
        val engine = DashboardLayoutEngine(GridDefinition(8, 6)) { type ->
            if (type == "new") range else ExplicitCardSizePolicy(listOf(CardSize(1, 1)))
        }
        val cards = buildList {
            for (row in 0..5) for (column in 0..7) {
                val free = (column < 4 && row < 2) || (column >= 5 && row < 3)
                if (!free) add(PlacedCard("$column:$row", "block", column, row, CardSize(1, 1)))
            }
        }
        val result = engine.addBestFit(cards, PlacedCard("new", "new", 0, 0, CardSize(4, 3)))
        assertEquals(CardSize(3, 3), result.cards.last().size)
        assertEquals(5, result.cards.last().column)
    }
    @Test fun explicitPolicyNeverInventsIntermediateSizes() {
        verify(ExplicitCardSizePolicy(listOf(CardSize(4, 3), CardSize(2, 1))), 3, 3, CardSize(2, 1))
    }

}
