package com.dormpanel.app.dashboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardSizePolicyTest {
    @Test
    fun `range policy accepts every dimension between minimum and maximum`() {
        val policy = RangeCardSizePolicy(CardSize(2, 1), CardSize(4, 3))

        assertTrue(policy.allows(CardSize(2, 1)))
        assertTrue(policy.allows(CardSize(3, 2)))
        assertTrue(policy.allows(CardSize(4, 3)))
    }

    @Test
    fun `range policy rejects dimensions outside either bound`() {
        val policy = RangeCardSizePolicy(CardSize(2, 1), CardSize(4, 3))

        assertFalse(policy.allows(CardSize(1, 2)))
        assertFalse(policy.allows(CardSize(3, 4)))
        assertFalse(policy.allows(CardSize(5, 1)))
    }

    @Test
    fun `range snapping honors provider and anchored grid bounds`() {
        val policy = RangeCardSizePolicy(CardSize(2, 1), CardSize(6, 4))

        assertEquals(CardSize(4, 3), policy.snap(CardSize(8, 3), CardSize(4, 6)))
    }

    @Test
    fun `explicit policy rejects unspecified intermediate sizes`() {
        val policy = ExplicitCardSizePolicy(listOf(CardSize(2, 1), CardSize(4, 1)))

        assertTrue(policy.allows(CardSize(2, 1)))
        assertFalse(policy.allows(CardSize(3, 1)))
        assertTrue(policy.allows(CardSize(4, 1)))
    }
}
