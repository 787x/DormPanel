package com.dormpanel.app.dashboard.layout

import com.dormpanel.app.dashboard.model.*
import com.dormpanel.app.dashboard.persistence.*
import org.junit.Assert.*
import org.junit.Test

class DashboardLayoutRepairTest {
    private val grid = GridDefinition(4, 3)
    private val catalog = CardSizeCatalog { type -> when (type) {
        "card" -> ExplicitCardSizePolicy(listOf(CardSize(2, 1), CardSize(2, 2)))
        "huge" -> ExplicitCardSizePolicy(listOf(CardSize(5, 5)))
        else -> null
    } }
    private fun raw(id: String, x: Int = 0, y: Int = 0, w: Int = 2, h: Int = 1, type: String = "card") =
        RawDashboardCard(id, type, x, y, w, h, "{opaque configuration}")
    private fun repair(vararg records: RawDashboardCard): DashboardRepairResult {
        val result = DashboardLayoutRepair(grid, catalog).repair(records.toList())
        assertNull(DashboardLayoutEngine(grid, catalog).validate(result.cards))
        assertEquals(result, DashboardLayoutRepair(grid, catalog).repair(records.toList()))
        return result
    }
    @Test fun validAndEmptyArePreservedExactly() {
        assertEquals(emptyList<PlacedCard>(), repair().cards)
        val cards = listOf(raw("a"), raw("b", 2, 1))
        assertEquals(cards.map { it.toModel() }, repair(*cards.toTypedArray()).cards)
    }
    @Test fun unsupportedSizeSnapsWhileEarlierCardStays() {
        val first = raw("first")
        val result = repair(first, raw("old", 2, 0, 3, 2))
        assertEquals(first.toModel(), result.cards.first())
        assertEquals(raw("old", 2, 0, 2, 2).toModel(), result.cards.last())
    }
    @Test fun outOfBoundsAndOverlapRelocateRowMajorWithoutReflow() {
        for (x in listOf(0, 99, Int.MAX_VALUE)) {
            val result = repair(raw("a"), raw("b", x))
            assertEquals(listOf(raw("a").toModel(), raw("b", 2).toModel()), result.cards)
        }
    }
    @Test fun unknownDuplicateMalformedAndImpossibleRetainRawRecords() {
        val bad = listOf(raw("unknown", type = "future"), raw("a", 2), raw("bad", w = 0), raw("huge", type = "huge"))
        val result = repair(raw("a"), *bad.toTypedArray())
        assertEquals(listOf(raw("a").toModel()), result.cards)
        assertEquals(bad, result.quarantine.map { it.raw })
        assertEquals(listOf(RecoveryReason.UNKNOWN_PROVIDER, RecoveryReason.DUPLICATE_ID, RecoveryReason.MALFORMED, RecoveryReason.NO_VALID_SIZE), result.quarantine.map { it.reason })
    }
    @Test fun noSpaceOnlyQuarantinesLaterCards() {
        val result = repair(*(0..6).map { raw(it.toString()) }.toTypedArray())
        assertEquals(6, result.cards.size)
        assertEquals(listOf(QuarantinedCard(raw("6"), RecoveryReason.NO_SPACE)), result.quarantine)
    }
    @Test fun recoveredProviderAppendsAfterActiveCardsAndRemovesOnlyRecoveredRecord() {
        val missing = raw("returning", type = "future")
        val quarantined = repair(raw("active"), missing).quarantine
        val restored = DashboardLayoutRepair(grid, CardSizeCatalog { catalog.sizePolicy("card") }).repair(listOf(raw("active")), quarantined)
        assertEquals(raw("active").toModel(), restored.cards.first())
        assertEquals(missing.toModel().copy(column = 2), restored.cards.last())
        assertTrue(restored.quarantine.isEmpty())
    }
}
