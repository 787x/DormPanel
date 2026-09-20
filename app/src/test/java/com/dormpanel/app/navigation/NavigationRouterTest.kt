package com.dormpanel.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationRouterTest {
    private val router = NavigationRouter.default()

    @Test
    fun `home routes in all four directions`() {
        assertEquals(PanelPage.APPS, router.destination(PanelPage.HOME, SwipeDirection.UP))
        assertEquals(PanelPage.CONTROL_CENTER, router.destination(PanelPage.HOME, SwipeDirection.DOWN))
        assertEquals(PanelPage.CALENDAR, router.destination(PanelPage.HOME, SwipeDirection.LEFT))
        assertEquals(PanelPage.HOME_CONTROL, router.destination(PanelPage.HOME, SwipeDirection.RIGHT))
    }

    @Test
    fun `each destination has its inverse route home`() {
        assertEquals(PanelPage.HOME, router.destination(PanelPage.APPS, SwipeDirection.DOWN))
        assertEquals(PanelPage.HOME, router.destination(PanelPage.CONTROL_CENTER, SwipeDirection.UP))
        assertEquals(PanelPage.HOME, router.destination(PanelPage.CALENDAR, SwipeDirection.RIGHT))
        assertEquals(PanelPage.HOME, router.destination(PanelPage.HOME_CONTROL, SwipeDirection.LEFT))
    }

    @Test
    fun `unsupported swipe has no destination`() {
        assertNull(router.destination(PanelPage.APPS, SwipeDirection.LEFT))
    }

    @Test
    fun `custom routes can replace the default mapping`() {
        val custom = NavigationRouter(
            initialPage = PanelPage.HOME,
            routes = listOf(
                NavigationRoute(PanelPage.HOME, SwipeDirection.UP, PanelPage.CALENDAR),
            ),
        )

        assertEquals(PanelPage.CALENDAR, custom.destination(PanelPage.HOME, SwipeDirection.UP))
        assertNull(custom.destination(PanelPage.HOME, SwipeDirection.LEFT))
    }
}
