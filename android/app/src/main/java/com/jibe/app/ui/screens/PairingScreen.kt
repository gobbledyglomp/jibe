package com.jibe.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * 1. Automatic NSD discovery (spinner)
 * 2. Daemon found → WebSocket connection
 * 3. PIN input field appears — user types the PIN shown on the daemon terminal
 * 4. Auth success → onPaired callback → navigate to Home
 *
 * Start the daemon with: python main.py --pair The PIN will appear in the daemon terminal output.
 */
@Composable
fun PairingScreen(repository: ConnectionRepository, onPaired: () -> Unit) {
        val state by repository.state.collectAsState()
        var pinValue by remember { mutableStateOf(TextFieldValue("")) }
        val focusRequester = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current

        LaunchedEffect(state) {
                if (state is ConnectionState.Authenticating) {
                        pinValue = TextFieldValue("")
                }
        }

        LaunchedEffect(state) {
                if (state is ConnectionState.Connected) {
                        onPaired()
                }
        }

        Scaffold(containerColor = MaterialTheme.colorScheme.surface) { _ ->
                Column(
                        modifier =
                                Modifier.fillMaxSize()
                                        .windowInsetsPadding(
                                                WindowInsets.navigationBars.union(WindowInsets.ime)
                                        )
                                        .padding(horizontal = 32.dp),
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
                                                is ConnectionState.Disconnected,
                                                is ConnectionState.Discovering -> {
                                                        DiscoveringIndicator()
                                                }
                                                is ConnectionState.Connecting -> {
                                                        ConnectingIndicator(
                                                                host = currentState.host
                                                        )
                                                }
                                                is ConnectionState.Authenticating -> {
                                                        PinInput(
                                                                value = pinValue,
                                                                hint = currentState.hint,
                                                                onValueChange = { newValue ->
                                                                        if (newValue.text.length <=
                                                                                        6 &&
                                                                                        newValue.text
                                                                                                .all {
                                                                                                        it.isDigit()
                                                                                                }
                                                                        ) {
                                                                                pinValue = newValue
                                                                        }
                                                                },
                                                                onSubmit = {
                                                                        if (pinValue.text.length ==
                                                                                        6
                                                                        ) {
                                                                                repository
                                                                                        .pairWithPin(
                                                                                                pin =
                                                                                                        pinValue.text,
                                                                                                deviceName =
                                                                                                        android.os
                                                                                                                .Build
                                                                                                                .MODEL
                                                                                        )
                                                                        }
                                                                },
                                                                focusRequester = focusRequester,
                                                                keyboardController =
                                                                        keyboardController
                                                        )
                                                        LaunchedEffect(currentState) {
                                                                focusRequester.requestFocus()
                                                                keyboardController?.show()
                                                        }
                                                }
                                                is ConnectionState.Connected -> {
                                                        Text(
                                                                text = "Connected",
                                                                style =
                                                                        MaterialTheme.typography
                                                                                .titleMedium,
                                                                color = JibePrimary
                                                        )
                                                }
                                                is ConnectionState.Failed -> {
                                                        FailedIndicator(
                                                                reason = currentState.reason,
                                                                onRetry = {
                                                                        pinValue =
                                                                                TextFieldValue("")
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

/** Arc spinner */
@Composable
private fun JibeSpinner(
        modifier: Modifier = Modifier,
        color: Color = JibePrimary,
        strokeWidth: Float = 4f
) {
        var angle by remember { mutableFloatStateOf(0f) }

        LaunchedEffect(Unit) {
                val periodNs = 900_000_000L // one full rotation every 900ms
                val startNs = withFrameNanos { it }
                while (true) {
                        withFrameNanos { frameNs ->
                                angle = ((frameNs - startNs) % periodNs).toFloat() / periodNs * 360f
                        }
                }
        }

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
private fun DiscoveringIndicator() {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                JibeSpinner(modifier = Modifier.size(32.dp))

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                        text = "Searching for daemon…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = JibeOnSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                        text = "Run: python main.py --pair",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = RobotoMono),
                        color = JibeOnSurfaceVariant.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center
                )
        }
}

@Composable
private fun ConnectingIndicator(host: String) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                JibeSpinner(modifier = Modifier.size(32.dp))

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
        value: TextFieldValue,
        hint: String?,
        onValueChange: (TextFieldValue) -> Unit,
        onSubmit: () -> Unit,
        focusRequester: FocusRequester,
        keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?
) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                        text = "Enter the PIN shown on your daemon",
                        style = MaterialTheme.typography.bodyMedium,
                        color = JibeOnSurfaceVariant
                )

                if (hint != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                                text = hint,
                                style = MaterialTheme.typography.labelSmall,
                                color = JibePrimary,
                                textAlign = TextAlign.Center
                        )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // ── Visual PIN boxes ────────────────────────────────────────

                Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier =
                                Modifier.fillMaxWidth().clickable(
                                                interactionSource =
                                                        remember { MutableInteractionSource() },
                                                indication = null
                                        ) {
                                        focusRequester.requestFocus()
                                        keyboardController?.show()
                                }
                ) {
                        for (i in 0 until 6) {
                                val char = value.text.getOrNull(i)
                                val isFilled = char != null

                                Box(
                                        modifier =
                                                Modifier.weight(1f)
                                                        .aspectRatio(
                                                                1f
                                                        ) // keep boxes square regardless of weight
                                                        // size
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(
                                                                if (isFilled)
                                                                        JibeSurfaceContainerHigh
                                                                else
                                                                        MaterialTheme.colorScheme
                                                                                .surface
                                                        )
                                                        .border(
                                                                width = 1.dp,
                                                                color =
                                                                        if (isFilled)
                                                                                JibePrimary.copy(
                                                                                        alpha = 0.5f
                                                                                )
                                                                        else
                                                                                JibeOnSurfaceVariant
                                                                                        .copy(
                                                                                                alpha =
                                                                                                        0.2f
                                                                                        ),
                                                                shape = RoundedCornerShape(8.dp)
                                                        ),
                                        contentAlignment = Alignment.Center
                                ) {
                                        if (isFilled) {
                                                Text(
                                                        text = char.toString(),
                                                        style =
                                                                MaterialTheme.typography
                                                                        .headlineSmall.copy(
                                                                        fontFamily = RobotoMono,
                                                                        fontWeight =
                                                                                FontWeight.Medium
                                                                ),
                                                        color = JibeOnSurface
                                                )
                                        } else {
                                                Box(
                                                        modifier =
                                                                Modifier.size(6.dp)
                                                                        .clip(CircleShape)
                                                                        .background(
                                                                                JibeOnSurfaceVariant
                                                                                        .copy(
                                                                                                alpha =
                                                                                                        0.15f
                                                                                        )
                                                                        )
                                                )
                                        }
                                }
                        }
                }

                // Invisible text field that captures keyboard input.
                // BasicTextField (no decorations) is more reliable than OutlinedTextField
                // at 1dp size — it doesn't fight Compose's focus system.
                BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.size(1.dp).focusRequester(focusRequester),
                        keyboardOptions =
                                KeyboardOptions(
                                        keyboardType = KeyboardType.NumberPassword,
                                        imeAction = ImeAction.Done
                                ),
                        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                        decorationBox = {}
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                        onClick = onSubmit,
                        enabled = value.text.length == 6,
                        colors =
                                ButtonDefaults.buttonColors(
                                        containerColor = JibePrimary,
                                        contentColor = MaterialTheme.colorScheme.surface,
                                        disabledContainerColor = JibePrimary.copy(alpha = 0.5f),
                                        disabledContentColor =
                                                MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                                ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                        Text(
                                text = "Pair",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(vertical = 4.dp)
                        )
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
