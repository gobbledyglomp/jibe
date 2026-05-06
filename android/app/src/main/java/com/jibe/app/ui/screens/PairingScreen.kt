package com.jibe.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jibe.app.data.repository.ConnectionRepository

/**
 * Discovery & PIN pairing screen — shown on first launch.
 *
 * Stub: will be implemented in feat(android): build discovery and pairing ui.
 */
@Composable
fun PairingScreen(repository: ConnectionRepository, onPaired: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Pairing — stub")
    }
}
