package com.dormpanel.app.dashboard.layout

import com.dormpanel.app.dashboard.model.CardSize
import com.dormpanel.app.dashboard.model.ExplicitCardSizePolicy
import com.dormpanel.app.dashboard.model.GridDefinition
import com.dormpanel.app.dashboard.model.PlacedCard
import com.dormpanel.app.dashboard.model.RangeCardSizePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardLayoutEngineTest {
    private val small = CardSize(1, 1)
    private val wide = CardSize(2, 1)
    private val large = CardSize(2, 2)
    private val catalog = CardSizeCatalog { type ->
        when (type) {
            "flex" -> ExplicitCardSizePolicy(listOf(small, wide, large))
            "small" -> ExplicitCardSizePolicy(listOf(small))
            "range" -> RangeCardSizePolicy(small, large)
            else -> null
        }
    }
    private val engine = DashboardLayoutEngine(GridDefinition(4, 3), catalog)

    @Test
    fun `valid placement remains inside bounds and non-overlapping`() {
        val cards = listOf(card("a", 0, 0), card("b", 3, 2))

        assertEquals(null, engine.validate(cards))
    }

    @Test
    fun `out of bounds move is rejected without changing layout`() {
        val cards = listOf(card("a", 0, 0))

        val result = engine.move(cards, "a", 4, 0)

        assertFailure(LayoutFailureReason.OUT_OF_BOUNDS, cards, result)
    }

    @Test
    fun `overlap is detected`() {
        assertEquals(
            LayoutFailureReason.OVERLAP,
            engine.validate(listOf(card("a", 0, 0), card("b", 0, 0))),
        )
    }

    @Test
    fun `drag into empty space moves only active card`() {
        val cards = listOf(card("a", 0, 0), card("b", 3, 2))

        val result = engine.move(cards, "a", 1, 1).success()

        assertEquals(card("a", 1, 1), result[0])
        assertEquals(cards[1], result[1])
    }

    @Test
    fun `collision deterministically reflows displaced card`() {
        val cards = listOf(card("a", 0, 0), card("b", 1, 0), card("c", 3, 2))

        val first = engine.move(cards, "a", 1, 0).success()
        val second = engine.move(cards, "a", 1, 0).success()

        assertEquals(first, second)
        assertEquals(card("a", 1, 0), first.first { it.id == "a" })
        assertEquals(card("b", 0, 0), first.first { it.id == "b" })
        assertEquals(cards[2], first.first { it.id == "c" })
    }

    @Test
    fun `several colliding cards reflow while unaffected card stays put`() {
        val cards = listOf(
            card("active", 0, 2, size = wide),
            card("b", 0, 0),
            card("c", 1, 0),
            card("untouched", 3, 2),
        )

        val result = engine.move(cards, "active", 0, 0).success()

        assertEquals(card("active", 0, 0, size = wide), result.first { it.id == "active" })
        assertEquals(card("b", 2, 0), result.first { it.id == "b" })
        assertEquals(card("c", 3, 0), result.first { it.id == "c" })
        assertEquals(cards.last(), result.first { it.id == "untouched" })
        assertEquals(null, engine.validate(result))
    }

    @Test
    fun `resize succeeds`() {
        val cards = listOf(card("a", 1, 1))

        val result = engine.resize(cards, "a", large).success()

        assertEquals(large, result.single().size)
        assertEquals(1, result.single().column)
        assertEquals(1, result.single().row)
    }

    @Test
    fun `resize displaces colliding cards`() {
        val cards = listOf(card("a", 0, 0), card("b", 1, 0), card("c", 0, 1))

        val result = engine.resize(cards, "a", large).success()

        assertEquals(card("a", 0, 0, size = large), result.first { it.id == "a" })
        assertEquals(null, engine.validate(result))
        assertTrue(result.first { it.id == "b" } != cards[1])
        assertTrue(result.first { it.id == "c" } != cards[2])
    }

    @Test
    fun `resize reflow is deterministic`() {
        val cards = listOf(card("a", 0, 0), card("b", 1, 0), card("c", 0, 1))

        val results = List(5) { engine.resize(cards, "a", large).success() }

        assertTrue(results.all { it == results.first() })
    }

    @Test
    fun `failed resize restores previous layout`() {
        val tightEngine = DashboardLayoutEngine(GridDefinition(2, 2), catalog)
        val cards = listOf(
            card("a", 0, 0), card("b", 1, 0),
            card("c", 0, 1), card("d", 1, 1),
        )

        val result = tightEngine.resize(cards, "a", wide)

        assertFailure(LayoutFailureReason.NO_SPACE, cards, result)
    }

    @Test
    fun `failed add leaves a full grid untouched`() {
        val tightEngine = DashboardLayoutEngine(GridDefinition(2, 1), catalog)
        val cards = listOf(card("a", 0, 0), card("b", 1, 0))

        val result = tightEngine.addFirstAvailable(cards, card("new", 0, 0))

        assertFailure(LayoutFailureReason.NO_SPACE, cards, result)
    }

    @Test
    fun `deletion frees a cell for a later add`() {
        val cards = listOf(card("a", 0, 0), card("b", 1, 0))

        val afterDelete = engine.delete(cards, "a").success()
        val afterAdd = engine.addFirstAvailable(afterDelete, card("new", 0, 0)).success()

        assertEquals(card("new", 0, 0), afterAdd.last())
    }

    @Test
    fun `identical complex inputs always produce identical layouts`() {
        val cards = listOf(
            card("z", 0, 2, size = wide),
            card("a", 0, 0),
            card("b", 1, 0),
            card("c", 2, 0),
            card("d", 3, 0),
        )

        val results = List(5) { engine.move(cards, "z", 1, 0).success() }

        assertTrue(results.all { it == results.first() })
    }

    @Test
    fun `unsupported card size is rejected`() {
        val cards = listOf(card("a", 0, 0, type = "small"))

        val result = engine.resize(cards, "a", wide)

        assertFailure(LayoutFailureReason.UNSUPPORTED_SIZE, cards, result)
    }

    private fun card(
        id: String,
        column: Int,
        row: Int,
        type: String = "flex",
        size: CardSize = small,
    ) = PlacedCard(id, type, column, row, size)

    private fun LayoutMutationResult.success(): List<PlacedCard> {
        assertTrue("Expected success but was $this", this is LayoutMutationResult.Success)
        return cards
    }

    private fun assertFailure(
        reason: LayoutFailureReason,
        original: List<PlacedCard>,
        result: LayoutMutationResult,
    ) {
        assertTrue("Expected failure but was $result", result is LayoutMutationResult.Failure)
        assertEquals(reason, (result as LayoutMutationResult.Failure).reason)
        assertEquals(original, result.cards)
    }
}
