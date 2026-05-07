package com.jibe.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jibe.app.data.repository.ConnectionRepository
import com.jibe.app.data.repository.ConnectionState
import com.jibe.app.data.repository.PingResult
import com.jibe.app.ui.theme.JibeError
import com.jibe.app.ui.theme.JibeOnSurface
import com.jibe.app.ui.theme.JibeOnSurfaceVariant
import com.jibe.app.ui.theme.JibePrimary
import com.jibe.app.ui.theme.JibeSuccess
import com.jibe.app.ui.theme.JibeSurfaceContainer
import com.jibe.app.ui.theme.JibeWarning
import com.jibe.app.ui.theme.RobotoMono

/**
 * Home dashboard — the main screen after pairing.
 *
 * Shows:
 * - Connection status (live dot + text)
 * - Daemon info (host, device ID)
 * - Ping button with round-trip latency display
 * - "Forget device" destructive action
 */
@Composable
fun HomeScreen(repository: ConnectionRepository, onDeviceForgotten: () -> Unit) {
        val state by repository.state.collectAsState()
        var lastLatency by remember { mutableLongStateOf(-1L) }
        var showForgetConfirm by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
                repository.pingResults.collect { result: PingResult ->
                        lastLatency = result.latencyMs
                }
        }

        LaunchedEffect(state) { if (state is ConnectionState.Disconnected) {} }

        Scaffold(containerColor = MaterialTheme.colorScheme.surface) { innerPadding ->
                Column(
                        modifier =
                                Modifier.fillMaxSize()
                                        .padding(innerPadding)
                                        .padding(horizontal = 24.dp)
                                        .padding(top = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                ) {
                        // ── Header ──────────────────────────────────────────
                        Text(
                                text = "jibe",
                                style =
                                        MaterialTheme.typography.headlineLarge.copy(
                                                fontFamily = RobotoMono,
                                                fontWeight = FontWeight.Bold
                                        ),
                                color = JibePrimary
                        )

                        Spacer(modifier = Modifier.height(32.dp))

                        // ── Connection status card ──────────────────────────
                        ConnectionStatusCard(state = state)

                        Spacer(modifier = Modifier.height(16.dp))

                        // ── Ping card ───────────────────────────────────────
                        PingCard(
                                isConnected = state is ConnectionState.Connected,
                                lastLatency = lastLatency,
                                onPing = { repository.sendPing() }
                        )

                        Spacer(modifier = Modifier.weight(1f))

                        // ── Forget device ───────────────────────────────────
                        if (showForgetConfirm) {
                                ForgetConfirmation(
                                        onConfirm = {
                                                repository.forgetDevice()
                                                onDeviceForgotten()
                                        },
                                        onCancel = { showForgetConfirm = false }
                                )
                        } else {
                                TextButton(onClick = { showForgetConfirm = true }) {
                                        Text(
                                                text = "Forget device",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = JibeOnSurfaceVariant.copy(alpha = 0.5f)
                                        )
                                }
                        }

                        Spacer(modifier = Modifier.height(32.dp))
                }
        }
}

// ── Sub-components ──────────────────────────────────────────────────

/**
 * Coroutine-driven arc spinner — works even when Animator Duration Scale is 0x. Identical to the
 * one in PairingScreen; extracted here to avoid importing across screen files (they share no module
 * boundary yet).
 */
@Composable
private fun JibeSpinner(
        modifier: Modifier = Modifier,
        color: Color = JibePrimary,
        strokeWidth: Float = 3f
) {
        val infiniteTransition = rememberInfiniteTransition(label = "spinner")
        val angle by
                infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec =
                                infiniteRepeatable(
                                        animation =
                                                tween(durationMillis = 900, easing = LinearEasing)
                                ),
                        label = "spin_angle"
                )
        Canvas(modifier = modifier) {
                val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                val inset = strokeWidth / 2
                drawArc(
                        color = color.copy(alpha = 0.15f),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - strokeWidth, size.height - strokeWidth),
                        style = stroke
                )
                drawArc(
                        color = color,
                        startAngle = angle,
                        sweepAngle = 260f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - strokeWidth, size.height - strokeWidth),
                        style = stroke
                )
        }
}

@Composable
private fun ConnectionStatusCard(state: ConnectionState) {
        val statusColor by
                animateColorAsState(
                        targetValue =
                                when (state) {
                                        is ConnectionState.Connected -> JibeSuccess
                                        is ConnectionState.Connecting,
                                        is ConnectionState.Authenticating,
                                        is ConnectionState.Discovering -> JibeWarning
                                        is ConnectionState.Failed -> JibeError
                                        is ConnectionState.Disconnected ->
                                                JibeOnSurfaceVariant.copy(alpha = 0.3f)
                                },
                        label = "status_color"
                )

        Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = JibeSurfaceContainer),
                shape = RoundedCornerShape(12.dp)
        ) {
                Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                        modifier =
                                                Modifier.size(8.dp)
                                                        .clip(CircleShape)
                                                        .background(statusColor)
                                )

                                Spacer(modifier = Modifier.width(10.dp))

                                AnimatedContent(
                                        targetState = state,
                                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                                        label = "status_text"
                                ) { currentState ->
                                        Text(
                                                text =
                                                        when (currentState) {
                                                                is ConnectionState.Connected ->
                                                                        "Connected"
                                                                is ConnectionState.Connecting ->
                                                                        "Connecting…"
                                                                is ConnectionState.Authenticating ->
                                                                        "Authenticating…"
                                                                is ConnectionState.Discovering ->
                                                                        "Discovering…"
                                                                is ConnectionState.Failed ->
                                                                        "Failed"
                                                                is ConnectionState.Disconnected ->
                                                                        "Disconnected"
                                                        },
                                                style = MaterialTheme.typography.titleMedium,
                                                color = JibeOnSurface
                                        )
                                }

                                if (state is ConnectionState.Connecting ||
                                                state is ConnectionState.Authenticating ||
                                                state is ConnectionState.Discovering
                                ) {
                                        Spacer(modifier = Modifier.width(12.dp))
                                        JibeSpinner(modifier = Modifier.size(16.dp))
                                }
                        }

                        val host =
                                when (state) {
                                        is ConnectionState.Connected -> state.host
                                        is ConnectionState.Connecting -> state.host
                                        is ConnectionState.Authenticating -> state.host
                                        else -> null
                                }

                        if (host != null) {
                                Spacer(modifier = Modifier.height(12.dp))

                                Row {
                                        Text(
                                                text = "Host",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = JibeOnSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                                text = host,
                                                style =
                                                        MaterialTheme.typography.labelSmall.copy(
                                                                fontFamily = RobotoMono
                                                        ),
                                                color = JibeOnSurface
                                        )
                                }
                        }

                        if (state is ConnectionState.Connected) {
                                Spacer(modifier = Modifier.height(4.dp))

                                Row {
                                        Text(
                                                text = "Device",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = JibeOnSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                                text = state.deviceId,
                                                style =
                                                        MaterialTheme.typography.labelSmall.copy(
                                                                fontFamily = RobotoMono
                                                        ),
                                                color = JibeOnSurface,
                                                maxLines = 1
                                        )
                                }
                        }

                        if (state is ConnectionState.Failed) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                        text = state.reason,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = JibeError.copy(alpha = 0.8f)
                                )
                        }
                }
        }
}

@Composable
private fun PingCard(isConnected: Boolean, lastLatency: Long, onPing: () -> Unit) {
        Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = JibeSurfaceContainer),
                shape = RoundedCornerShape(12.dp)
        ) {
                Row(
                        modifier = Modifier.padding(20.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                ) {
                        Column {
                                Text(
                                        text = "Ping",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = JibeOnSurface
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                        text = if (lastLatency >= 0) "${lastLatency}ms" else "—",
                                        style =
                                                MaterialTheme.typography.headlineSmall.copy(
                                                        fontFamily = RobotoMono,
                                                        fontWeight = FontWeight.Medium
                                                ),
                                        color =
                                                if (lastLatency >= 0) JibePrimary
                                                else JibeOnSurfaceVariant
                                )
                        }

                        OutlinedButton(
                                onClick = onPing,
                                enabled = isConnected,
                                shape = RoundedCornerShape(8.dp),
                                colors =
                                        ButtonDefaults.outlinedButtonColors(
                                                contentColor = JibePrimary
                                        )
                        ) { Text(text = "Send", style = MaterialTheme.typography.labelLarge) }
                }
        }
}

@Composable
private fun ForgetConfirmation(onConfirm: () -> Unit, onCancel: () -> Unit) {
        Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = JibeSurfaceContainer),
                shape = RoundedCornerShape(12.dp)
        ) {
                Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                ) {
                        Text(
                                text = "Forget this device?",
                                style = MaterialTheme.typography.titleMedium,
                                color = JibeOnSurface
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                                text = "You'll need to re-enter the PIN to reconnect.",
                                style = MaterialTheme.typography.bodySmall,
                                color = JibeOnSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(
                                        onClick = onCancel,
                                        shape = RoundedCornerShape(8.dp)
                                ) { Text("Cancel") }

                                Button(
                                        onClick = onConfirm,
                                        colors =
                                                ButtonDefaults.buttonColors(
                                                        containerColor = JibeError
                                                ),
                                        shape = RoundedCornerShape(8.dp)
                                ) { Text("Forget") }
                        }
                }
        }
}
