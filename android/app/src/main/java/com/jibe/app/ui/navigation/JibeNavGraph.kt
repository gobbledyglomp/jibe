package com.jibe.app.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jibe.app.data.local.DeviceCredentials
import com.jibe.app.data.repository.ConnectionRepository
import com.jibe.app.ui.screens.HomeScreen
import com.jibe.app.ui.screens.PairingScreen
import kotlinx.coroutines.flow.StateFlow

/**
 * Root navigation graph — decides where the user starts based on whether saved credentials exist.
 *
 * No onboarding, no splash screen. The user lands directly on the screen that matters:
 * - Has credentials → Home (auto-reconnecting)
 * - No credentials → Pairing (discovery + PIN)
 *
 * @param credentialsFlow Reactive stream from DataStore — null means no saved device, non-null
 * means we have a paired daemon.
 * @param repository The connection state machine, shared between screens.
 */
@Composable
fun JibeNavGraph(credentialsFlow: StateFlow<DeviceCredentials?>, repository: ConnectionRepository) {
    val credentials by credentialsFlow.collectAsState(initial = null)
    val navController = rememberNavController()

    // Start destination depends on whether we have saved credentials
    val startRoute = if (credentials != null) Route.Home.path else Route.Pairing.path

    NavHost(
            navController = navController,
            startDestination = startRoute,
            enterTransition = { fadeIn() },
            exitTransition = { fadeOut() }
    ) {
        composable(Route.Pairing.path) {
            PairingScreen(
                    repository = repository,
                    onPaired = {
                        navController.navigate(Route.Home.path) {
                            popUpTo(Route.Pairing.path) { inclusive = true }
                        }
                    }
            )
        }

        composable(Route.Home.path) {
            HomeScreen(
                    repository = repository,
                    onDeviceForgotten = {
                        navController.navigate(Route.Pairing.path) {
                            popUpTo(Route.Home.path) { inclusive = true }
                        }
                    }
            )
        }
    }
}
