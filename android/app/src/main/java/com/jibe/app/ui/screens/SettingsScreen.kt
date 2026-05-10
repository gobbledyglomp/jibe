package com.jibe.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jibe.app.data.local.JibeDataStore
import kotlinx.coroutines.launch

/**
 * In-app preferences: appearance, locale, and per-feature toggles respected by the foreground
 * service and message handlers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(dataStore: JibeDataStore, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    val theme by dataStore.theme.collectAsStateWithLifecycle(initialValue = "dark")
    val language by dataStore.language.collectAsStateWithLifecycle(initialValue = "auto")

    val featClipboard by dataStore.featClipboard.collectAsStateWithLifecycle(initialValue = true)
    val featNotifications by dataStore.featNotifications.collectAsStateWithLifecycle(initialValue = true)
    val featFileTransfer by dataStore.featFileTransfer.collectAsStateWithLifecycle(initialValue = true)
    val featPresentation by dataStore.featPresentationRemote.collectAsStateWithLifecycle(initialValue = true)
    val featFindPhone by dataStore.featFindPhone.collectAsStateWithLifecycle(initialValue = true)
    val featPing by dataStore.featPing.collectAsStateWithLifecycle(initialValue = false)

    Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                CenterAlignedTopAppBar(
                        title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                        navigationIcon = {
                            TextButton(onClick = onBack) { Text("← Back") }
                        }
                )
            }
    ) { inner ->
        Column(
                modifier =
                        Modifier.padding(inner)
                                .verticalScroll(scroll)
                                .padding(horizontal = 20.dp, vertical = 8.dp)
                                .fillMaxWidth()
        ) {
            SectionTitle("Appearance")
            ToggleRow(
                    title = "Dark theme",
                    subtitle = "When off, uses light surfaces",
                    checked = theme == "dark",
                    onCheckedChange = { dark ->
                        scope.launch { dataStore.setTheme(if (dark) "dark" else "light") }
                    }
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text("Language", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LangChip(
                        label = "Auto",
                        selected = language == "auto",
                        onClick = { scope.launch { dataStore.setLanguage("auto") } }
                )
                LangChip(
                        label = "EN",
                        selected = language == "en",
                        onClick = { scope.launch { dataStore.setLanguage("en") } }
                )
                LangChip(
                        label = "ES",
                        selected = language == "es",
                        onClick = { scope.launch { dataStore.setLanguage("es") } }
                )
            }
            Text(
                    text = "Applies UI locale via system APIs where supported.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp))

            SectionTitle("Features")
            ToggleRow(
                    title = "Clipboard sync",
                    subtitle = "Send and receive clipboard text",
                    checked = featClipboard,
                    onCheckedChange = { v -> scope.launch { dataStore.setFeatClipboard(v) } }
            )
            ToggleRow(
                    title = "Notification mirror",
                    subtitle = "Forward notifications to Linux",
                    checked = featNotifications,
                    onCheckedChange = { v -> scope.launch { dataStore.setFeatNotifications(v) } }
            )
            ToggleRow(
                    title = "File transfer",
                    subtitle = "Upload files to the daemon",
                    checked = featFileTransfer,
                    onCheckedChange = { v -> scope.launch { dataStore.setFeatFileTransfer(v) } }
            )
            ToggleRow(
                    title = "Presentation remote",
                    subtitle = "Slide controls from Present screen",
                    checked = featPresentation,
                    onCheckedChange = { v -> scope.launch { dataStore.setFeatPresentationRemote(v) } }
            )
            ToggleRow(
                    title = "Find my phone",
                    subtitle = "Allow daemon ring commands",
                    checked = featFindPhone,
                    onCheckedChange = { v -> scope.launch { dataStore.setFeatFindPhone(v) } }
            )
            ToggleRow(
                    title = "Ping (diagnostic)",
                    subtitle = "Application ping/pong latency button",
                    checked = featPing,
                    onCheckedChange = { v -> scope.launch { dataStore.setFeatPing(v) } }
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 12.dp)
    )
}

@Composable
private fun ToggleRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
) {
    Row(
            modifier =
                    Modifier.fillMaxWidth()
                            .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun LangChip(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
                text = label,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color =
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
        )
    }
}
