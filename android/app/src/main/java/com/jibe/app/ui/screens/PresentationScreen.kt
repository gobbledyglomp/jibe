package com.jibe.app.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jibe.app.R
import com.jibe.app.data.repository.ConnectionRepository
import com.jibe.app.data.repository.ConnectionState
import com.jibe.app.ui.theme.JibeSuccess
import com.jibe.app.ui.theme.JibeError
import com.jibe.app.ui.theme.RobotoMono

/**
 * Large-tap presentation remote: previous / stop / next slide keys over WebSocket.
 */
@Composable
fun PresentationScreen(repository: ConnectionRepository, onBack: () -> Unit) {
    val state by repository.state.collectAsStateWithLifecycle(initialValue = ConnectionState.Disconnected)
    val connected = state is ConnectionState.Connected
    val host = (state as? ConnectionState.Connected)?.host ?: "—"

    BackHandler(onBack = onBack)

    Column(
            modifier =
                    Modifier.fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Text(
                text = stringResource(R.string.present_title),
                style = MaterialTheme.typography.headlineLarge.copy(
                        fontFamily = RobotoMono,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-1).sp,
                ),
                color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
                text = host,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = RobotoMono),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(48.dp))

        RowOfRemoteButtons(
                enabled = connected,
                onPrev = { repository.sendRemoteKey("prev") },
                onStop = { repository.sendRemoteKey("stop") },
                onNext = { repository.sendRemoteKey("next") }
        )

        Spacer(modifier = Modifier.height(48.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                    modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (connected) JibeSuccess else JibeError.copy(alpha = 0.6f))
            )
            Spacer(modifier = Modifier.padding(start = 8.dp))
            Text(
                    text = if (connected)
                        stringResource(R.string.state_connected)
                    else
                        stringResource(R.string.present_not_connected),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (connected) JibeSuccess else JibeError.copy(alpha = 0.6f),
            )
        }

        Spacer(modifier = Modifier.weight(1.5f))
    }
}

@Composable
private fun RowOfRemoteButtons(
        enabled: Boolean,
        onPrev: () -> Unit,
        onStop: () -> Unit,
        onNext: () -> Unit
) {
    Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
    ) {
        RemotePad(
                label = "←",
                description = stringResource(R.string.present_prev),
                modifier = Modifier.weight(1f),
                enabled = enabled,
                onClick = onPrev
        )
        RemotePad(
                label = "■",
                description = stringResource(R.string.present_stop),
                modifier = Modifier.weight(1f),
                enabled = enabled,
                onClick = onStop
        )
        RemotePad(
                label = "→",
                description = stringResource(R.string.present_next),
                modifier = Modifier.weight(1f),
                enabled = enabled,
                onClick = onNext
        )
    }
}

@Composable
private fun RemotePad(
        label: String,
        description: String,
        modifier: Modifier = Modifier,
        enabled: Boolean,
        onClick: () -> Unit
) {
    Button(
            onClick = onClick,
            enabled = enabled,
            modifier =
                    modifier.height(120.dp).semantics {
                        contentDescription = description
                    },
            shape = RoundedCornerShape(16.dp),
            colors =
                    ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.35f),
                            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    )
    ) {
        Text(text = label, fontSize = 32.sp, fontWeight = FontWeight.Bold)
    }
}
