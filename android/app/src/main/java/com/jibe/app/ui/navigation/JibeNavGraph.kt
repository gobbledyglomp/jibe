package com.jibe.app.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jibe.app.data.local.DeviceCredentials
import com.jibe.app.data.local.JibeDataStore
import com.jibe.app.data.repository.ConnectionRepository
import com.jibe.app.ui.screens.HomeScreen
import com.jibe.app.ui.screens.PairingScreen
import com.jibe.app.ui.screens.PresentationScreen
import com.jibe.app.ui.screens.SettingsScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

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
fun JibeNavGraph(
        credentialsFlow: Flow<DeviceCredentials?>,
        repository: ConnectionRepository,
        dataStore: JibeDataStore,
) {
    /** Wait for DataStore's first emission so startDestination matches persisted credentials. */
    var bootstrapCredentials by remember { mutableStateOf<DeviceCredentials?>(null) }
    var bootstrapDone by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        bootstrapCredentials = credentialsFlow.first()
        bootstrapDone = true
    }

    if (!bootstrapDone) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    val navController = rememberNavController()

    val startRoute = if (bootstrapCredentials != null) Route.Home.path else Route.Pairing.path

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
                    },
                    onOpenSettings = { navController.navigate(Route.Settings.path) },
            )
        }

        composable(Route.Home.path) {
            HomeScreen(
                    repository = repository,
                    dataStore = dataStore,
                    onDeviceForgotten = {
                        navController.navigate(Route.Pairing.path) {
                            popUpTo(Route.Home.path) { inclusive = true }
                        }
                    },
                    onOpenSettings = { navController.navigate(Route.Settings.path) },
                    onOpenPresentation = { navController.navigate(Route.Presentation.path) },
            )
        }

        composable(Route.Presentation.path) {
            PresentationScreen(
                    repository = repository,
                    onBack = { navController.popBackStack() },
            )
        }

        composable(Route.Settings.path) {
            SettingsScreen(
                    dataStore = dataStore,
                    onBack = { navController.popBackStack() },
            )
        }
    }
}
