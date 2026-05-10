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
 * ``stopServiceDiscovery`` completes asynchronously; calling ``discoverServices`` again before
 * [NsdManager.DiscoveryListener.onDiscoveryStopped] fires breaks NSD. A follow-up [startDiscovery]
 * sets [pendingRestart] so discovery begins from ``onDiscoveryStopped``.
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

    /** True while ``discoverServices`` is active (until ``onDiscoveryStopped``). */
    private var isDiscovering = false

    /** True after ``stopServiceDiscovery`` until ``onDiscoveryStopped``. */
    private var stopInFlight = false

    /** [startDiscovery] was requested while a stop was still completing. */
    private var pendingRestart = false

    /** Bumped on every [stopDiscovery] so late [onServiceResolved] callbacks cannot emit stale Found. */
    private var resolveEpoch = 0

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
                    stopInFlight = false
                    isDiscovering = false
                    _state.value = DiscoveryState.Idle

                    if (pendingRestart) {
                        pendingRestart = false
                        beginDiscoverServices()
                    }
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "Discovery start failed: error code $errorCode")
                    _state.value = DiscoveryState.Error("Discovery failed (error $errorCode)")
                    isDiscovering = false
                    stopInFlight = false
                    pendingRestart = false
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "Discovery stop failed: error code $errorCode")
                    stopInFlight = false
                    isDiscovering = false
                    _state.value = DiscoveryState.Idle
                    if (pendingRestart) {
                        pendingRestart = false
                        beginDiscoverServices()
                    }
                }
            }

    /** Start scanning for Jibe daemons on the local network. */
    fun startDiscovery() {
        if (stopInFlight) {
            pendingRestart = true
            Log.d(TAG, "Discovery stop in flight — scheduling restart when stopped")
            return
        }
        if (isDiscovering) {
            Log.d(TAG, "Discovery already running, skipping")
            return
        }
        beginDiscoverServices()
    }

    private fun beginDiscoverServices() {
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            isDiscovering = true
            stopInFlight = false
            _state.value = DiscoveryState.Searching
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "discoverServices failed: ${e.message}")
            isDiscovering = false
            _state.value = DiscoveryState.Error(e.message ?: "Discovery failed")
        }
    }

    /**
     * Stop scanning. Must be called before the host Activity/Service is destroyed to avoid leaking
     * the NSD listener.
     */
    fun stopDiscovery() {
        resolveEpoch++
        pendingRestart = false

        if (!isDiscovering) {
            _state.value = DiscoveryState.Idle
            return
        }

        stopInFlight = true
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "stopDiscovery called but listener not registered: ${e.message}")
            stopInFlight = false
            isDiscovering = false
            _state.value = DiscoveryState.Idle
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val epochAtResolve = resolveEpoch
        nsdManager.resolveService(
                serviceInfo,
                object : NsdManager.ResolveListener {

                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        if (epochAtResolve != resolveEpoch) return
                        Log.e(
                                TAG,
                                "Resolve failed for ${serviceInfo.serviceName}: error $errorCode"
                        )
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (epochAtResolve != resolveEpoch) {
                            Log.d(
                                    TAG,
                                    "Ignoring stale resolve for ${serviceInfo.serviceName} " +
                                            "(epoch $epochAtResolve vs $resolveEpoch)"
                            )
                            return
                        }
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
