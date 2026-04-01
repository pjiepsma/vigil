package com.vigil.wear.presentation.navigation

/**
 * String routes for [androidx.wear.compose.navigation.SwipeDismissableNavHost].
 * Keeping them in one object avoids typos across composables.
 */
object VigilRoutes {
    const val HOME = "home"
    const val ACTIVE = "active"
    const val SETTINGS = "settings"
    const val SESSION_SETUP = "session_setup/{modeId}"

    fun sessionSetup(modeId: String): String = "session_setup/$modeId"
}
