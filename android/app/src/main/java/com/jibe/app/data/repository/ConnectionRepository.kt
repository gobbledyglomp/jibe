package com.jibe.app.data.repository

import android.content.ContentResolver
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.jibe.app.data.local.DeviceCredentials
import com.jibe.app.data.local.JibeDataStore
import com.jibe.app.data.model.AuthRequest
import com.jibe.app.data.model.AuthResponse
import com.jibe.app.data.model.ClipboardSyncMessage
import com.jibe.app.data.model.ErrorMessage
import com.jibe.app.data.model.FileAckMessage
import com.jibe.app.data.model.DeviceBatteryMessage
import com.jibe.app.data.model.DeviceFeaturesMessage
import com.jibe.app.data.model.FileChunkAckMessage
import com.jibe.app.data.model.MessageType
import com.jibe.app.data.model.NotificationMessage
import com.jibe.app.data.model.RemoteKeyMessage
import com.jibe.app.data.model.PingMessage
import com.jibe.app.network.DaemonTlsSocketFactory
import com.jibe.app.network.DiscoveryState
import com.jibe.app.network.JibeDiscovery
import com.jibe.app.network.JibeTrustManager
import com.jibe.app.network.JibeWebSocketHandle
import com.jibe.app.network.MessageParser
import com.jibe.app.network.WebSocketEvent
import com.jibe.app.service.RingAlertActivity
import com.jibe.app.service.RingPlayer
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Connection states — the central state machine for the app.
 *
 * Each state maps directly to a UI state in the Compose screens. Sealed class ensures exhaustive
 * when-expressions (the compiler forces us to handle every state — no forgotten edge cases).
 */
sealed class ConnectionState {
    /** No connection activity. Initial state and post-disconnect. */
    data object Disconnected : ConnectionState()

    /** Scanning LAN for _jibe._tcp. daemons via NSD. */
    data object Discovering : ConnectionState()

    /** Found a daemon, opening WebSocket + TLS handshake. */
    data class Connecting(val host: String, val port: Int) : ConnectionState()

    /** WebSocket open, waiting for PIN from user. Optional hint shown below the PIN boxes. */
    data class Authenticating(val host: String, val hint: String? = null) : ConnectionState()

    /** Fully authenticated — ready for message exchange. */
    data class Connected(val host: String, val port: Int, val deviceId: String) : ConnectionState()

    /** Something went wrong — human-readable error for the UI. */
    data class Failed(val reason: String) : ConnectionState()

    /** Pairing was rejected. Unlike [Failed], this should not auto-reconnect in a loop. */
    data class PairingFailed(val reason: String, val guidance: String) : ConnectionState()

    /** Daemon rejected pairing because pairing mode is not active yet. */
    data class PairingUnavailable(val reason: String, val guidance: String) : ConnectionState()
}

/** Ping result — emitted after a pong arrives. */
data class PingResult(val latencyMs: Long)

/**
 * Prefix for [ConnectionState.Authenticating.hint] after a rejected PIN during pairing; the pairing
 * screen clears digits when the hint starts with this string.
 */
const val WRONG_PAIRING_PIN_HINT_PREFIX = "Wrong PIN"

/**
 * Single source of truth for the daemon connection.
 *
 * Orchestrates NSD discovery ↔ WebSocket ↔ credential persistence, and exposes state as a
 * [StateFlow] for the UI/ViewModel.
 *
 * Reconnection is two-phase: fast direct reconnect (limited attempts), then NSD re-discovery.
 */
class ConnectionRepository(
        private val appContext: Context,
        private val dataStore: JibeDataStore,
        private val discovery: JibeDiscovery,
        private val scope: CoroutineScope,
        private val deviceNameProvider: DeviceNameProvider,
        private val socketFactory: DaemonTlsSocketFactory,
        private val clipboardWriter: ClipboardWriter,
        private val connectionDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        private const val TAG = "ConnectionRepo"
        private const val RING_CHANNEL_ID = "jibe_ring"
        private const val MAX_FAST_RECONNECTS = 4
        private const val RECONNECT_BASE_DELAY_MS = 1_000L
        private const val RECONNECT_MAX_DELAY_MS = 8_000L
        private const val PING_TIMEOUT_MS = 5_000L
        private const val PAIRING_FAILURE_GUIDANCE =
                "Restart the daemon to reset pairing, then tap Retry."
        private const val PAIRING_INACTIVE_GUIDANCE =
                "Start pairing mode on the daemon (--pair or SIGUSR1), then tap Retry."
        private const val MAX_PAIRING_PIN_FAILURES = 5
        private const val PAIRING_RETRY_SETTLE_MS = 120L
        private const val PAIRING_DIRECT_RECONNECT_MAX = 3
        private const val PAIRING_RECONNECT_BASE_MS = 1_000L
        private const val PAIRING_RECONNECT_MAX_MS = 5_000L
    }

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _pingResults = MutableSharedFlow<PingResult>()
    val pingResults: SharedFlow<PingResult> = _pingResults.asSharedFlow()

    private val _pairSubmitInFlight = MutableStateFlow(false)
    /** True after `pairWithPin` until auth outcome or disconnect — drives pairing UI progress. */
    val pairSubmitInFlight: StateFlow<Boolean> = _pairSubmitInFlight.asStateFlow()

    private val _pairingLockoutProbeUi = MutableStateFlow(false)
    /**
     * True during PIN-lockout Retry: keep discovery-style loading through NSD + TLS until the
     * daemon accepts pairing again or rejects (no PIN sheet blink).
     */
    val pairingLockoutProbeUi: StateFlow<Boolean> = _pairingLockoutProbeUi.asStateFlow()

    private val fileTransfers =
            FileTransferRepository(
                    scope,
                    connectionDispatcher,
                    sendJson = { json -> wsClient?.send(json) == true },
                    sendBinary = { bytes -> wsClient?.sendBinary(bytes) == true },
            )

    /** Outbound file upload progress (Android → Linux). */
    val transferProgress: StateFlow<TransferProgress?> = fileTransfers.progress

    private var wsClient: JibeWebSocketHandle? = null
    private var trustManager: JibeTrustManager? = null
    private var eventCollectorJob: Job? = null
    private var reconnectJob: Job? = null
    private var discoveryJob: Job? = null
    private var pingTimeoutJob: Job? = null
    private var pingSentAt: Long = 0
    private var fastReconnectAttempts = 0
    private var pairingRetryCount = 0
    private var pairingPinSubmitted = false
    private var pairingWrongPinAttempts = 0

    private var currentHost: String? = null
    private var currentPort: Int? = null

    private var lastPairingFailureReason: String = ""
    private var lastPairingFailureGuidance: String = PAIRING_FAILURE_GUIDANCE

    private val _featClipboard = MutableStateFlow(true)
    private val _featNotifications = MutableStateFlow(true)
    private val _featFileTransfer = MutableStateFlow(true)
    private val _featPresentationRemote = MutableStateFlow(true)
    private val _featFindPhone = MutableStateFlow(true)
    private val _featPing = MutableStateFlow(false)

    val featClipboardSync: StateFlow<Boolean> = _featClipboard.asStateFlow()
    val featFileTransferEnabled: StateFlow<Boolean> = _featFileTransfer.asStateFlow()
    val featPresentationRemote: StateFlow<Boolean> = _featPresentationRemote.asStateFlow()
    val featPingEnabled: StateFlow<Boolean> = _featPing.asStateFlow()

    init {
        scope.launch {
            dataStore.featClipboard.collect { _featClipboard.value = it }
        }
        scope.launch {
            dataStore.featNotifications.collect { _featNotifications.value = it }
        }
        scope.launch {
            dataStore.featFileTransfer.collect { _featFileTransfer.value = it }
        }
        scope.launch {
            dataStore.featPresentationRemote.collect { _featPresentationRemote.value = it }
        }
        scope.launch {
            dataStore.featFindPhone.collect { enabled ->
                _featFindPhone.value = enabled
                val client = wsClient
                if (client != null && _state.value is ConnectionState.Connected) {
                    client.send(
                            MessageParser.toJson(DeviceFeaturesMessage(featFindPhone = enabled))
                    )
                }
            }
        }
        scope.launch {
            dataStore.featPing.collect { _featPing.value = it }
        }
    }

    /**
     * After lockout, user may tap Retry before restarting the daemon; the next pairing attempt then
     * fails immediately. When true, the first disconnect during Connecting/Authenticating (before a
     * new PIN is sent) restores [ConnectionState.PairingFailed] instead of auto-reconnect loops.
     */
    private var expectPairingFailureOnEarlyDisconnect = false

    /** Suppresses one [handleDisconnection] invocation from intentional teardown (see snap-back). */
    private var skipNextDisconnectedHandling = false

    /**
     * Start the connection flow.
     *
     * If we have saved credentials, attempt auto-reconnect directly. If not, start NSD discovery to
     * find a daemon for first-time pairing.
     */
    fun start() {
        scope.launch {
            val credentials = dataStore.credentials.first()

            if (credentials != null) {
                Log.i(TAG, "Saved credentials found, attempting reconnection")
                connectToDaemon(
                        host = credentials.daemonHost,
                        port = credentials.daemonPort,
                        certFingerprint = credentials.certFingerprint
                )
            } else {
                Log.i(TAG, "No saved credentials, starting discovery")
                startDiscovery()
            }
        }
    }

    /** Begin NSD discovery — used both for first-time pairing and phase-2 reconnection. */
    fun startDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        discovery.stopDiscovery()
        _state.value = ConnectionState.Discovering
        discovery.startDiscovery()

        discoveryJob =
                scope.launch {
                    discovery.state.collect { discoveryState ->
                        if (discoveryState is DiscoveryState.Found) {
                            val daemon = discoveryState.daemon
                            Log.i(
                                    TAG,
                                    "Daemon found: ${daemon.name} at ${daemon.host}:${daemon.port}"
                            )
                            discovery.stopDiscovery()

                            val credentials = dataStore.credentials.first()
                            connectToDaemon(
                                    host = daemon.host,
                                    port = daemon.port,
                                    certFingerprint = credentials?.certFingerprint
                            )
                        }
                    }
                }
    }

    /**
     * Full pairing retry after lockout or user-initiated Retry: tear down socket and discovery
     * jobs, reset PIN-attempt state, then scan again. Avoids overlapping [startDiscovery] with
     * stale reconnect work (which caused Discovering ↔ PIN flicker).
     */
    fun retryPairing(afterPairingLockout: Boolean = false) {
        scope.launch {
            _pairSubmitInFlight.value = false
            if (afterPairingLockout) {
                expectPairingFailureOnEarlyDisconnect = true
                _pairingLockoutProbeUi.value = true
            } else {
                expectPairingFailureOnEarlyDisconnect = false
                _pairingLockoutProbeUi.value = false
            }
            reconnectJob?.cancel()
            reconnectJob = null
            discoveryJob?.cancel()
            discoveryJob = null
            pingTimeoutJob?.cancel()
            pingTimeoutJob = null
            eventCollectorJob?.cancel()
            eventCollectorJob = null

            wsClient?.disconnect()
            wsClient = null
            trustManager = null

            pairingPinSubmitted = false
            pairingWrongPinAttempts = 0
            pairingRetryCount = 0
            fastReconnectAttempts = 0

            discovery.stopDiscovery()
            delay(PAIRING_RETRY_SETTLE_MS)
            startDiscovery()
        }
    }

    /**
     * Pair with the daemon using a 6-digit PIN.
     *
     * Called from the pairing UI after the user enters the PIN. The WebSocket must already be open
     * (state == Authenticating).
     */
    fun pairWithPin(pin: String, deviceName: String) {
        val client = wsClient ?: return
        pairingPinSubmitted = true
        _pairSubmitInFlight.value = true
        val authRequest =
                AuthRequest(
                        deviceName = deviceName,
                        pin = pin,
                        featFindPhone = _featFindPhone.value
                )
        client.send(MessageParser.toJson(authRequest))
        Log.d(TAG, "Sent auth.request with PIN")
    }

    /**
     * Send a ping and measure round-trip latency.
     *
     * The result arrives asynchronously via [pingResults] flow when the pong response comes back.
     */
    /**
     * Push plain text to the daemon clipboard sync channel (requires [ConnectionState.Connected]).
     *
     * @return ``true`` if the message was queued on the socket.
     */
    fun sendClipboardSync(content: String): Boolean {
        val client = wsClient ?: return false
        if (_state.value !is ConnectionState.Connected || !_featClipboard.value) return false
        return client.send(MessageParser.toJson(ClipboardSyncMessage(content = content)))
    }

    /** Forward a mirrored notification payload to the daemon. */
    fun sendNotification(msg: NotificationMessage) {
        val client = wsClient ?: return
        if (_state.value !is ConnectionState.Connected || !_featNotifications.value) return
        client.send(MessageParser.toJson(msg))
    }

    /** Upload a content URI to the daemon into ``~/Downloads`` (requires connected). */
    fun sendFile(uri: Uri, contentResolver: ContentResolver) {
        if (_state.value !is ConnectionState.Connected || !_featFileTransfer.value) return
        fileTransfers.sendFile(uri, contentResolver)
    }

    /** Cancel the active file transfer without disconnecting the daemon session. */
    fun cancelFileTransfer(): Boolean = fileTransfers.cancelActiveTransfer()

    fun sendPing() {
        val client = wsClient ?: return
        if (_state.value !is ConnectionState.Connected || !_featPing.value) return
        pingTimeoutJob?.cancel()
        pingSentAt = System.currentTimeMillis()
        client.send(MessageParser.toJson(PingMessage()))
        Log.d(TAG, "Ping sent")

        pingTimeoutJob =
                scope.launch {
                    delay(PING_TIMEOUT_MS)
                    if (_state.value is ConnectionState.Connected && pingSentAt != 0L) {
                        Log.w(TAG, "Ping timed out after ${PING_TIMEOUT_MS}ms; treating as loss")
                        pingSentAt = 0
                        handleDisconnection()
                    }
                }
    }

    /**
     * Forget the paired device — clears credentials, disconnects, and immediately restarts
     * discovery so the app is ready to re-pair without any user action.
     */
    fun forgetDevice() {
        scope.launch {
            disconnect()
            dataStore.clearCredentials()
            Log.i(TAG, "Device forgotten, credentials cleared")
            startDiscovery()
        }
    }

    /** Clean disconnect — close WebSocket, stop discovery, cancel pending reconnects. */
    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        discoveryJob?.cancel()
        discoveryJob = null
        pingTimeoutJob?.cancel()
        pingTimeoutJob = null
        eventCollectorJob?.cancel()
        eventCollectorJob = null
        pairingPinSubmitted = false
        pairingWrongPinAttempts = 0
        expectPairingFailureOnEarlyDisconnect = false
        skipNextDisconnectedHandling = false
        _pairSubmitInFlight.value = false
        _pairingLockoutProbeUi.value = false
        wsClient?.disconnect()
        wsClient = null
        trustManager = null
        discovery.stopDiscovery()
        fastReconnectAttempts = 0
        pairingRetryCount = 0
        fileTransfers.reset()
        _state.value = ConnectionState.Disconnected
    }

    /** Push current battery telemetry (requires authenticated WebSocket). */
    fun sendBatteryLevel(level: Int, charging: Boolean) {
        val client = wsClient ?: return
        if (_state.value !is ConnectionState.Connected) return
        val pct = level.coerceIn(0, 100)
        client.send(
                MessageParser.toJson(DeviceBatteryMessage(level = pct, charging = charging))
        )
    }

    /** Presentation remote key (see protocol ``remote.key``). */
    fun sendRemoteKey(key: String) {
        val client = wsClient ?: return
        if (_state.value !is ConnectionState.Connected || !_featPresentationRemote.value) return
        client.send(MessageParser.toJson(RemoteKeyMessage(key = key)))
    }

    private fun triggerRingAlert() {
        RingPlayer.start(appContext)

        val intent =
                Intent(appContext, RingAlertActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }

        val ringNotifId = RingAlertActivity.RING_NOTIFICATION_ID
        val fullScreenPi = PendingIntent.getActivity(
                appContext, ringNotifId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        var channel = nm.getNotificationChannel(RING_CHANNEL_ID)
        if (channel == null) {
            channel = NotificationChannel(
                    RING_CHANNEL_ID, "Find my phone",
                    NotificationManager.IMPORTANCE_HIGH
            ).apply { setSound(null, null) }
            nm.createNotificationChannel(channel)
        }

        val notification = Notification.Builder(appContext, RING_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Jibe")
                .setContentText("Find my phone")
                .setContentIntent(fullScreenPi)
                .setFullScreenIntent(fullScreenPi, true)
                .setAutoCancel(true)
                .setOngoing(true)
                .build()
        nm.notify(ringNotifId, notification)
    }

    /**
     * Open a WebSocket to the daemon.
     *
     * @param host The daemon's IP.
     * @param port The daemon's port.
     * @param certFingerprint Pinned cert fingerprint for reconnection, or null for first-time
     * pairing (TOFU mode).
     */
    private fun connectToDaemon(host: String, port: Int, certFingerprint: String?) {
        _pairSubmitInFlight.value = false
        _state.value = ConnectionState.Connecting(host, port)
        currentHost = host
        currentPort = port
        pingTimeoutJob?.cancel()
        pingTimeoutJob = null
        pairingPinSubmitted = false
        if (certFingerprint == null) {
            pairingWrongPinAttempts = 0
        }

        eventCollectorJob?.cancel()
        eventCollectorJob = null

        wsClient?.disconnect()
        val (ws, tm) = socketFactory.create(certFingerprint)
        trustManager = tm

        wsClient = ws

        eventCollectorJob =
                scope.launch {
                    ws.events.collect { event -> handleWebSocketEvent(event, certFingerprint) }
                }

        scope.launch(connectionDispatcher) { ws.connect(host, port) }
    }

    /** Handle events from the WebSocket — the core state machine transitions. */
    private suspend fun handleWebSocketEvent(event: WebSocketEvent, certFingerprint: String?) {
        when (event) {
            is WebSocketEvent.Connected -> {
                val host = currentHost ?: "unknown"
                _pairSubmitInFlight.value = false

                pairingRetryCount = 0

                val credentials = dataStore.credentials.first()
                val silentHandshakeUi =
                        credentials == null && expectPairingFailureOnEarlyDisconnect

                if (!silentHandshakeUi) {
                    _state.value = ConnectionState.Authenticating(host)
                }

                val deviceLabel = deviceNameProvider.deviceDisplayName()
                if (credentials != null) {
                    val authRequest =
                            AuthRequest(
                                    deviceName = deviceLabel,
                                    fingerprint = credentials.fingerprint,
                                    featFindPhone = _featFindPhone.value
                            )
                    wsClient?.send(MessageParser.toJson(authRequest))
                    Log.d(TAG, "Sent auto-reconnect auth.request with fingerprint")
                } else {
                    val probe =
                            AuthRequest(
                                    deviceName = deviceLabel,
                                    featFindPhone = _featFindPhone.value
                            )
                    wsClient?.send(MessageParser.toJson(probe))
                    Log.d(TAG, "Sent pairing probe to trigger daemon PIN generation")
                }
            }
            is WebSocketEvent.MessageReceived -> {
                handleMessage(event.message)
            }
            is WebSocketEvent.Disconnected -> {
                Log.i(TAG, "Disconnected: ${event.code} ${event.reason}")
                handleDisconnection()
            }
            is WebSocketEvent.Error -> {
                Log.e(TAG, "Connection error: ${event.throwable.message}")
                handleDisconnection()
            }
        }
    }

    /** Route incoming messages to the appropriate handler. */
    private suspend fun handleMessage(message: com.jibe.app.data.model.JibeMessage) {
        when (message.type) {
            MessageType.AUTH_RESPONSE -> {
                val response = MessageParser.payloadAs<AuthResponse>(message)
                handleAuthResponse(response)
            }
            MessageType.PONG -> {
                val latency = System.currentTimeMillis() - pingSentAt
                pingTimeoutJob?.cancel()
                pingTimeoutJob = null
                pingSentAt = 0
                Log.d(TAG, "Pong received, latency: ${latency}ms")
                _pingResults.emit(PingResult(latency))
            }
            MessageType.CLIPBOARD_SYNC -> {
                if (!_featClipboard.value) return
                val sync = MessageParser.payloadAs<ClipboardSyncMessage>(message)
                clipboardWriter.setPlainText(sync.content)
            }
            MessageType.DEVICE_RING -> {
                if (!_featFindPhone.value) return
                triggerRingAlert()
            }
            MessageType.FILE_ACK -> {
                val ack = MessageParser.payloadAs<FileAckMessage>(message)
                fileTransfers.onFileAck(ack)
            }
            MessageType.FILE_CHUNK_ACK -> {
                val ack = MessageParser.payloadAs<FileChunkAckMessage>(message)
                fileTransfers.onFileChunkAck(ack)
            }
            MessageType.ERROR -> {
                val error = MessageParser.payloadAs<ErrorMessage>(message)
                Log.w(TAG, "Server error: [${error.code}] ${error.message}")
                if (error.code == "auth_rejected" || error.code == "auth_required") {
                    val credentials = dataStore.credentials.first()
                    if (credentials == null && pairingPinSubmitted) {
                        pairingPinSubmitted = false
                        pairingWrongPinAttempts = 0
                        transitionToPairingFailed(reason = error.message)
                    } else if (credentials == null) {
                        if (expectPairingFailureOnEarlyDisconnect) {
                            transitionToPairingFailed(reason = error.message)
                        } else if (isPairingModeInactiveReason(error.message)) {
                            transitionToPairingUnavailable(reason = error.message)
                        } else {
                            expectPairingFailureOnEarlyDisconnect = false
                            _pairingLockoutProbeUi.value = false
                            _state.value =
                                    ConnectionState.Authenticating(
                                            currentHost ?: "unknown",
                                            hint = error.message
                                    )
                        }
                    } else {
                        _state.value = ConnectionState.Failed(error.message)
                    }
                }
            }
            else -> {
                Log.d(TAG, "Unhandled message type: ${message.type}")
            }
        }
    }

    /** Process auth.response — the outcome of pairing or reconnection. */
    private suspend fun handleAuthResponse(response: AuthResponse) {
        _pairSubmitInFlight.value = false

        if (response.accepted) {
            val host = currentHost ?: "unknown"
            val port = currentPort ?: 0
            val deviceId = response.deviceId ?: "unknown"
            val fingerprint = response.fingerprint ?: ""
            val certFp = trustManager?.lastSeenFingerprint ?: ""

            dataStore.saveCredentials(
                    DeviceCredentials(
                            deviceId = deviceId,
                            fingerprint = fingerprint,
                            daemonHost = host,
                            daemonPort = port,
                            certFingerprint = certFp
                    )
            )

            fastReconnectAttempts = 0
            pairingPinSubmitted = false
            pairingWrongPinAttempts = 0
            expectPairingFailureOnEarlyDisconnect = false
            _pairingLockoutProbeUi.value = false
            _state.value = ConnectionState.Connected(host, port, deviceId)
            Log.i(TAG, "Authenticated as device $deviceId")
        } else {
            val credentials = dataStore.credentials.first()
            if (credentials == null) {
                val host = currentHost ?: "unknown"
                if (pairingPinSubmitted) {
                    pairingPinSubmitted = false
                    pairingWrongPinAttempts++
                    Log.w(
                            TAG,
                            "Pairing rejected after PIN entry ($pairingWrongPinAttempts/$MAX_PAIRING_PIN_FAILURES): ${response.reason}"
                    )
                    if (pairingWrongPinAttempts >= MAX_PAIRING_PIN_FAILURES) {
                        pairingWrongPinAttempts = 0
                        transitionToPairingFailed(reason = response.reason)
                    } else {
                        expectPairingFailureOnEarlyDisconnect = false
                        _pairingLockoutProbeUi.value = false
                        val remaining = MAX_PAIRING_PIN_FAILURES - pairingWrongPinAttempts
                        val hint =
                                "$WRONG_PAIRING_PIN_HINT_PREFIX — $remaining ${if (remaining == 1) "attempt" else "attempts"} remaining"
                        _state.value = ConnectionState.Authenticating(host, hint = hint)
                    }
                } else {
                    Log.d(
                            TAG,
                            "Probe rejected (pairing not yet active or just started): ${response.reason}"
                    )
                    expectPairingFailureOnEarlyDisconnect = false
                    _pairingLockoutProbeUi.value = false
                    if (isPairingModeInactiveReason(response.reason)) {
                        transitionToPairingUnavailable(reason = response.reason)
                    } else {
                        _state.value =
                                ConnectionState.Authenticating(host, hint = response.reason)
                    }
                }
            } else {
                Log.w(TAG, "Auth rejected: ${response.reason}")
                _state.value = ConnectionState.Failed(response.reason)
            }
        }
    }

    private fun transitionToPairingFailed(
            reason: String,
            guidance: String = PAIRING_FAILURE_GUIDANCE
    ) {
        _pairSubmitInFlight.value = false
        _pairingLockoutProbeUi.value = false
        lastPairingFailureReason = reason
        lastPairingFailureGuidance = guidance
        expectPairingFailureOnEarlyDisconnect = false
        _state.value = ConnectionState.PairingFailed(reason, guidance)
    }

    private fun transitionToPairingUnavailable(
            reason: String,
            guidance: String = PAIRING_INACTIVE_GUIDANCE
    ) {
        _pairSubmitInFlight.value = false
        _pairingLockoutProbeUi.value = false
        pairingPinSubmitted = false
        pairingWrongPinAttempts = 0
        expectPairingFailureOnEarlyDisconnect = false
        _state.value = ConnectionState.PairingUnavailable(reason, guidance)
    }

    private fun isPairingModeInactiveReason(reason: String): Boolean =
            reason.contains("not active", ignoreCase = true)

    /** Handle disconnection using a two-phase reconnection strategy. */
    private fun handleDisconnection() {
        if (skipNextDisconnectedHandling) {
            skipNextDisconnectedHandling = false
            return
        }

        _pairSubmitInFlight.value = false
        fileTransfers.reset()

        val stateAtDisconnect = _state.value

        reconnectJob?.cancel()
        reconnectJob =
                scope.launch {
                    val credentials = dataStore.credentials.first()

                    if (credentials == null) {
                        fastReconnectAttempts = 0

                        when (stateAtDisconnect) {
                            is ConnectionState.PairingUnavailable -> {
                                Log.i(
                                        TAG,
                                        "Daemon disappeared while pairing inactive — restarting NSD discovery"
                                )
                                startDiscovery()
                                return@launch
                            }
                            is ConnectionState.PairingFailed -> {
                                _state.value = stateAtDisconnect
                            }
                            is ConnectionState.Authenticating -> {
                                val host = currentHost
                                val port = currentPort
                                pairingRetryCount++
                                val backoffMs =
                                        min(
                                                PAIRING_RECONNECT_BASE_MS * pairingRetryCount,
                                                PAIRING_RECONNECT_MAX_MS
                                        )
                                Log.d(
                                        TAG,
                                        "Pairing reconnect #$pairingRetryCount — waiting ${backoffMs}ms"
                                )
                                if (host != null &&
                                                port != null &&
                                                pairingRetryCount <= PAIRING_DIRECT_RECONNECT_MAX
                                ) {
                                    _state.value = ConnectionState.Connecting(host, port)
                                    delay(backoffMs)
                                    connectToDaemon(host, port, certFingerprint = null)
                                } else {
                                    delay(backoffMs)
                                    Log.i(
                                            TAG,
                                            "Direct reconnects exhausted — falling back to discovery"
                                    )
                                    startDiscovery()
                                }
                            }
                            is ConnectionState.Connecting -> {
                                pairingRetryCount++
                                val backoffMs =
                                        min(
                                                PAIRING_RECONNECT_BASE_MS * pairingRetryCount,
                                                RECONNECT_MAX_DELAY_MS
                                        )
                                Log.d(
                                        TAG,
                                        "Stale mDNS bounce #$pairingRetryCount — waiting ${backoffMs}ms"
                                )
                                delay(backoffMs)
                                startDiscovery()
                            }
                            is ConnectionState.Failed -> {
                                Log.i(TAG, "Rate-limited or failed — recovering in 3s")
                                delay(3_000L)
                                startDiscovery()
                            }
                            else -> {
                                _state.value = ConnectionState.Disconnected
                            }
                        }
                        return@launch
                    }

                    if (fastReconnectAttempts < MAX_FAST_RECONNECTS) {
                        fastReconnectAttempts++
                        val delayMs =
                                min(
                                        (RECONNECT_BASE_DELAY_MS *
                                                        2.0.pow(fastReconnectAttempts - 1))
                                                .toLong(),
                                        RECONNECT_MAX_DELAY_MS
                                )
                        Log.i(
                                TAG,
                                "Fast reconnect $fastReconnectAttempts/$MAX_FAST_RECONNECTS " +
                                        "in ${delayMs}ms → ${credentials.daemonHost}:${credentials.daemonPort}"
                        )
                        _state.value =
                                ConnectionState.Connecting(
                                        credentials.daemonHost,
                                        credentials.daemonPort
                                )
                        delay(delayMs)
                        connectToDaemon(
                                credentials.daemonHost,
                                credentials.daemonPort,
                                credentials.certFingerprint
                        )
                        return@launch
                    }

                    Log.i(TAG, "Fast reconnects exhausted — switching to NSD re-discovery")
                    fastReconnectAttempts = 0
                    startDiscovery()
                }
    }
}
