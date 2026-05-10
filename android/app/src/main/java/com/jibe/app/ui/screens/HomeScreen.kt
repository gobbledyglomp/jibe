package com.jibe.app.ui.screens

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jibe.app.R
import com.jibe.app.data.repository.ConnectionRepository
import com.jibe.app.data.repository.ConnectionState
import com.jibe.app.data.repository.ClipboardTextReader
import com.jibe.app.data.repository.PingResult
import com.jibe.app.data.repository.TransferProgress
import com.jibe.app.ui.components.JibeSpinner
import com.jibe.app.ui.theme.JibeError
import com.jibe.app.ui.theme.JibeOnSurface
import com.jibe.app.ui.theme.JibeOnSurfaceVariant
import com.jibe.app.ui.theme.JibePrimary
import com.jibe.app.ui.theme.JibeSuccess
import com.jibe.app.ui.theme.JibeSurfaceContainer
import com.jibe.app.ui.theme.JibeSurfaceContainerHigh
import com.jibe.app.ui.theme.JibeWarning
import com.jibe.app.ui.theme.RobotoMono

/**
 * Home dashboard — the main screen after pairing.
 *
 * Shows:
 * - Connection status (live dot + text)
 * - Daemon info (host, device ID)
 * - Ping button with round-trip latency display
 * - Clipboard sync, chunked file send, notification-mirroring hint
 * - "Forget device" destructive action
 */
@Composable
fun HomeScreen(
        repository: ConnectionRepository,
        onDeviceForgotten: () -> Unit,
        onOpenSettings: () -> Unit = {},
        onOpenPresentation: () -> Unit = {}
) {
        val state by repository.state.collectAsState()
        val transferProgress by repository.transferProgress.collectAsState()
        val featPing by repository.featPingEnabled.collectAsStateWithLifecycle(initialValue = false)
        val featClipboard by repository.featClipboardSync.collectAsStateWithLifecycle(initialValue = true)
        val featFile by repository.featFileTransferEnabled.collectAsStateWithLifecycle(initialValue = true)
        val featPresentation by
                repository.featPresentationRemote.collectAsStateWithLifecycle(initialValue = true)
        val context = LocalContext.current
        val pickDocument =
                rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocument(),
                        onResult = { uri ->
                                if (uri != null) {
                                        repository.sendFile(uri, context.contentResolver)
                                }
                        }
                )

        var lastLatency by remember { mutableLongStateOf(-1L) }
        var pingInFlight by remember { mutableStateOf(false) }
        var showForgetConfirm by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
                repository.pingResults.collect { result: PingResult ->
                        pingInFlight = false
                        lastLatency = result.latencyMs
                }
        }

        LaunchedEffect(state) {
                if (state !is ConnectionState.Connected) pingInFlight = false
        }

        val scrollState = rememberScrollState()
        val configuration = LocalConfiguration.current
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        Scaffold(containerColor = MaterialTheme.colorScheme.surface) { innerPadding ->
                Column(
                        modifier =
                                Modifier.fillMaxSize()
                                        .verticalScroll(scrollState)
                                        .padding(innerPadding)
                                        .padding(horizontal = if (isLandscape) 40.dp else 24.dp)
                                        .padding(top = if (isLandscape) 20.dp else 48.dp)
                                        .padding(bottom = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                ) {
                        Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                        ) {
                                if (state is ConnectionState.Connected && featPresentation) {
                                        TextButton(onClick = onOpenPresentation) {
                                                Text(
                                                        text = "Present",
                                                        style = MaterialTheme.typography.labelLarge,
                                                        color = JibePrimary
                                                )
                                        }
                                }
                                TextButton(onClick = onOpenSettings) {
                                        Text(
                                                text = "Settings",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = JibeOnSurfaceVariant
                                        )
                                }
                        }

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

                        Spacer(modifier = Modifier.height(if (isLandscape) 16.dp else 32.dp))

                        ConnectionStatusCard(state = state)

                        Spacer(modifier = Modifier.height(12.dp))

                        if (featPing) {
                                PingCard(
                                        isConnected = state is ConnectionState.Connected,
                                        pingInFlight = pingInFlight,
                                        lastLatency = lastLatency,
                                        onPing = {
                                                if (!pingInFlight) {
                                                        pingInFlight = true
                                                        repository.sendPing()
                                                }
                                        }
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                        }

                        if (featClipboard) {
                                ClipboardCard(
                                        isConnected = state is ConnectionState.Connected,
                                        repository = repository
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                        }

                        if (featFile) {
                                FileTransferCard(
                                        isConnected = state is ConnectionState.Connected,
                                        transferProgress = transferProgress,
                                        onPickClick = { pickDocument.launch(arrayOf("*/*")) },
                                        onCancelClick = { repository.cancelFileTransfer() }
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                        }

                        NotificationPermissionCard()

                        Spacer(modifier = Modifier.height(24.dp))

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
                }
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
                                        is ConnectionState.PairingFailed -> JibeError
                                        is ConnectionState.PairingUnavailable -> JibeWarning
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
                                                                is ConnectionState.PairingFailed ->
                                                                        "Pairing failed"
                                                                is ConnectionState.PairingUnavailable ->
                                                                        "Pairing unavailable"
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

                        val connectionHost =
                                when (state) {
                                        is ConnectionState.Connected -> state.host
                                        is ConnectionState.Connecting -> state.host
                                        is ConnectionState.Authenticating -> state.host
                                        else -> null
                                }

                        if (connectionHost != null) {
                                Spacer(modifier = Modifier.height(12.dp))

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                                text = "Host",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = JibeOnSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                                text = connectionHost,
                                                style =
                                                        MaterialTheme.typography.labelSmall.copy(
                                                                fontFamily = RobotoMono
                                                        ),
                                                color = JibeOnSurface
                                        )
                                }
                        }

                        if (state is ConnectionState.PairingFailed ||
                                        state is ConnectionState.PairingUnavailable
                        ) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                        text =
                                                when (state) {
                                                        is ConnectionState.PairingFailed ->
                                                                state.reason
                                                        is ConnectionState.PairingUnavailable ->
                                                                state.reason
                                                        else -> ""
                                                },
                                        style = MaterialTheme.typography.bodySmall,
                                        color =
                                                if (state is ConnectionState.PairingFailed) {
                                                    JibeError.copy(alpha = 0.85f)
                                                } else {
                                                    JibeWarning.copy(alpha = 0.85f)
                                                }
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                        text =
                                                when (state) {
                                                        is ConnectionState.PairingFailed ->
                                                                state.guidance
                                                        is ConnectionState.PairingUnavailable ->
                                                                state.guidance
                                                        else -> ""
                                                },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = JibeOnSurfaceVariant
                                )
                        }

                        if (state is ConnectionState.Connected) {
                                Spacer(modifier = Modifier.height(4.dp))

                                Row(verticalAlignment = Alignment.CenterVertically) {
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
private fun PingCard(
        isConnected: Boolean,
        pingInFlight: Boolean,
        lastLatency: Long,
        onPing: () -> Unit
) {
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
                                enabled = isConnected && !pingInFlight,
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
private fun ClipboardCard(isConnected: Boolean, repository: ConnectionRepository) {
        val context = LocalContext.current
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
                        Column(modifier = Modifier.weight(1f)) {
                                Text(
                                        text = "Clipboard",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = JibeOnSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                        text = "Send current clipboard text to the daemon",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = JibeOnSurfaceVariant
                                )
                        }

                        Button(
                                onClick = {
                                        val text = ClipboardTextReader.readPlainText(context)
                                        if (text.isNullOrBlank()) {
                                                Toast.makeText(
                                                                context,
                                                                context.getString(
                                                                        R.string.clipboard_sync_empty
                                                                ),
                                                                Toast.LENGTH_SHORT
                                                        )
                                                        .show()
                                        } else {
                                                if (repository.sendClipboardSync(text)) {
                                                        Toast.makeText(
                                                                        context,
                                                                        context.getString(
                                                                                R.string.clipboard_sync_sent
                                                                        ),
                                                                        Toast.LENGTH_SHORT
                                                                )
                                                                .show()
                                                } else {
                                                        Toast.makeText(
                                                                        context,
                                                                        context.getString(
                                                                                R.string.clipboard_sync_not_connected
                                                                        ),
                                                                        Toast.LENGTH_SHORT
                                                                )
                                                                .show()
                                                }
                                        }
                                },
                                enabled = isConnected,
                                shape = RoundedCornerShape(8.dp),
                                colors =
                                        ButtonDefaults.buttonColors(
                                                containerColor = JibePrimary.copy(alpha = 0.12f),
                                                contentColor = JibePrimary
                                        )
                        ) { Text(text = "Sync", style = MaterialTheme.typography.labelLarge) }
                }
        }
}

private val TransferProgressBarShape = RoundedCornerShape(4.dp)
private val TransferProgressBarHeight = 5.dp
private const val TransferProgressTrackAlpha = 0.10f

@Composable
private fun TransferProgressBar(
        modifier: Modifier = Modifier,
        progress: (() -> Float)?,
) {
        val barModifier =
                modifier.fillMaxWidth().height(TransferProgressBarHeight).clip(TransferProgressBarShape)
        val trackColor = JibeOnSurface.copy(alpha = TransferProgressTrackAlpha)
        val indicatorColor = JibePrimary
        if (progress != null) {
                LinearProgressIndicator(
                        progress = progress,
                        modifier = barModifier,
                        color = indicatorColor,
                        trackColor = trackColor,
                        strokeCap = StrokeCap.Round,
                        drawStopIndicator = {},
                )
        } else {
                LinearProgressIndicator(
                        modifier = barModifier,
                        color = indicatorColor,
                        trackColor = trackColor,
                        strokeCap = StrokeCap.Round,
                )
        }
}

@Composable
private fun FileTransferCard(
        isConnected: Boolean,
        transferProgress: TransferProgress?,
        onPickClick: () -> Unit,
        onCancelClick: () -> Unit
) {
        val busy =
                transferProgress?.let {
                    !it.isComplete && !it.isCancelled && it.error == null
                } == true

        Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = JibeSurfaceContainer),
                shape = RoundedCornerShape(12.dp)
        ) {
                Column(modifier = Modifier.padding(20.dp).fillMaxWidth()) {
                        Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                        Text(
                                                text = "Send file",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = JibeOnSurface
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                                text = "Chunked upload to ~/Downloads on the daemon",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = JibeOnSurfaceVariant
                                        )
                                }

                                when {
                                        transferProgress?.isCancelled == true -> {
                                                Spacer(modifier = Modifier.width(1.dp))
                                        }
                                        busy -> {
                                        Button(
                                                onClick = onCancelClick,
                                                enabled = isConnected,
                                                shape = RoundedCornerShape(8.dp),
                                                colors =
                                                        ButtonDefaults.buttonColors(
                                                                containerColor =
                                                                        JibeError.copy(
                                                                                alpha = 0.14f
                                                                        ),
                                                                contentColor = JibeError
                                                        )
                                        ) {
                                                Text(
                                                        text = "Cancel",
                                                        style = MaterialTheme.typography.labelLarge
                                                )
                                        }
                                        }
                                        else -> {
                                        Button(
                                                onClick = onPickClick,
                                                enabled = isConnected,
                                                shape = RoundedCornerShape(8.dp),
                                                colors =
                                                        ButtonDefaults.buttonColors(
                                                                containerColor =
                                                                        JibePrimary.copy(
                                                                                alpha = 0.12f
                                                                        ),
                                                                contentColor = JibePrimary
                                                        )
                                        ) {
                                                Text(
                                                        text = "Pick",
                                                        style = MaterialTheme.typography.labelLarge
                                                )
                                        }
                                        }
                                }
                        }

                        if (transferProgress != null) {
                                Spacer(modifier = Modifier.height(12.dp))

                                val awaitingAck =
                                        transferProgress.bytesSent >= transferProgress.totalBytes &&
                                                !transferProgress.isComplete &&
                                                transferProgress.error == null

                                when {
                                        transferProgress.error != null -> {
                                                Text(
                                                        text = transferProgress.error,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = JibeError.copy(alpha = 0.9f)
                                                )
                                        }
                                        transferProgress.isCancelled -> {
                                                Text(
                                                        text = "Cancelled",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = JibeOnSurfaceVariant.copy(alpha = 0.9f)
                                                )
                                        }
                                        transferProgress.isComplete -> {
                                                Text(
                                                        text = "Saved on daemon ✓",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = JibeSuccess.copy(alpha = 0.9f)
                                                )
                                        }
                                        awaitingAck -> {
                                                TransferProgressBar(progress = null)
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(
                                                        text = "Verifying…",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = JibeOnSurfaceVariant
                                                )
                                        }
                                        transferProgress.totalBytes > 0 -> {
                                                val p =
                                                        transferProgress.bytesSent.toFloat() /
                                                                transferProgress.totalBytes
                                                                        .toFloat()
                                                TransferProgressBar(progress = { p })
                                                Spacer(modifier = Modifier.height(6.dp))
                                                // Line 1: filename (truncated)
                                                Text(
                                                        text = transferProgress.filename,
                                                        style =
                                                                MaterialTheme.typography.labelSmall
                                                                        .copy(
                                                                                fontFamily =
                                                                                        RobotoMono
                                                                        ),
                                                        color = JibeOnSurface,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                // Line 2: bytes · speed · ETA
                                                Text(
                                                        text =
                                                                buildTransferMeta(
                                                                        transferProgress
                                                                ),
                                                        style =
                                                                MaterialTheme.typography.labelSmall
                                                                        .copy(
                                                                                fontFamily =
                                                                                        RobotoMono
                                                                        ),
                                                        color = JibeOnSurfaceVariant
                                                )
                                        }
                                        else -> {
                                                TransferProgressBar(progress = null)
                                        }
                                }
                        }
                }
        }
}

@Composable
private fun NotificationPermissionCard() {
        val context = LocalContext.current
        if (notificationAccessEnabled(context)) return

        Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = JibeSurfaceContainerHigh),
                shape = RoundedCornerShape(12.dp)
        ) {
                Column(modifier = Modifier.padding(20.dp).fillMaxWidth()) {
                        Text(
                                text = "Notification mirroring",
                                style = MaterialTheme.typography.titleMedium,
                                color = JibeOnSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                                text =
                                        "Forward Android notifications to the Linux desktop. Grant notification access for Jibe.",
                                style = MaterialTheme.typography.bodySmall,
                                color = JibeOnSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                                onClick = {
                                        context.startActivity(
                                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                        )
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors =
                                        ButtonDefaults.outlinedButtonColors(
                                                contentColor = JibePrimary
                                        )
                        ) { Text(text = "Open notification settings") }
                }
        }
}

private fun notificationAccessEnabled(context: Context): Boolean {
        val flat =
                Settings.Secure.getString(
                        context.contentResolver,
                        "enabled_notification_listeners"
                )
                        ?: return false
        return flat.contains(context.packageName)
}

private fun formatKb(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        return if (kb < 1024) "%.1f KB".format(kb) else "%.1f MB".format(kb / 1024.0)
}

private fun buildTransferMeta(p: TransferProgress): String = buildString {
        append("${formatKb(p.bytesSent)} / ${formatKb(p.totalBytes)}")
        if (p.speedBps > 0L) append("  ${formatKb(p.speedBps)}/s")
        if (p.etaSeconds != null && p.etaSeconds > 0L) {
                val eta =
                        when {
                                p.etaSeconds < 60 -> "${p.etaSeconds}s"
                                else -> "${p.etaSeconds / 60}m ${p.etaSeconds % 60}s"
                        }
                append("  $eta left")
        }
}

@Composable
private fun ForgetConfirmation(onConfirm: () -> Unit, onCancel: () -> Unit) {
        Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = JibeSurfaceContainerHigh),
                shape = RoundedCornerShape(18.dp)
        ) {
                Column(
                        modifier = Modifier.padding(20.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                ) {
                        Text(
                                text = "Forget this device?",
                                style =
                                        MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.SemiBold
                                        ),
                                color = JibeOnSurface,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                                text =
                                        "This removes the saved certificate and returns Jibe to pairing mode.",
                                style = MaterialTheme.typography.bodySmall,
                                color = JibeOnSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                        ) {
                                OutlinedButton(
                                        onClick = onCancel,
                                        shape = RoundedCornerShape(8.dp)
                                ) { Text("Cancel") }

                                Spacer(modifier = Modifier.width(12.dp))

                                Button(
                                        onClick = onConfirm,
                                        colors =
                                                ButtonDefaults.buttonColors(
                                                        containerColor = JibeError.copy(alpha = 0.14f),
                                                        contentColor = JibeError
                                                ),
                                        shape = RoundedCornerShape(8.dp)
                                ) { Text("Forget") }
                        }
                }
        }
}
