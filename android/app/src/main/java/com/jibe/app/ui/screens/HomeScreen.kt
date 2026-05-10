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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
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
import com.jibe.app.ui.theme.JibeSuccess
import com.jibe.app.ui.theme.JibeWarning
import com.jibe.app.ui.theme.RobotoMono

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
        val isConnected = state is ConnectionState.Connected

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
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
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

                                IconButton(onClick = onOpenSettings) {
                                        Icon(
                                                imageVector = Icons.Default.Settings,
                                                contentDescription = stringResource(R.string.settings_title),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(22.dp)
                                        )
                                }
                        }

                        Spacer(modifier = Modifier.height(if (isLandscape) 16.dp else 24.dp))

                        ConnectionStatusCard(state = state)

                        Spacer(modifier = Modifier.height(12.dp))

                        if (featClipboard) {
                                ClipboardCard(
                                        isConnected = isConnected,
                                        repository = repository
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                        }

                        if (featFile) {
                                FileTransferCard(
                                        isConnected = isConnected,
                                        transferProgress = transferProgress,
                                        onPickClick = { pickDocument.launch(arrayOf("*/*")) },
                                        onCancelClick = { repository.cancelFileTransfer() }
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                        }

                        if (featPresentation && isConnected) {
                                PresentCard(onClick = onOpenPresentation)
                                Spacer(modifier = Modifier.height(12.dp))
                        }

                        NotificationPermissionCard(trailingSpacerDp = 12)

                        if (featPing) {
                                PingCard(
                                        isConnected = isConnected,
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

                        Spacer(modifier = Modifier.height(12.dp))

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
                                                text = stringResource(R.string.home_forget_device),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
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
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                },
                        label = "status_color"
                )

        Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                shape = RoundedCornerShape(14.dp)
        ) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
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
                                                                        stringResource(R.string.state_connected)
                                                                is ConnectionState.Connecting ->
                                                                        stringResource(R.string.state_connecting)
                                                                is ConnectionState.Authenticating ->
                                                                        stringResource(R.string.state_authenticating)
                                                                is ConnectionState.Discovering ->
                                                                        stringResource(R.string.state_discovering)
                                                                is ConnectionState.PairingFailed ->
                                                                        stringResource(R.string.state_pairing_failed)
                                                                is ConnectionState.PairingUnavailable ->
                                                                        stringResource(R.string.state_pairing_unavailable)
                                                                is ConnectionState.Failed ->
                                                                        stringResource(R.string.state_failed)
                                                                is ConnectionState.Disconnected ->
                                                                        stringResource(R.string.state_disconnected)
                                                        },
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onSurface
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
                                                text = stringResource(R.string.label_host),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                                text = connectionHost,
                                                style =
                                                        MaterialTheme.typography.labelSmall.copy(
                                                                fontFamily = RobotoMono
                                                        ),
                                                color = MaterialTheme.colorScheme.onSurface
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
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                        }

                        if (state is ConnectionState.Connected) {
                                Spacer(modifier = Modifier.height(4.dp))

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                                text = stringResource(R.string.label_device),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                                text = state.deviceId,
                                                style =
                                                        MaterialTheme.typography.labelSmall.copy(
                                                                fontFamily = RobotoMono
                                                        ),
                                                color = MaterialTheme.colorScheme.onSurface,
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
private fun PresentCard(onClick: () -> Unit) {
        Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = RoundedCornerShape(12.dp)
        ) {
                Row(
                        modifier = Modifier.padding(20.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                ) {
                        Column(modifier = Modifier.weight(1f)) {
                                Text(
                                        text = stringResource(R.string.present_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                        text = stringResource(R.string.present_subtitle),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                        }

                        OutlinedButton(
                                onClick = onClick,
                                shape = RoundedCornerShape(8.dp),
                                colors =
                                        ButtonDefaults.outlinedButtonColors(
                                                contentColor = MaterialTheme.colorScheme.primary
                                        )
                        ) { Text(text = stringResource(R.string.present_open), style = MaterialTheme.typography.labelLarge) }
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = RoundedCornerShape(12.dp)
        ) {
                Row(
                        modifier = Modifier.padding(20.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                ) {
                        Column {
                                Text(
                                        text = stringResource(R.string.ping_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
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
                                                if (lastLatency >= 0) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                        }

                        OutlinedButton(
                                onClick = onPing,
                                enabled = isConnected && !pingInFlight,
                                shape = RoundedCornerShape(8.dp),
                                colors =
                                        ButtonDefaults.outlinedButtonColors(
                                                contentColor = MaterialTheme.colorScheme.primary
                                        )
                        ) { Text(text = stringResource(R.string.action_send), style = MaterialTheme.typography.labelLarge) }
                }
        }
}

@Composable
private fun ClipboardCard(isConnected: Boolean, repository: ConnectionRepository) {
        val context = LocalContext.current
        Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = RoundedCornerShape(12.dp)
        ) {
                Row(
                        modifier = Modifier.padding(20.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                ) {
                        Column(modifier = Modifier.weight(1f)) {
                                Text(
                                        text = stringResource(R.string.clipboard_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                        text = stringResource(R.string.clipboard_subtitle),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                                contentColor = MaterialTheme.colorScheme.primary
                                        )
                        ) { Text(text = stringResource(R.string.clipboard_sync_action), style = MaterialTheme.typography.labelLarge) }
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
        val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = TransferProgressTrackAlpha)
        val indicatorColor = MaterialTheme.colorScheme.primary
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
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
                                                text = stringResource(R.string.file_title),
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                                text = stringResource(R.string.file_subtitle),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                                        text = stringResource(R.string.action_cancel),
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
                                                                        MaterialTheme.colorScheme.primary.copy(
                                                                                alpha = 0.12f
                                                                        ),
                                                                contentColor = MaterialTheme.colorScheme.primary
                                                        )
                                        ) {
                                                Text(
                                                        text = stringResource(R.string.file_pick),
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
                                                        text = stringResource(R.string.transfer_cancelled),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                                                )
                                        }
                                        transferProgress.isComplete -> {
                                                Text(
                                                        text = stringResource(R.string.transfer_saved),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = JibeSuccess.copy(alpha = 0.9f)
                                                )
                                        }
                                        awaitingAck -> {
                                                TransferProgressBar(progress = null)
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(
                                                        text = stringResource(R.string.transfer_verifying),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                        }
                                        transferProgress.totalBytes > 0 -> {
                                                val p =
                                                        transferProgress.bytesSent.toFloat() /
                                                                transferProgress.totalBytes
                                                                        .toFloat()
                                                TransferProgressBar(progress = { p })
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(
                                                        text = transferProgress.filename,
                                                        style =
                                                                MaterialTheme.typography.labelSmall
                                                                        .copy(
                                                                                fontFamily =
                                                                                        RobotoMono
                                                                        ),
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
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
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
private fun NotificationPermissionCard(trailingSpacerDp: Int = 0) {
        val context = LocalContext.current
        if (notificationAccessEnabled(context)) return

        Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                shape = RoundedCornerShape(12.dp)
        ) {
                Column(modifier = Modifier.padding(20.dp).fillMaxWidth()) {
                        Text(
                                text = stringResource(R.string.notif_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                                text = stringResource(R.string.notif_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                                contentColor = MaterialTheme.colorScheme.primary
                                        )
                        ) { Text(text = stringResource(R.string.notif_open_settings)) }
                }
        }
        if (trailingSpacerDp > 0) {
                Spacer(modifier = Modifier.height(trailingSpacerDp.dp))
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                shape = RoundedCornerShape(18.dp)
        ) {
                Column(
                        modifier = Modifier.padding(20.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                ) {
                        Text(
                                text = stringResource(R.string.forget_title),
                                style =
                                        MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.SemiBold
                                        ),
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                                text = stringResource(R.string.forget_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                ) { Text(stringResource(R.string.action_cancel)) }

                                Spacer(modifier = Modifier.width(12.dp))

                                Button(
                                        onClick = onConfirm,
                                        colors =
                                                ButtonDefaults.buttonColors(
                                                        containerColor = JibeError.copy(alpha = 0.14f),
                                                        contentColor = JibeError
                                                ),
                                        shape = RoundedCornerShape(8.dp)
                                ) { Text(stringResource(R.string.forget_confirm)) }
                        }
                }
        }
}
