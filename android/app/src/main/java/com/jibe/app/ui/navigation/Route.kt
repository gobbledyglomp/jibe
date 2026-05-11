package com.jibe.app.ui.navigation

/**
 * Navigation routes — the single source of truth for screen destinations.
 *
 * Sealed class ensures exhaustive route handling. Each route is a singleton object — no arguments
 * needed at the route level since screen state comes from the ViewModel/Repository, not from nav
 * args.
 */
sealed class Route(val path: String) {
    /** Discovery + PIN pairing — shown when no saved credentials exist. */
    data object Pairing : Route("pairing")

    /** Dashboard — shown when authenticated with a saved device. */
    data object Home : Route("home")

    /** Presentation remote control (authenticated only). */
    data object Presentation : Route("presentation")

    /** App preferences (theme, language, feature toggles). */
    data object Settings : Route("settings")
}
