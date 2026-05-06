package com.jibe.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jibe.app.data.repository.ConnectionRepository
import com.jibe.app.data.repository.ConnectionState
import com.jibe.app.ui.theme.JibeOnSurface
import com.jibe.app.ui.theme.JibeOnSurfaceVariant
import com.jibe.app.ui.theme.JibePrimary
import com.jibe.app.ui.theme.JibeSurfaceContainerHigh
import com.jibe.app.ui.theme.RobotoMono

/**
 * Discovery & PIN pairing screen — the first screen new users see.
 *
 * Flow:
 * 1. Automatic NSD discovery (scanning animation)
 * 2. Daemon found → WebSocket connection
 * 3. PIN input field appears
 * 4. User enters 6-digit PIN from daemon
 * 5. Auth success → onPaired callback → navigate to Home
 *
 * No carousel, no tutorial. Drop the user straight into discovery.
 */
@Composable
fun PairingScreen(repository: ConnectionRepository, onPaired: () -> Unit) {
    val state by repository.state.collectAsState()
    var pinValue by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    // Navigate to Home when authentication succeeds
    LaunchedEffect(state) {
        if (state is ConnectionState.Connected) {
            onPaired()
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { innerPadding ->
        Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
        ) {
            // ── Header ──────────────────────────────────────────────
            Text(
                    text = "jibe",
                    style =
                            MaterialTheme.typography.headlineLarge.copy(
                                    fontFamily = RobotoMono,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-1).sp
                            ),
                    color = JibePrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                    text = "Android ↔ Linux",
                    style = MaterialTheme.typography.bodyMedium,
                    color = JibeOnSurfaceVariant
            )

            Spacer(modifier = Modifier.height(48.dp))

            // ── State-driven content ────────────────────────────────
            AnimatedContent(
                    targetState = state,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "pairing_state"
            ) { currentState ->
                Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                ) {
                    when (currentState) {
                        is ConnectionState.Disconnected, is ConnectionState.Discovering -> {
                            DiscoveringIndicator()
                        }
                        is ConnectionState.Connecting -> {
                            ConnectingIndicator(host = currentState.host)
                        }
                        is ConnectionState.Authenticating -> {
                            PinInput(
                                    value = pinValue,
                                    onValueChange = { newValue ->
                                        if (newValue.length <= 6 && newValue.all { it.isDigit() }) {
                                            pinValue = newValue
                                        }
                                    },
                                    onSubmit = {
                                        if (pinValue.length == 6) {
                                            repository.pairWithPin(
                                                    pin = pinValue,
                                                    deviceName = android.os.Build.MODEL
                                            )
                                        }
                                    },
                                    focusRequester = focusRequester
                            )

                            LaunchedEffect(Unit) { focusRequester.requestFocus() }
                        }
                        is ConnectionState.Connected -> {
                            Text(
                                    text = "Connected",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = JibePrimary
                            )
                        }
                        is ConnectionState.Failed -> {
                            FailedIndicator(
                                    reason = currentState.reason,
                                    onRetry = {
                                        pinValue = ""
                                        repository.startDiscovery()
                                    }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Sub-components ──────────────────────────────────────────────────

@Composable
private fun DiscoveringIndicator() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = JibePrimary,
                strokeWidth = 2.dp
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
                text = "Searching for daemon…",
                style = MaterialTheme.typography.bodyMedium,
                color = JibeOnSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
                text = "Make sure the daemon is running on your Linux machine",
                style = MaterialTheme.typography.labelSmall,
                color = JibeOnSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ConnectingIndicator(host: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = JibePrimary,
                strokeWidth = 2.dp
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
                text = "Connecting…",
                style = MaterialTheme.typography.bodyMedium,
                color = JibeOnSurfaceVariant
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
                text = host,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = RobotoMono),
                color = JibeOnSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun PinInput(
        value: String,
        onValueChange: (String) -> Unit,
        onSubmit: () -> Unit,
        focusRequester: FocusRequester
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
                text = "Enter the PIN shown on your daemon",
                style = MaterialTheme.typography.bodyMedium,
                color = JibeOnSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ── Visual PIN boxes ────────────────────────────────────
        Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            for (i in 0 until 6) {
                val char = value.getOrNull(i)
                val isFilled = char != null

                Box(
                        modifier =
                                Modifier.size(48.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                                if (isFilled) JibeSurfaceContainerHigh
                                                else MaterialTheme.colorScheme.surface
                                        )
                                        .border(
                                                width = 1.dp,
                                                color =
                                                        if (isFilled) JibePrimary.copy(alpha = 0.5f)
                                                        else
                                                                JibeOnSurfaceVariant.copy(
                                                                        alpha = 0.2f
                                                                ),
                                                shape = RoundedCornerShape(8.dp)
                                        ),
                        contentAlignment = Alignment.Center
                ) {
                    if (isFilled) {
                        Text(
                                text = char.toString(),
                                style =
                                        MaterialTheme.typography.headlineSmall.copy(
                                                fontFamily = RobotoMono,
                                                fontWeight = FontWeight.Medium
                                        ),
                                color = JibeOnSurface
                        )
                    } else {
                        Box(
                                modifier =
                                        Modifier.size(6.dp)
                                                .clip(CircleShape)
                                                .background(
                                                        JibeOnSurfaceVariant.copy(alpha = 0.15f)
                                                )
                        )
                    }
                }
            }
        }

        OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.size(1.dp).focusRequester(focusRequester),
                keyboardOptions =
                        KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                                imeAction = ImeAction.Done
                        ),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                colors =
                        OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.surface,
                                unfocusedBorderColor = MaterialTheme.colorScheme.surface
                        )
        )

        Spacer(modifier = Modifier.height(24.dp))

        AnimatedVisibility(visible = value.length == 6) {
            Button(
                    onClick = onSubmit,
                    colors = ButtonDefaults.buttonColors(containerColor = JibePrimary),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)
            ) {
                Text(
                        text = "Pair",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun FailedIndicator(reason: String, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
                text = "Pairing failed",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = JibeOnSurfaceVariant,
                textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = JibePrimary),
                shape = RoundedCornerShape(8.dp)
        ) { Text("Retry") }
    }
}

private val Int.sp
    get() =
            androidx.compose.ui.unit.TextUnit(
                    this.toFloat(),
                    androidx.compose.ui.unit.TextUnitType.Sp
            )
