package com.dormpanel.app.dashboard.card

import android.content.Context
import android.view.View
import com.dormpanel.app.dashboard.model.CardSize
import com.dormpanel.app.dashboard.model.ExplicitCardSizePolicy
import com.dormpanel.app.dashboard.model.PlacedCard
import org.junit.Assert.assertThrows
import org.junit.Test

class DashboardCardRegistryTest {
    @Test
    fun `provider default size must satisfy its policy`() {
        val invalidProvider = object : DashboardCardProvider {
            override val typeKey = "invalid"
            override val displayMetadata = CardDisplayMetadata("Invalid", "Test provider")
            override val sizePolicy = ExplicitCardSizePolicy(listOf(CardSize(2, 1)))
            override val defaultSize = CardSize(3, 1)

            override fun createView(context: Context): View = error("Not used")
            override fun bind(view: View, card: PlacedCard, interactions: CardInteractionScope) = Unit
        }

        assertThrows(IllegalArgumentException::class.java) {
            DashboardCardRegistry(listOf(invalidProvider))
        }
    }
}
