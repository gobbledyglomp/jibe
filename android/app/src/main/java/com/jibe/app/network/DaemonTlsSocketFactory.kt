package com.jibe.app.network

import kotlinx.coroutines.CoroutineScope

/**
 * Builds a TLS-backed WebSocket handle plus the trust manager used for certificate pinning / TOFU
 * fingerprint capture.
 */
fun interface DaemonTlsSocketFactory {
    fun create(certFingerprint: String?): Pair<JibeWebSocketHandle, JibeTrustManager>
}

/** Production factory wiring OkHttp + [JibeWebSocketClient]. */
class OkHttpDaemonTlsSocketFactory(
        private val callbackScope: CoroutineScope,
) : DaemonTlsSocketFactory {
    override fun create(certFingerprint: String?): Pair<JibeWebSocketHandle, JibeTrustManager> {
        val (okHttp, trustManager) = OkHttpFactory.create(certFingerprint)
        val client = JibeWebSocketClient(okHttp, callbackScope)
        return client to trustManager
    }
}
