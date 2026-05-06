package com.jibe.app.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A discovered Jibe daemon on the local network.
 *
 * Resolved from mDNS — contains everything needed to open a WebSocket.
 */
data class DiscoveredDaemon(val name: String, val host: String, val port: Int)

/** Discovery states — drives the UI indicator on the pairing screen. */
sealed class DiscoveryState {
    data object Idle : DiscoveryState()
    data object Searching : DiscoveryState()
    data class Found(val daemon: DiscoveredDaemon) : DiscoveryState()
    data class Error(val message: String) : DiscoveryState()
}

/**
 * Wraps Android's NsdManager to discover Jibe daemons on the LAN.
 *
 * The daemon registers itself as _jibe._tcp.local. via zeroconf. Android's NSD (Network Service
 * Discovery) API is the platform's built-in mDNS client — no extra libraries needed.
 *
 * NSD quirks to be aware of:
 * - Discovery and resolution are callback-based (not coroutine-friendly), so we bridge to StateFlow
 * for the Compose UI.
 * - NsdManager must be obtained from a Context (it's a system service).
 * - You must stop discovery before the Activity/Service is destroyed, or Android will leak the
 * listener and eventually crash.
 * - Resolution gives us the actual IP + port (discovery alone only gives us the service name).
 */
class JibeDiscovery(context: Context) {

    companion object {
        private const val TAG = "JibeDiscovery"
        /** Must match the daemon's SERVICE_TYPE in config.py. */
        private const val SERVICE_TYPE = "_jibe._tcp."
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _state = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    val state: StateFlow<DiscoveryState> = _state.asStateFlow()

    private var isDiscovering = false

    // ── NSD callbacks ───────────────────────────────────────────────

    private val discoveryListener =
            object : NsdManager.DiscoveryListener {

                override fun onDiscoveryStarted(serviceType: String) {
                    Log.d(TAG, "Discovery started for $serviceType")
                    _state.value = DiscoveryState.Searching
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "Service found: ${serviceInfo.serviceName}")
                    resolveService(serviceInfo)
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "Service lost: ${serviceInfo.serviceName}")
                    val current = _state.value
                    if (current is DiscoveryState.Found &&
                                    current.daemon.name == serviceInfo.serviceName
                    ) {
                        _state.value = DiscoveryState.Searching
                    }
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Log.d(TAG, "Discovery stopped for $serviceType")
                    isDiscovering = false
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "Discovery start failed: error code $errorCode")
                    _state.value = DiscoveryState.Error("Discovery failed (error $errorCode)")
                    isDiscovering = false
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "Discovery stop failed: error code $errorCode")
                }
            }

    // ── Public API ──────────────────────────────────────────────────

    /**
     * Start scanning for Jibe daemons on the local network.
     *
     * Results arrive asynchronously via the [state] flow. Call [stopDiscovery] when done (e.g.
     * after successful pairing).
     */
    fun startDiscovery() {
        if (isDiscovering) {
            Log.d(TAG, "Discovery already running, skipping")
            return
        }

        isDiscovering = true
        _state.value = DiscoveryState.Searching

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    /**
     * Stop scanning. Must be called before the host Activity/Service is destroyed to avoid leaking
     * the NSD listener.
     */
    fun stopDiscovery() {
        if (!isDiscovering) return

        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "stopDiscovery called but listener not registered: ${e.message}")
        }
        isDiscovering = false
        _state.value = DiscoveryState.Idle
    }

    // ── Internals ───────────────────────────────────────────────────

    /**
     * Resolve a discovered service to get its IP and port.
     *
     * NsdManager.resolveService is also callback-based. On success, we update the state flow with a
     * DiscoveredDaemon containing the resolved host and port.
     */
    private fun resolveService(serviceInfo: NsdServiceInfo) {
        nsdManager.resolveService(
                serviceInfo,
                object : NsdManager.ResolveListener {

                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.e(
                                TAG,
                                "Resolve failed for ${serviceInfo.serviceName}: error $errorCode"
                        )
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = serviceInfo.host?.hostAddress ?: return
                        val port = serviceInfo.port

                        Log.i(TAG, "Resolved ${serviceInfo.serviceName} → $host:$port")

                        val daemon =
                                DiscoveredDaemon(
                                        name = serviceInfo.serviceName,
                                        host = host,
                                        port = port
                                )
                        _state.value = DiscoveryState.Found(daemon)
                    }
                }
        )
    }
}
