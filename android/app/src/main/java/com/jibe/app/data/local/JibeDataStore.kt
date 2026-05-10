package com.jibe.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "jibe_prefs")

data class DeviceCredentials(
        val deviceId: String,
        val fingerprint: String,
        val daemonHost: String,
        val daemonPort: Int,
        val certFingerprint: String
)

data class AppSettings(
        val theme: String = "dark",
        val language: String = FALLBACK_LANGUAGE,
        val featClipboard: Boolean = true,
        val featNotifications: Boolean = true,
        val featFileTransfer: Boolean = true,
        val featPresentation: Boolean = true,
        val featFindPhone: Boolean = true,
        val featPing: Boolean = false,
)

private const val FALLBACK_LANGUAGE = "en"
private val SUPPORTED_LANGUAGES = setOf("en", "es")

private fun detectSystemLanguage(): String {
    val sysLang = java.util.Locale.getDefault().language
    return if (sysLang in SUPPORTED_LANGUAGES) sysLang else FALLBACK_LANGUAGE
}

class JibeDataStore(private val context: Context) {

    companion object {
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private val KEY_FINGERPRINT = stringPreferencesKey("fingerprint")
        private val KEY_DAEMON_HOST = stringPreferencesKey("daemon_host")
        private val KEY_DAEMON_PORT = stringPreferencesKey("daemon_port")
        private val KEY_CERT_FINGERPRINT = stringPreferencesKey("cert_fingerprint")

        private val KEY_THEME = stringPreferencesKey("theme")
        private val KEY_LANGUAGE = stringPreferencesKey("language")

        private val KEY_FEAT_CLIPBOARD = booleanPreferencesKey("feat_clipboard")
        private val KEY_FEAT_NOTIFICATIONS = booleanPreferencesKey("feat_notifications")
        private val KEY_FEAT_FILE_TRANSFER = booleanPreferencesKey("feat_file_transfer")
        private val KEY_FEAT_PRESENTATION_REMOTE = booleanPreferencesKey("feat_presentation_remote")
        private val KEY_FEAT_FIND_PHONE = booleanPreferencesKey("feat_find_phone")
        private val KEY_FEAT_PING = booleanPreferencesKey("feat_ping")
    }

    val credentials: Flow<DeviceCredentials?> =
            context.dataStore.data.map { prefs ->
                val deviceId = prefs[KEY_DEVICE_ID] ?: return@map null
                val fingerprint = prefs[KEY_FINGERPRINT] ?: return@map null
                val host = prefs[KEY_DAEMON_HOST] ?: return@map null
                val port = prefs[KEY_DAEMON_PORT]?.toIntOrNull() ?: return@map null
                val certFp = prefs[KEY_CERT_FINGERPRINT] ?: return@map null

                DeviceCredentials(
                        deviceId = deviceId,
                        fingerprint = fingerprint,
                        daemonHost = host,
                        daemonPort = port,
                        certFingerprint = certFp
                )
            }

    val theme: Flow<String> =
            context.dataStore.data.map { prefs -> prefs[KEY_THEME] ?: "dark" }

    val language: Flow<String> =
            context.dataStore.data.map { prefs ->
                val stored = prefs[KEY_LANGUAGE]
                when {
                    stored != null && stored != "auto" -> stored
                    else -> detectSystemLanguage()
                }
            }

    val allSettings: Flow<AppSettings> =
            context.dataStore.data.map { prefs ->
                val lang = prefs[KEY_LANGUAGE]
                AppSettings(
                        theme = prefs[KEY_THEME] ?: "dark",
                        language = when {
                            lang != null && lang != "auto" -> lang
                            else -> detectSystemLanguage()
                        },
                        featClipboard = prefs[KEY_FEAT_CLIPBOARD] ?: true,
                        featNotifications = prefs[KEY_FEAT_NOTIFICATIONS] ?: true,
                        featFileTransfer = prefs[KEY_FEAT_FILE_TRANSFER] ?: true,
                        featPresentation = prefs[KEY_FEAT_PRESENTATION_REMOTE] ?: true,
                        featFindPhone = prefs[KEY_FEAT_FIND_PHONE] ?: true,
                        featPing = prefs[KEY_FEAT_PING] ?: false,
                )
            }

    val featClipboard: Flow<Boolean> =
            context.dataStore.data.map { prefs -> prefs[KEY_FEAT_CLIPBOARD] ?: true }

    val featNotifications: Flow<Boolean> =
            context.dataStore.data.map { prefs -> prefs[KEY_FEAT_NOTIFICATIONS] ?: true }

    val featFileTransfer: Flow<Boolean> =
            context.dataStore.data.map { prefs -> prefs[KEY_FEAT_FILE_TRANSFER] ?: true }

    val featPresentationRemote: Flow<Boolean> =
            context.dataStore.data.map { prefs -> prefs[KEY_FEAT_PRESENTATION_REMOTE] ?: true }

    val featFindPhone: Flow<Boolean> =
            context.dataStore.data.map { prefs -> prefs[KEY_FEAT_FIND_PHONE] ?: true }

    val featPing: Flow<Boolean> =
            context.dataStore.data.map { prefs -> prefs[KEY_FEAT_PING] ?: false }

    suspend fun saveCredentials(credentials: DeviceCredentials) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DEVICE_ID] = credentials.deviceId
            prefs[KEY_FINGERPRINT] = credentials.fingerprint
            prefs[KEY_DAEMON_HOST] = credentials.daemonHost
            prefs[KEY_DAEMON_PORT] = credentials.daemonPort.toString()
            prefs[KEY_CERT_FINGERPRINT] = credentials.certFingerprint
        }
    }

    suspend fun clearCredentials() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_DEVICE_ID)
            prefs.remove(KEY_FINGERPRINT)
            prefs.remove(KEY_DAEMON_HOST)
            prefs.remove(KEY_DAEMON_PORT)
            prefs.remove(KEY_CERT_FINGERPRINT)
        }
    }

    suspend fun setTheme(theme: String) {
        context.dataStore.edit { prefs -> prefs[KEY_THEME] = theme }
    }

    suspend fun setLanguage(language: String) {
        context.dataStore.edit { prefs -> prefs[KEY_LANGUAGE] = language }
    }

    suspend fun setFeatClipboard(on: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_FEAT_CLIPBOARD] = on }
    }

    suspend fun setFeatNotifications(on: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_FEAT_NOTIFICATIONS] = on }
    }

    suspend fun setFeatFileTransfer(on: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_FEAT_FILE_TRANSFER] = on }
    }

    suspend fun setFeatPresentationRemote(on: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_FEAT_PRESENTATION_REMOTE] = on }
    }

    suspend fun setFeatFindPhone(on: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_FEAT_FIND_PHONE] = on }
    }

    suspend fun setFeatPing(on: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_FEAT_PING] = on }
    }
}
