package com.jibe.app.network

import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import okhttp3.OkHttpClient

/**
 * Factory for OkHttpClient instances configured for Jibe's TLS model.
 *
 * Jibe uses self-signed certificates, so we can't rely on Android's default certificate authority
 * trust chain. Instead, we inject our custom JibeTrustManager that implements TOFU + cert pinning.
 *
 * Two modes:
 * - Pairing: trustedFingerprint is null → accept any cert
 * - Reconnection: trustedFingerprint is set → reject cert mismatches
 */
object OkHttpFactory {

    /** OkHttp default read timeout is 10s; large ``file.chunk`` streams exceed that before any inbound traffic. */
    private const val SOCKET_TIMEOUT_SEC = 0L

    /** Client-initiated WebSocket pings so long uploads do not look idle to middleboxes or stacks. */
    private const val WEBSOCKET_PING_INTERVAL_SEC = 20L

    /**
     * Create an OkHttpClient wired with the Jibe TLS trust manager.
     *
     * @param trustedFingerprint The pinned cert fingerprint, or null for first-time pairing mode.
     * @return A pair of (OkHttpClient, JibeTrustManager) — the caller needs the trust manager
     * reference to read the lastSeenFingerprint after a successful TLS handshake during pairing.
     */
    fun create(trustedFingerprint: String? = null): Pair<OkHttpClient, JibeTrustManager> {
        val trustManager = JibeTrustManager(trustedFingerprint)

        val sslContext =
                SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf(trustManager), SecureRandom())
                }

        val client =
                OkHttpClient.Builder()
                        .sslSocketFactory(sslContext.socketFactory, trustManager)
                        .hostnameVerifier { _, _ -> true }
                        // Long uploads send many outbound chunks without inbound app messages until
                        // file.done; OkHttp defaults (read timeout 10s) close the socket mid-transfer.
                        .readTimeout(SOCKET_TIMEOUT_SEC, TimeUnit.SECONDS)
                        .writeTimeout(SOCKET_TIMEOUT_SEC, TimeUnit.SECONDS)
                        .callTimeout(SOCKET_TIMEOUT_SEC, TimeUnit.SECONDS)
                        .pingInterval(WEBSOCKET_PING_INTERVAL_SEC, TimeUnit.SECONDS)
                        .build()

        return client to trustManager
    }
}
