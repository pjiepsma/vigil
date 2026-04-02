package com.vigil.wear.presentation.navigation

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.vigil.wear.presentation.home.HomeScreen
import com.vigil.wear.presentation.session.ActiveSessionScreen
import com.vigil.wear.presentation.session.SessionSetupScreen
import com.vigil.wear.presentation.settings.SettingsScreen
import com.vigil.wear.session.SessionViewModel
import com.vigil.wear.session.VigilMode

/**
 * Single nav graph for Vigil. Each screen supplies its own [AppScaffold] / [ScreenScaffold] for Wear layout.
 */
@androidx.compose.runtime.Composable
fun VigilNavHost(viewModel: SessionViewModel) {
    val navController = rememberSwipeDismissableNavController()
    val uiState by viewModel.uiState.collectAsState()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    viewModel.syncFromServiceState()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uiState.isSessionRunning) {
        if (uiState.isSessionRunning) {
            navController.navigate(VigilRoutes.ACTIVE) {
                launchSingleTop = true
            }
        }
    }

    SwipeDismissableNavHost(
        navController = navController,
        startDestination = VigilRoutes.HOME,
        userSwipeEnabled = currentRoute != VigilRoutes.ACTIVE,
    ) {
        composable(VigilRoutes.HOME) {
            HomeScreen(
                viewModel = viewModel,
                onModeChosen = { mode ->
                    viewModel.selectMode(mode)
                    navController.navigate(VigilRoutes.sessionSetup(mode.id))
                },
                onOpenActiveSession = {
                    navController.navigate(VigilRoutes.ACTIVE) {
                        launchSingleTop = true
                    }
                },
                onOpenSettings = {
                    navController.navigate(VigilRoutes.SETTINGS)
                },
            )
        }
        composable(
            route = VigilRoutes.SESSION_SETUP,
            arguments =
                listOf(
                    navArgument("modeId") {
                        type = NavType.StringType
                    },
                ),
        ) {
            val modeId = it.arguments?.getString("modeId") ?: VigilMode.Passive.id
            val mode = VigilMode.fromId(modeId) ?: VigilMode.Passive
            SessionSetupScreen(
                mode = mode,
                viewModel = viewModel,
                onStarted = {
                    navController.navigate(VigilRoutes.ACTIVE) {
                        launchSingleTop = true
                        popUpTo(VigilRoutes.HOME) { saveState = true }
                    }
                },
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(VigilRoutes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(VigilRoutes.ACTIVE) {
            ActiveSessionScreen(
                viewModel = viewModel,
                onEndSession = {
                    viewModel.endSession()
                    navController.popBackStack(VigilRoutes.HOME, inclusive = false)
                },
            )
        }
    }
}
