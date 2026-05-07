package com.jibe.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.jibe.app.data.local.JibeDataStore
import com.jibe.app.service.JibeService
import com.jibe.app.ui.navigation.JibeNavGraph
import com.jibe.app.ui.theme.JibeTheme

/**
 * Main (and only) Activity — thin shell that binds to JibeService.
 *
 * The Activity does not own any business logic. It:
 * 1. Requests notification permission (Android 13+, just-in-time)
 * 2. Starts the foreground service
 * 3. Binds to the service to access the ConnectionRepository
 * 4. Renders the Compose UI, passing the repository to the NavGraph
 *
 * When the user closes the app, the Activity is destroyed but the service keeps running — the
 * WebSocket stays connected.
 */
class MainActivity : ComponentActivity() {

    private var service: JibeService? by mutableStateOf(null)

    private val serviceConnection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    service = (binder as JibeService.JibeBinder).service
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    service = null
                }
            }

    /**
     * Just-in-time notification permission request.
     *
     * On Android 13+ (API 33), apps must explicitly request POST_NOTIFICATIONS. We ask right at
     * startup since the foreground service needs to display a notification immediately. The service
     * still works without the permission — the notification just won't be visible.
     */
    private val notificationPermissionLauncher =
            registerForActivityResult(
                    ActivityResultContracts.RequestPermission()
            ) { /* We proceed regardless — the service works either way */}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val dataStore = JibeDataStore(applicationContext)

        // Render UI first — so the user sees something immediately, even
        // during the brief window before the service binds.
        setContent {
            JibeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    val currentService = service

                    if (currentService != null) {
                        JibeNavGraph(
                            credentialsFlow = dataStore.credentials,
                            repository = currentService.repository
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
        }

        // Request notification permission after the UI is up. On Android 13+
        // this shows a dialog — we want the app to already be visible behind it.
        requestNotificationPermissionIfNeeded()

        // Start and bind to the foreground service
        val serviceIntent = Intent(this, JibeService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService(serviceConnection)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, permission) !=
                            PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(permission)
            }
        }
    }
}
