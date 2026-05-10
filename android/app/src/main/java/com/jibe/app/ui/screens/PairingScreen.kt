package com.jibe.app.ui.screens

import android.os.Build

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jibe.app.R
import com.jibe.app.data.repository.ConnectionRepository
import com.jibe.app.data.repository.ConnectionState
import com.jibe.app.data.repository.DEFAULT_DEVICE_DISPLAY_NAME
import com.jibe.app.data.repository.WRONG_PAIRING_PIN_HINT_PREFIX
import com.jibe.app.ui.components.JibeSpinner
import com.jibe.app.ui.theme.JibeError
import com.jibe.app.ui.theme.JibeOnSurface
import com.jibe.app.ui.theme.JibeOnSurfaceVariant
import com.jibe.app.ui.theme.JibePrimary
import com.jibe.app.ui.theme.JibeSurfaceContainerHigh
import com.jibe.app.ui.theme.RobotoMono

private sealed class PairingMotionTarget {
    data object Searching : PairingMotionTarget()

    data class Connecting(val host: String) : PairingMotionTarget()

    data class PinEntry(val host: String, val hint: String?) : PairingMotionTarget()

    data object ConnectedFlash : PairingMotionTarget()

    data class FailedCard(val reason: String) : PairingMotionTarget()

    data class PairingFailedCard(val reason: String, val guidance: String) : PairingMotionTarget()

    data class PairingUnavailableCard(val reason: String, val guidance: String) :
            PairingMotionTarget()
}

private fun pairingMotionTarget(
        state: ConnectionState,
        lockoutProbeUi: Boolean
): PairingMotionTarget =
        when (state) {
                ConnectionState.Disconnected,
                ConnectionState.Discovering -> PairingMotionTarget.Searching
                is ConnectionState.Connecting ->
                        if (lockoutProbeUi) PairingMotionTarget.Searching
                        else PairingMotionTarget.Connecting(state.host)
                is ConnectionState.Authenticating ->
                        PairingMotionTarget.PinEntry(state.host, state.hint)
                is ConnectionState.Connected -> PairingMotionTarget.ConnectedFlash
                is ConnectionState.Failed -> PairingMotionTarget.FailedCard(state.reason)
                is ConnectionState.PairingFailed ->
                        PairingMotionTarget.PairingFailedCard(state.reason, state.guidance)
                is ConnectionState.PairingUnavailable ->
                        PairingMotionTarget.PairingUnavailableCard(state.reason, state.guidance)
        }

/**
 * First-time pairing screen.
 *
 * Uses [ConnectionRepository.state] to drive discovery, connect, and PIN entry (PIN is printed
 * by the daemon when run in pairing mode).
 */
@Composable
fun PairingScreen(repository: ConnectionRepository, onPaired: () -> Unit) {
        val state by repository.state.collectAsState()
        val pairSubmitInFlight by repository.pairSubmitInFlight.collectAsState()
        val pairingLockoutProbeUi by repository.pairingLockoutProbeUi.collectAsState()
        var pinValue by remember { mutableStateOf(TextFieldValue("")) }
        val focusRequester = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current
        var lastPairingHost by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(state) {
                val current = state
                when {
                        current is ConnectionState.Authenticating -> {
                                val hostChanged = current.host != lastPairingHost
                                if (hostChanged) {
                                        lastPairingHost = current.host
                                }
                                if (hostChanged ||
                                                current.hint?.startsWith(
                                                        WRONG_PAIRING_PIN_HINT_PREFIX
                                                ) == true
                                ) {
                                        pinValue = TextFieldValue("")
                                }
                                focusRequester.requestFocus()
                                keyboardController?.show()
                        }
                        current is ConnectionState.PairingUnavailable -> {
                                pinValue = TextFieldValue("")
                                keyboardController?.hide()
                        }
                        current is ConnectionState.Connected -> {
                                keyboardController?.hide()
                                onPaired()
                        }
                        else -> {
                                keyboardController?.hide()
                        }
                }
        }

        Scaffold(containerColor = MaterialTheme.colorScheme.surface) { innerPadding ->
                Column(
                        modifier =
                                Modifier.fillMaxSize()
                                        .padding(innerPadding)
                                        .windowInsetsPadding(
                                                WindowInsets.navigationBars.union(WindowInsets.ime)
                                        )
                                        .padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                ) {
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

                        AnimatedContent(
                                targetState =
                                        pairingMotionTarget(state, pairingLockoutProbeUi),
                                transitionSpec = { fadeIn() togetherWith fadeOut() },
                                label = "pairing_state"
                        ) { motion ->
                                Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.fillMaxWidth()
                                ) {
                                        when (motion) {
                                                PairingMotionTarget.Searching -> {
                                                        DiscoveringIndicator()
                                                }
                                                is PairingMotionTarget.Connecting -> {
                                                        ConnectingIndicator(host = motion.host)
                                                }
                                                is PairingMotionTarget.PinEntry -> {
                                                        PinInput(
                                                                value = pinValue,
                                                                hint = motion.hint,
                                                                submitInFlight = pairSubmitInFlight,
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
                                                                                repository.pairWithPin(
                                                                                        pin =
                                                                                                pinValue.text,
                                                                                        deviceName =
                                                                                                Build.MODEL
                                                                                                        ?.takeIf {
                                                                                                                it.isNotBlank()
                                                                                                        }
                                                                                                        ?: DEFAULT_DEVICE_DISPLAY_NAME
                                                                                )
                                                                        }
                                                                },
                                                                focusRequester = focusRequester,
                                                                keyboardController =
                                                                        keyboardController
                                                        )
                                                }
                                                PairingMotionTarget.ConnectedFlash -> {
                                                        Text(
                                                                text = "Connected",
                                                                style =
                                                                        MaterialTheme.typography
                                                                                .titleMedium,
                                                                color = JibePrimary
                                                        )
                                                }
                                                is PairingMotionTarget.FailedCard -> {
                                                        FailedIndicator(
                                                                reason = motion.reason,
                                                                onRetry = {
                                                                        pinValue =
                                                                                TextFieldValue("")
                                                                        repository.retryPairing()
                                                                }
                                                        )
                                                }
                                                is PairingMotionTarget.PairingFailedCard -> {
                                                        PairingFailedIndicator(
                                                                reason = motion.reason,
                                                                guidance = motion.guidance,
                                                                onRetry = {
                                                                        pinValue =
                                                                                TextFieldValue("")
                                                                        repository.retryPairing(
                                                                                afterPairingLockout =
                                                                                        true
                                                                        )
                                                                }
                                                        )
                                                }
                                                is PairingMotionTarget.PairingUnavailableCard -> {
                                                        PairingUnavailableIndicator(
                                                                reason = motion.reason,
                                                                guidance = motion.guidance,
                                                                onRetry = {
                                                                        pinValue =
                                                                                TextFieldValue("")
                                                                        repository.retryPairing()
                                                                }
                                                        )
                                                }
                                        }
                                }
                        }
                }
        }
}

@Composable
private fun DiscoveringIndicator() {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                JibeSpinner(modifier = Modifier.size(32.dp), strokeWidth = 4f)

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
                JibeSpinner(modifier = Modifier.size(32.dp), strokeWidth = 4f)

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
        submitInFlight: Boolean,
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
                                val slotDescription =
                                        if (char != null) {
                                                stringResource(
                                                        R.string.accessibility_pin_slot_filled,
                                                        i + 1,
                                                        char.toString()
                                                )
                                        } else {
                                                stringResource(
                                                        R.string.accessibility_pin_slot_empty,
                                                        i + 1
                                                )
                                        }

                                Box(
                                        modifier =
                                                Modifier.weight(1f)
                                                        .aspectRatio(
                                                                1f
                                                        ) // keep boxes square regardless of weight
                                                        .semantics(mergeDescendants = true) {
                                                                contentDescription = slotDescription
                                                        }
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

                // Capture keyboard input without visible decorations.
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
                        enabled = value.text.length == 6 && !submitInFlight,
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
                        if (submitInFlight) {
                                CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        strokeWidth = 2.dp
                                )
                        } else {
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
                        color = JibeError
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

@Composable
private fun PairingFailedIndicator(reason: String, guidance: String, onRetry: () -> Unit) {
        Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = JibeSurfaceContainerHigh),
                shape = RoundedCornerShape(16.dp)
        ) {
                Column(
                        modifier = Modifier.padding(20.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                ) {
                        Text(
                                text = "PIN rejected",
                                style = MaterialTheme.typography.titleMedium,
                                color = JibeOnSurface,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                                text = reason,
                                style = MaterialTheme.typography.bodyMedium,
                                color = JibeOnSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                                text = guidance,
                                style = MaterialTheme.typography.bodySmall,
                                color = JibeOnSurfaceVariant.copy(alpha = 0.8f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        Button(
                                onClick = onRetry,
                                colors =
                                        ButtonDefaults.buttonColors(
                                                containerColor = JibeError.copy(alpha = 0.14f),
                                                contentColor = JibeError
                                        ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                        ) {
                                Text("Retry")
                        }
                }
        }
}

@Composable
private fun PairingUnavailableIndicator(
        reason: String,
        guidance: String,
        onRetry: () -> Unit
) {
        Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = JibeSurfaceContainerHigh),
                shape = RoundedCornerShape(16.dp)
        ) {
                Column(
                        modifier = Modifier.padding(20.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                ) {
                        Text(
                                text = "Pairing mode is not active",
                                style = MaterialTheme.typography.titleMedium,
                                color = JibeOnSurface,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                                text = reason,
                                style = MaterialTheme.typography.bodyMedium,
                                color = JibeOnSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                                text = guidance,
                                style = MaterialTheme.typography.bodySmall,
                                color = JibeOnSurfaceVariant.copy(alpha = 0.8f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        Button(
                                onClick = onRetry,
                                colors =
                                        ButtonDefaults.buttonColors(
                                                containerColor = JibePrimary.copy(alpha = 0.14f),
                                                contentColor = JibePrimary
                                        ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                        ) {
                                Text("Retry")
                        }
                }
        }
}
