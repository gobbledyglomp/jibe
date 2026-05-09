package com.jibe.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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

class JibeDataStore(private val context: Context) {

    companion object {
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private val KEY_FINGERPRINT = stringPreferencesKey("fingerprint")
        private val KEY_DAEMON_HOST = stringPreferencesKey("daemon_host")
        private val KEY_DAEMON_PORT = stringPreferencesKey("daemon_port")
        private val KEY_CERT_FINGERPRINT = stringPreferencesKey("cert_fingerprint")
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
        context.dataStore.edit { it.clear() }
    }
}
