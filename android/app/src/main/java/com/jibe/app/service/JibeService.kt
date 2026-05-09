package com.jibe.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.jibe.app.MainActivity
import com.jibe.app.R
import com.jibe.app.data.local.JibeDataStore
import com.jibe.app.data.repository.ClipboardWriter
import com.jibe.app.data.repository.ConnectionRepository
import com.jibe.app.data.repository.ConnectionState
import com.jibe.app.data.repository.DEFAULT_DEVICE_DISPLAY_NAME
import com.jibe.app.data.repository.DeviceNameProvider
import com.jibe.app.network.JibeDiscovery
import com.jibe.app.network.OkHttpDaemonTlsSocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground Service that keeps the daemon WebSocket connection alive.
 *
 * Android requires a user-visible notification for long-lived background work. The Activity
 * binds to the service to observe the shared [ConnectionRepository] state.
 */
class JibeService : Service() {

    companion object {
        private const val TAG = "JibeService"
        private const val CHANNEL_ID = "jibe_connection"
        private const val NOTIFICATION_ID = 1
        private const val FAILED_REASON_MAX_CHARS = 80
    }

    /** Binder for Activity to access the service's repository. */
    inner class JibeBinder : Binder() {
        val service: JibeService
            get() = this@JibeService
    }

    private val binder = JibeBinder()

    /**
     * SupervisorJob ensures a single child failure doesn't cancel everything. Dispatchers.Main for
     * StateFlow collection in the notification updater.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The single source of truth — Activity reads state from here. */
    lateinit var repository: ConnectionRepository
        private set

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")

        createNotificationChannel()

        val dataStore = JibeDataStore(applicationContext)
        val discovery = JibeDiscovery(applicationContext)

        repository =
                ConnectionRepository(
                        dataStore = dataStore,
                        discovery = discovery,
                        scope = serviceScope,
                        deviceNameProvider =
                                DeviceNameProvider {
                                    Build.MODEL?.takeIf { it.isNotBlank() }
                                            ?: DEFAULT_DEVICE_DISPLAY_NAME
                                },
                        socketFactory = OkHttpDaemonTlsSocketFactory(),
                        clipboardWriter =
                                ClipboardWriter { text ->
                                    Handler(Looper.getMainLooper()).post {
                                        val cm =
                                                getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("jibe", text))
                                    }
                                },
                )
        JibeRepositoryHolder.connectionRepository = repository
        serviceScope.launch { repository.state.collect { state -> updateNotification(state) } }
    }

    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service started")

        val currentNotification = buildNotification(repository.state.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                    NOTIFICATION_ID,
                    currentNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, currentNotification)
        }

        if (!started) {
            started = true
            repository.start()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "Activity bound to service")
        return binder
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        repository.disconnect()
        JibeRepositoryHolder.connectionRepository = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel =
                NotificationChannel(
                                CHANNEL_ID,
                                "Jibe Connection",
                                NotificationManager.IMPORTANCE_LOW
                        )
                        .apply {
                            description = "Shows Jibe connection status"
                            setShowBadge(false)
                        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(state: ConnectionState): Notification {
        val pendingIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

        val (title, text) =
                when (state) {
                    is ConnectionState.Disconnected -> "Jibe" to "Disconnected"
                    is ConnectionState.Discovering -> "Jibe" to "Searching for daemon…"
                    is ConnectionState.Connecting -> "Jibe" to "Connecting to ${state.host}…"
                    is ConnectionState.Authenticating -> "Jibe" to "Authenticating…"
                    is ConnectionState.Connected -> "Jibe — Connected" to state.host
                    is ConnectionState.PairingFailed ->
                            "Jibe" to "PIN rejected. Restart the daemon and retry."
                    is ConnectionState.Failed -> {
                        val detail = state.reason.trim()
                        val text =
                                if (detail.isEmpty()) {
                                    "Connection failed"
                                } else {
                                    val clipped =
                                            if (detail.length <= FAILED_REASON_MAX_CHARS) {
                                                detail
                                            } else {
                                                detail.take(FAILED_REASON_MAX_CHARS - 1) + "…"
                                            }
                                    "Connection failed — $clipped"
                                }
                        "Jibe" to text
                    }
                }

        return Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_jibe)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
    }

    private fun updateNotification(state: ConnectionState) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }
}
