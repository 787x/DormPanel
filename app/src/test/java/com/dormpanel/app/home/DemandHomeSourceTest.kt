package com.dormpanel.app.home

import org.junit.Assert.*
import org.junit.Test

class DemandHomeSourceTest {
    private class Source : HomeControlSource {
        override var homeState = HomeControlState()
        val listeners = linkedSetOf<(HomeControlState) -> Unit>()
        var adds = 0; var removes = 0
        override fun addHomeListener(listener: (HomeControlState) -> Unit) { adds++; listeners += listener; listener(homeState) }
        override fun removeHomeListener(listener: (HomeControlState) -> Unit) { removes++; listeners -= listener }
        override fun activateEntity(id: String) = true
    }
    @Test fun subscriptionsSwitchExactlyOnceAndUnsubscribedReadsAreFresh() {
        val demo = Source(); val ha = Source(); val relay = DemandHomeSource(demo)
        assertEquals(0, demo.adds)
        demo.homeState = HomeControlState(connected = true)
        assertEquals(demo.homeState, relay.homeState)
        var firstCalls = 0; var secondCalls = 0
        val first: (HomeControlState) -> Unit = { firstCalls++ }
        val second: (HomeControlState) -> Unit = { secondCalls++ }
        relay.addHomeListener(first); relay.addHomeListener(first); relay.addHomeListener(second)
        assertEquals(1, demo.adds); assertEquals(1, firstCalls); assertEquals(1, secondCalls)
        relay.activate(ha); relay.activate(ha)
        assertEquals(1, demo.removes); assertEquals(1, ha.adds)
        assertEquals(2, firstCalls); assertEquals(2, secondCalls)
        relay.removeHomeListener(first); assertEquals(0, ha.removes)
        relay.removeHomeListener(second); assertEquals(1, ha.removes)
        ha.homeState = HomeControlState(areas = listOf(HomeArea("fresh", "Fresh")))
        assertEquals(ha.homeState, relay.homeState)
        relay.activate(demo); assertEquals(1, demo.adds)
        relay.addHomeListener(first); assertEquals(2, demo.adds)
        relay.close(); assertEquals(2, demo.removes)
    }
}
