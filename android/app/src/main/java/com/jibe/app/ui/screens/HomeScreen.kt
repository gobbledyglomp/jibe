package com.jibe.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jibe.app.data.repository.ConnectionRepository

/**
 * Home dashboard — shown when authenticated.
 *
 * Stub: will be implemented in feat(android): build home screen.
 */
@Composable
fun HomeScreen(repository: ConnectionRepository, onDeviceForgotten: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Home — stub")
    }
}
