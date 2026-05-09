package com.jibe.app.network

/**
 * Builds a TLS-backed WebSocket handle plus the trust manager used for certificate pinning / TOFU
 * fingerprint capture.
 */
fun interface DaemonTlsSocketFactory {
    fun create(certFingerprint: String?): Pair<JibeWebSocketHandle, JibeTrustManager>
}

/** Production factory wiring OkHttp + [JibeWebSocketClient]. */
class OkHttpDaemonTlsSocketFactory : DaemonTlsSocketFactory {
    override fun create(certFingerprint: String?): Pair<JibeWebSocketHandle, JibeTrustManager> {
        val (okHttp, trustManager) = OkHttpFactory.create(certFingerprint)
        val client = JibeWebSocketClient(okHttp)
        return client to trustManager
    }
}
