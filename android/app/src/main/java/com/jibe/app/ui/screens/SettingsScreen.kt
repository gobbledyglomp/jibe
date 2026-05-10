package com.jibe.app.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jibe.app.R
import com.jibe.app.data.local.AppSettings
import com.jibe.app.data.local.JibeDataStore
import kotlinx.coroutines.launch

/**
 * Preferences screen: appearance (theme, language) and per-feature toggles.
 * All values are loaded as a single snapshot to avoid toggle flicker on entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(dataStore: JibeDataStore, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    var settings by remember { mutableStateOf<AppSettings?>(null) }

    LaunchedEffect(Unit) {
        dataStore.allSettings.collect { settings = it }
    }

    Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                        title = {
                            Text(
                                    stringResource(R.string.settings_title),
                                    fontWeight = FontWeight.SemiBold
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(R.string.action_back),
                                        tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                titleContentColor = MaterialTheme.colorScheme.onSurface
                        )
                )
            }
    ) { inner ->
        val s = settings
        if (s == null) {
            Box(modifier = Modifier.fillMaxSize().padding(inner))
            return@Scaffold
        }

        Column(
                modifier =
                        Modifier.padding(inner)
                                .verticalScroll(scroll)
                                .padding(horizontal = 20.dp, vertical = 8.dp)
                                .fillMaxWidth()
        ) {
            SectionTitle(stringResource(R.string.settings_appearance))

            ToggleRow(
                    title = stringResource(R.string.settings_dark_theme),
                    subtitle = stringResource(R.string.settings_dark_theme_desc),
                    checked = s.theme == "dark",
                    onCheckedChange = { dark ->
                        scope.launch { dataStore.setTheme(if (dark) "dark" else "light") }
                    }
            )

            Spacer(modifier = Modifier.height(16.dp))

            LanguageDropdown(
                    selected = s.language,
                    onSelect = { lang -> scope.launch { dataStore.setLanguage(lang) } }
            )

            HorizontalDivider(
                    modifier = Modifier.padding(vertical = 20.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            )

            SectionTitle(stringResource(R.string.settings_features))

            ToggleRow(
                    title = stringResource(R.string.settings_feat_clipboard),
                    subtitle = stringResource(R.string.settings_feat_clipboard_desc),
                    checked = s.featClipboard,
                    onCheckedChange = { v -> scope.launch { dataStore.setFeatClipboard(v) } },
                    icon = Icons.Default.ContentCopy,
            )
            ToggleRow(
                    title = stringResource(R.string.settings_feat_notifications),
                    subtitle = stringResource(R.string.settings_feat_notifications_desc),
                    checked = s.featNotifications,
                    onCheckedChange = { v -> scope.launch { dataStore.setFeatNotifications(v) } },
                    icon = Icons.Default.Notifications,
            )
            ToggleRow(
                    title = stringResource(R.string.settings_feat_file_transfer),
                    subtitle = stringResource(R.string.settings_feat_file_transfer_desc),
                    checked = s.featFileTransfer,
                    onCheckedChange = { v -> scope.launch { dataStore.setFeatFileTransfer(v) } },
                    icon = Icons.Default.UploadFile,
            )
            ToggleRow(
                    title = stringResource(R.string.settings_feat_presentation),
                    subtitle = stringResource(R.string.settings_feat_presentation_desc),
                    checked = s.featPresentation,
                    onCheckedChange = { v -> scope.launch { dataStore.setFeatPresentationRemote(v) } },
                    icon = Icons.Default.Slideshow,
            )
            ToggleRow(
                    title = stringResource(R.string.settings_feat_find_phone),
                    subtitle = stringResource(R.string.settings_feat_find_phone_desc),
                    checked = s.featFindPhone,
                    onCheckedChange = { v -> scope.launch { dataStore.setFeatFindPhone(v) } },
                    icon = Icons.Default.PhoneAndroid,
            )
            ToggleRow(
                    title = stringResource(R.string.settings_feat_ping),
                    subtitle = stringResource(R.string.settings_feat_ping_desc),
                    checked = s.featPing,
                    onCheckedChange = { v -> scope.launch { dataStore.setFeatPing(v) } },
                    icon = Icons.Default.Speed,
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 12.dp)
    )
}

@Composable
private fun ToggleRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        icon: ImageVector? = null,
) {
    Row(
            modifier =
                    Modifier.fillMaxWidth()
                            .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.size(14.dp))
        }
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                )
        )
    }
}

private data class LanguageOption(val code: String, val label: String)

private val LANGUAGE_OPTIONS = listOf(
        LanguageOption("en", "English"),
        LanguageOption("es", "Español"),
)

@Composable
private fun LanguageDropdown(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = LANGUAGE_OPTIONS.find { it.code == selected }?.label ?: "English"

    Column {
        Text(
                stringResource(R.string.settings_language),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Box {
            OutlinedButton(
                    onClick = { expanded = true },
                    shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                        text = selectedLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                )
            }
            DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
            ) {
                LANGUAGE_OPTIONS.forEach { option ->
                    DropdownMenuItem(
                            text = {
                                Text(
                                        text = option.label,
                                        fontWeight = if (option.code == selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            onClick = {
                                expanded = false
                                onSelect(option.code)
                            }
                    )
                }
            }
        }
    }
}
