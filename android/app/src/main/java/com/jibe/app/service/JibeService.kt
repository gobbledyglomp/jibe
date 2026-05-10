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
import android.graphics.drawable.Icon
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
import com.jibe.app.data.repository.TransferProgress
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
        private const val TRANSFER_FILENAME_MAX_CHARS = 30
        private const val REQUEST_CODE_OPEN = 0
        private const val REQUEST_CODE_CLIP_SYNC = 1
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
        serviceScope.launch { repository.state.collect { _ -> rebuildNotification() } }
        serviceScope.launch { repository.transferProgress.collect { _ -> rebuildNotification() } }
    }

    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service started")

        val currentNotification =
                buildNotification(repository.state.value, repository.transferProgress.value)
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

    private fun buildNotification(
            state: ConnectionState,
            transfer: TransferProgress?
    ): Notification {
        val pendingIntent =
                PendingIntent.getActivity(
                        this,
                        REQUEST_CODE_OPEN,
                        Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

        val isTransferring =
                transfer != null &&
                        !transfer.isComplete &&
                        !transfer.isCancelled &&
                        transfer.error == null

        val (title, text) =
                when {
                    isTransferring -> {
                        val p = transfer!!
                        val name =
                                if (p.filename.length > TRANSFER_FILENAME_MAX_CHARS) {
                                    p.filename.take(TRANSFER_FILENAME_MAX_CHARS - 1) + "…"
                                } else p.filename
                        "Jibe — Sending $name" to buildTransferStatusText(p)
                    }
                    state is ConnectionState.Disconnected -> "Jibe" to "Disconnected"
                    state is ConnectionState.Discovering -> "Jibe" to "Searching for daemon…"
                    state is ConnectionState.Connecting ->
                            "Jibe" to "Connecting to ${state.host}…"
                    state is ConnectionState.Authenticating -> "Jibe" to "Authenticating…"
                    state is ConnectionState.Connected -> "Jibe — Connected" to state.host
                    state is ConnectionState.PairingFailed ->
                            "Jibe" to "PIN rejected. Restart the daemon and retry."
                    state is ConnectionState.PairingUnavailable ->
                            "Jibe" to state.reason
                    state is ConnectionState.Failed -> {
                        val detail = state.reason.trim()
                        val clipped =
                                if (detail.length <= FAILED_REASON_MAX_CHARS) detail
                                else detail.take(FAILED_REASON_MAX_CHARS - 1) + "…"
                        "Jibe" to if (detail.isEmpty()) "Connection failed" else "Connection failed — $clipped"
                    }
                    else -> "Jibe" to ""
                }

        val builder =
                Notification.Builder(this, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_stat_jibe)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setContentIntent(pendingIntent)
                        .setOngoing(true)

        if (isTransferring) {
            val p = transfer!!
            val pct =
                    if (p.totalBytes > 0L) (p.bytesSent * 100L / p.totalBytes).toInt() else 0
            builder.setProgress(100, pct, p.totalBytes == 0L)
        }

        // "Sync clipboard" action — visible only in the expanded notification view.
        // Uses a transparent trampoline Activity so the clipboard read is made from
        // the foreground (Android 10+ restriction).
        if (state is ConnectionState.Connected) {
            val clipSyncIntent =
                    PendingIntent.getActivity(
                            this,
                            REQUEST_CODE_CLIP_SYNC,
                            Intent(this, ClipboardSyncActivity::class.java).apply {
                                flags =
                                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                                Intent.FLAG_ACTIVITY_NO_ANIMATION
                            },
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
            builder.addAction(
                    Notification.Action.Builder(
                                    Icon.createWithResource(this, R.drawable.ic_stat_jibe),
                                    "Sync clipboard",
                                    clipSyncIntent
                            )
                            .build()
            )
        }

        return builder.build()
    }

    private fun buildTransferStatusText(p: TransferProgress): String = buildString {
        append("${formatBytes(p.bytesSent)} / ${formatBytes(p.totalBytes)}")
        if (p.speedBps > 0L) append("  ${formatBytes(p.speedBps)}/s")
        if (p.etaSeconds != null && p.etaSeconds > 0L) {
            val eta =
                    when {
                        p.etaSeconds < 60 -> "${p.etaSeconds}s"
                        else -> "${p.etaSeconds / 60}m ${p.etaSeconds % 60}s"
                    }
            append("  $eta left")
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        return if (kb < 1024) "%.1f KB".format(kb) else "%.1f MB".format(kb / 1024.0)
    }

    private fun rebuildNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
                NOTIFICATION_ID,
                buildNotification(repository.state.value, repository.transferProgress.value)
        )
    }
}
