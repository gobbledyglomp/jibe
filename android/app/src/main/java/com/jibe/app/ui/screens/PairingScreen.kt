package com.jibe.app.ui.screens

import android.os.Build

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
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
import com.jibe.app.ui.theme.RobotoMono

private sealed class PairingMotionTarget {
    data object Searching : PairingMotionTarget()

    data class Connecting(val host: String) : PairingMotionTarget()

    data class PinEntry(val host: String, val hint: String?) : PairingMotionTarget()

    data object ConnectedFlash : PairingMotionTarget()

    data class FailedCard(val reason: String) : PairingMotionTarget()

    data class PairingFailedCard(val reason: String, val guidance: String) : PairingMotionTarget()

    data class PairingUnavailableCard(val reason: String) : PairingMotionTarget()
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
                        if (state.hint == null) PairingMotionTarget.Connecting(state.host)
                        else PairingMotionTarget.PinEntry(state.host, state.hint)
                is ConnectionState.Connected -> PairingMotionTarget.ConnectedFlash
                is ConnectionState.Failed -> PairingMotionTarget.FailedCard(state.reason)
                is ConnectionState.PairingFailed ->
                        PairingMotionTarget.PairingFailedCard(state.reason, state.guidance)
                is ConnectionState.PairingUnavailable ->
                        PairingMotionTarget.PairingUnavailableCard(state.reason)
        }

/**
 * First-time pairing screen.
 *
 * Uses [ConnectionRepository.state] to drive discovery, connect, and PIN entry (PIN is printed
 * by the daemon when run in pairing mode).
 */
@Composable
fun PairingScreen(repository: ConnectionRepository, onPaired: () -> Unit, onOpenSettings: () -> Unit = {}) {
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
                                if (current.hint != null) {
                                        val hostChanged = current.host != lastPairingHost
                                        if (hostChanged) {
                                                lastPairingHost = current.host
                                        }
                                        if (hostChanged ||
                                                        current.hint.startsWith(
                                                                WRONG_PAIRING_PIN_HINT_PREFIX
                                                        )
                                        ) {
                                                pinValue = TextFieldValue("")
                                        }
                                        focusRequester.requestFocus()
                                        keyboardController?.show()
                                } else {
                                        pinValue = TextFieldValue("")
                                        keyboardController?.hide()
                                }
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
                Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
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
                        Text(
                                text = stringResource(R.string.app_brand),
                                style =
                                        MaterialTheme.typography.headlineLarge.copy(
                                                fontFamily = RobotoMono,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = (-1).sp
                                        ),
                                color = MaterialTheme.colorScheme.primary
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                                text = stringResource(R.string.pairing_tagline),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                                                text = stringResource(R.string.state_connected),
                                                                style =
                                                                        MaterialTheme.typography
                                                                                .titleMedium,
                                                                color = MaterialTheme.colorScheme.primary
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
                    IconButton(
                            onClick = onOpenSettings,
                            modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 16.dp, end = 8.dp)
                    ) {
                        Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = stringResource(R.string.settings_title),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                        )
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
                        text = stringResource(R.string.pairing_searching),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                        text = stringResource(R.string.pairing_run_hint),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = RobotoMono),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
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
                        text = stringResource(R.string.state_connecting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                        text = host,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = RobotoMono),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
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
                        text = stringResource(R.string.pairing_enter_pin),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (hint != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                                text = hint,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
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
                                                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                                                else
                                                                        MaterialTheme.colorScheme
                                                                                .surface
                                                        )
                                                        .border(
                                                                width = 1.dp,
                                                                color =
                                                                        if (isFilled)
                                                                                MaterialTheme.colorScheme.primary.copy(
                                                                                        alpha = 0.5f
                                                                                )
                                                                        else
                                                                                MaterialTheme.colorScheme.onSurfaceVariant
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
                                                        color = MaterialTheme.colorScheme.onSurface
                                                )
                                        } else {
                                                Box(
                                                        modifier =
                                                                Modifier.size(6.dp)
                                                                        .clip(CircleShape)
                                                                        .background(
                                                                                MaterialTheme.colorScheme.onSurfaceVariant
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
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                        disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                        disabledContentColor =
                                                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)
                                ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                        if (submitInFlight) {
                                CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                )
                        } else {
                                Text(
                                        text = stringResource(R.string.pairing_pair),
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
                        text = stringResource(R.string.state_pairing_failed),
                        style = MaterialTheme.typography.titleMedium,
                        color = JibeError
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                        text = reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(8.dp)
                ) { Text(stringResource(R.string.action_retry)) }
        }
}

@Composable
private fun PairingFailedIndicator(reason: String, guidance: String, onRetry: () -> Unit) {
        val accent = MaterialTheme.colorScheme.error
        val bodyText =
                guidance.ifBlank { stringResource(R.string.pairing_pin_rejected_body) }
        Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                shape = RoundedCornerShape(12.dp)
        ) {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                        Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                        ) {
                                Icon(
                                        imageVector = Icons.Outlined.Warning,
                                        contentDescription = null,
                                        modifier = Modifier.padding(top = 2.dp).size(22.dp),
                                        tint = accent.copy(alpha = 0.9f)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                                text = stringResource(R.string.pairing_pin_rejected),
                                                style = MaterialTheme.typography.titleSmall,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                                text = bodyText,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                lineHeight = 18.sp
                                        )
                                        if (reason.isNotBlank()) {
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Text(
                                                        text = reason,
                                                        style =
                                                                MaterialTheme.typography.labelSmall.copy(
                                                                        fontFamily = RobotoMono,
                                                                        lineHeight = 16.sp
                                                                ),
                                                        color =
                                                                MaterialTheme.colorScheme.onSurfaceVariant
                                                                        .copy(alpha = 0.72f),
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                )
                                        }
                                }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        OutlinedButton(
                                onClick = onRetry,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                        ) {
                                Text(
                                        stringResource(R.string.pairing_unavailable_retry),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary
                                )
                        }
                }
        }
}

@Composable
private fun PairingUnavailableIndicator(reason: String, onRetry: () -> Unit) {
        val accent = MaterialTheme.colorScheme.primary
        Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                shape = RoundedCornerShape(12.dp)
        ) {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                        Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                        ) {
                                Icon(
                                        imageVector = Icons.Outlined.Info,
                                        contentDescription = null,
                                        modifier = Modifier.padding(top = 2.dp).size(22.dp),
                                        tint = accent.copy(alpha = 0.9f)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                                text = stringResource(R.string.pairing_unavailable_title),
                                                style = MaterialTheme.typography.titleSmall,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                                text = stringResource(R.string.pairing_unavailable_body),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                lineHeight = 18.sp
                                        )
                                        if (reason.isNotBlank()) {
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Text(
                                                        text = reason,
                                                        style =
                                                                MaterialTheme.typography.labelSmall.copy(
                                                                        fontFamily = RobotoMono,
                                                                        lineHeight = 16.sp
                                                                ),
                                                        color =
                                                                MaterialTheme.colorScheme.onSurfaceVariant
                                                                        .copy(alpha = 0.72f),
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                )
                                        }
                                }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        OutlinedButton(
                                onClick = onRetry,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
                        ) {
                                Text(
                                        stringResource(R.string.pairing_unavailable_retry),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = accent
                                )
                        }
                }
        }
}
