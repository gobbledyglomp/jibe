package com.jibe.app.network

import java.security.SecureRandom
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
                        .build()

        return client to trustManager
    }
}
