package com.jibe.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jibe.app.data.repository.ConnectionRepository
import com.jibe.app.data.repository.ConnectionState
import com.jibe.app.ui.theme.JibePrimary
import com.jibe.app.ui.theme.JibeSurfaceContainer
import com.jibe.app.ui.theme.JibeSurfaceDark
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
                            .background(JibeSurfaceDark)
                            .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                    text = "Present",
                    style = MaterialTheme.typography.titleMedium,
                    color = JibePrimary,
                    fontFamily = RobotoMono,
                    fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                    text = host,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.55f),
                    fontFamily = RobotoMono,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
            )
        }

        Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RowOfRemoteButtons(
                    enabled = connected,
                    onPrev = { repository.sendRemoteKey("prev") },
                    onStop = { repository.sendRemoteKey("stop") },
                    onNext = { repository.sendRemoteKey("next") }
            )
        }

        Text(
                text = if (connected) "Connected" else "Not connected",
                style = MaterialTheme.typography.labelMedium,
                color = if (connected) Color(0xFF6FCF97) else Color(0xFFCF6679),
                modifier = Modifier.padding(bottom = 8.dp)
        )
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
                description = "Previous slide",
                modifier = Modifier.weight(1f),
                enabled = enabled,
                onClick = onPrev
        )
        RemotePad(
                label = "■",
                description = "Stop presentation",
                modifier = Modifier.weight(1f),
                enabled = enabled,
                onClick = onStop
        )
        RemotePad(
                label = "→",
                description = "Next slide",
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
                            containerColor = JibeSurfaceContainer,
                            contentColor = Color.White,
                            disabledContainerColor = JibeSurfaceContainer.copy(alpha = 0.35f),
                            disabledContentColor = Color.White.copy(alpha = 0.35f)
                    )
    ) {
        Text(text = label, fontSize = 32.sp, fontWeight = FontWeight.Bold)
    }
}
