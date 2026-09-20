package com.dormpanel.app.navigation

data class NavigationRoute(
    val from: PanelPage,
    val direction: SwipeDirection,
    val destination: PanelPage,
)

/**
 * Resolves page-to-page navigation without coupling gesture handling to page rendering.
 * A future settings layer can provide a different route collection without changing the UI.
 */
class NavigationRouter(
    val initialPage: PanelPage,
    routes: Collection<NavigationRoute>,
) {
    private val routesByGesture = routes.associateBy { it.from to it.direction }

    fun destination(from: PanelPage, direction: SwipeDirection): PanelPage? =
        routesByGesture[from to direction]?.destination

    fun direction(from: PanelPage, destination: PanelPage): SwipeDirection? =
        routesByGesture.values.firstOrNull {
            it.from == from && it.destination == destination
        }?.direction

    companion object {
        fun default(): NavigationRouter = NavigationRouter(
            initialPage = PanelPage.HOME,
            routes = listOf(
                NavigationRoute(PanelPage.HOME, SwipeDirection.UP, PanelPage.APPS),
                NavigationRoute(PanelPage.HOME, SwipeDirection.DOWN, PanelPage.CONTROL_CENTER),
                NavigationRoute(PanelPage.HOME, SwipeDirection.LEFT, PanelPage.CALENDAR),
                NavigationRoute(PanelPage.HOME, SwipeDirection.RIGHT, PanelPage.HOME_CONTROL),
                NavigationRoute(PanelPage.APPS, SwipeDirection.DOWN, PanelPage.HOME),
                NavigationRoute(PanelPage.CONTROL_CENTER, SwipeDirection.UP, PanelPage.HOME),
                NavigationRoute(PanelPage.CALENDAR, SwipeDirection.RIGHT, PanelPage.HOME),
                NavigationRoute(PanelPage.HOME_CONTROL, SwipeDirection.LEFT, PanelPage.HOME),
            ),
        )
    }
}
