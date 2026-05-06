package com.jibe.app.data.repository

import android.util.Log
import com.jibe.app.data.local.DeviceCredentials
import com.jibe.app.data.local.JibeDataStore
import com.jibe.app.data.model.AuthRequest
import com.jibe.app.data.model.AuthResponse
import com.jibe.app.data.model.ErrorMessage
import com.jibe.app.data.model.MessageType
import com.jibe.app.data.model.PingMessage
import com.jibe.app.network.DiscoveryState
import com.jibe.app.network.JibeDiscovery
import com.jibe.app.network.JibeTrustManager
import com.jibe.app.network.JibeWebSocketClient
import com.jibe.app.network.MessageParser
import com.jibe.app.network.OkHttpFactory
import com.jibe.app.network.WebSocketEvent
import kotlinx.coroutines.CoroutineScope
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

    /** WebSocket open, waiting for auth.response from daemon. */
    data class Authenticating(val host: String) : ConnectionState()

    /** Fully authenticated — ready for message exchange. */
    data class Connected(val host: String, val port: Int, val deviceId: String) : ConnectionState()

    /** Something went wrong — human-readable error for the UI. */
    data class Failed(val reason: String) : ConnectionState()
}

/** Ping result — emitted after a pong arrives. */
data class PingResult(val latencyMs: Long)

/**
 * The single source of truth for the daemon connection.
 *
 * Orchestrates: NSD discovery ↔ WebSocket ↔ DataStore persistence. Exposes state as a StateFlow
 * that the ViewModel observes.
 *
 * This is the "Repository" in MVVM — it owns the data layer and hides the complexity of networking,
 * TLS, and persistence from the UI.
 *
 * Lifecycle: owned by the Foreground Service, survives Activity recreation.
 */
class ConnectionRepository(
        private val dataStore: JibeDataStore,
        private val discovery: JibeDiscovery,
        private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "ConnectionRepo"
        private const val RECONNECT_DELAY_MS = 3000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _pingResults = MutableSharedFlow<PingResult>()
    val pingResults: SharedFlow<PingResult> = _pingResults.asSharedFlow()

    private var wsClient: JibeWebSocketClient? = null
    private var trustManager: JibeTrustManager? = null
    private var eventCollectorJob: Job? = null
    private var pingSentAt: Long = 0
    private var reconnectAttempts = 0

    private var currentHost: String? = null
    private var currentPort: Int? = null

    // ── Public API ──────────────────────────────────────────────────

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

    /** Begin NSD discovery for first-time pairing. */
    fun startDiscovery() {
        _state.value = ConnectionState.Discovering
        discovery.startDiscovery()

        scope.launch {
            discovery.state.collect { discoveryState ->
                if (discoveryState is DiscoveryState.Found) {
                    val daemon = discoveryState.daemon
                    Log.i(TAG, "Daemon found: ${daemon.name} at ${daemon.host}:${daemon.port}")
                    discovery.stopDiscovery()
                    connectToDaemon(daemon.host, daemon.port, certFingerprint = null)
                }
            }
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

        val authRequest = AuthRequest(deviceName = deviceName, pin = pin)
        client.send(MessageParser.toJson(authRequest))
        Log.d(TAG, "Sent auth.request with PIN")
    }

    /**
     * Send a ping and measure round-trip latency.
     *
     * The result arrives asynchronously via [pingResults] flow when the pong response comes back.
     */
    fun sendPing() {
        val client = wsClient ?: return
        pingSentAt = System.currentTimeMillis()
        client.send(MessageParser.toJson(PingMessage()))
        Log.d(TAG, "Ping sent")
    }

    /**
     * Forget the paired device — clears credentials and disconnects.
     *
     * After this, the credentials flow emits null, which triggers the UI to navigate back to the
     * pairing screen.
     */
    fun forgetDevice() {
        scope.launch {
            disconnect()
            dataStore.clearCredentials()
            Log.i(TAG, "Device forgotten, credentials cleared")
        }
    }

    /** Clean disconnect — close WebSocket, stop discovery. */
    fun disconnect() {
        eventCollectorJob?.cancel()
        eventCollectorJob = null
        wsClient?.disconnect()
        wsClient = null
        trustManager = null
        discovery.stopDiscovery()
        _state.value = ConnectionState.Disconnected
    }

    // ── Internal connection logic ───────────────────────────────────

    /**
     * Open a WebSocket to the daemon.
     *
     * @param host The daemon's IP.
     * @param port The daemon's port.
     * @param certFingerprint Pinned cert fingerprint for reconnection, or null for first-time
     * pairing (TOFU mode).
     */
    private fun connectToDaemon(host: String, port: Int, certFingerprint: String?) {
        _state.value = ConnectionState.Connecting(host, port)
        currentHost = host
        currentPort = port

        val (client, tm) = OkHttpFactory.create(certFingerprint)
        trustManager = tm

        val ws = JibeWebSocketClient(client)
        wsClient = ws

        eventCollectorJob?.cancel()
        eventCollectorJob =
                scope.launch {
                    ws.events.collect { event -> handleWebSocketEvent(event, certFingerprint) }
                }

        ws.connect(host, port)
    }

    /** Handle events from the WebSocket — the core state machine transitions. */
    private suspend fun handleWebSocketEvent(event: WebSocketEvent, certFingerprint: String?) {
        when (event) {
            is WebSocketEvent.Connected -> {
                val host = currentHost ?: "unknown"
                _state.value = ConnectionState.Authenticating(host)

                val credentials = dataStore.credentials.first()
                if (credentials != null) {
                    val authRequest =
                            AuthRequest(
                                    deviceName = android.os.Build.MODEL,
                                    fingerprint = credentials.fingerprint
                            )
                    wsClient?.send(MessageParser.toJson(authRequest))
                    Log.d(TAG, "Sent auto-reconnect auth.request with fingerprint")
                }
                // If no credentials, we're in pairing mode — wait for user to enter PIN
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
                Log.d(TAG, "Pong received, latency: ${latency}ms")
                _pingResults.emit(PingResult(latency))
            }
            MessageType.ERROR -> {
                val error = MessageParser.payloadAs<ErrorMessage>(message)
                Log.w(TAG, "Server error: [${error.code}] ${error.message}")

                if (error.code == "auth_rejected" || error.code == "auth_required") {
                    _state.value = ConnectionState.Failed(error.message)
                }
            }
            else -> {
                Log.d(TAG, "Unhandled message type: ${message.type}")
            }
        }
    }

    /** Process auth.response — the outcome of pairing or reconnection. */
    private suspend fun handleAuthResponse(response: AuthResponse) {
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

            reconnectAttempts = 0
            _state.value = ConnectionState.Connected(host, port, deviceId)
            Log.i(TAG, "Authenticated as device $deviceId")
        } else {
            Log.w(TAG, "Auth rejected: ${response.reason}")
            _state.value = ConnectionState.Failed(response.reason)
        }
    }

    /**
     * Handle disconnection with auto-reconnect logic.
     *
     * Only auto-reconnects if we were previously connected (have credentials) and haven't exceeded
     * the retry limit.
     */
    private fun handleDisconnection() {
        val wasConnected = _state.value is ConnectionState.Connected

        scope.launch {
            val credentials = dataStore.credentials.first()

            if ((wasConnected || _state.value is ConnectionState.Authenticating) &&
                            credentials != null &&
                            reconnectAttempts < MAX_RECONNECT_ATTEMPTS
            ) {
                reconnectAttempts++
                Log.i(TAG, "Reconnecting (attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)")
                _state.value =
                        ConnectionState.Connecting(credentials.daemonHost, credentials.daemonPort)
                delay(RECONNECT_DELAY_MS)
                connectToDaemon(
                        credentials.daemonHost,
                        credentials.daemonPort,
                        credentials.certFingerprint
                )
            } else {
                _state.value = ConnectionState.Disconnected
                reconnectAttempts = 0
            }
        }
    }
}
