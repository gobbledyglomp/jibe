package com.jibe.app.network

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Trust-on-First-Use (TOFU) TLS trust manager for self-signed certificates.
 *
 * The Jibe daemon serves a self-signed certificate (no CA involved). This means Android's default
 * trust manager will reject it outright. We need a custom one that mirrors the SSH known_hosts
 * model:
 *
 * 1. First connection (pairing): accept any cert, extract its SHA-256 fingerprint so we can store
 * it alongside the device credentials.
 *
 * 2. Subsequent connections (reconnection): reject any cert whose fingerprint doesn't match the
 * stored one — this is cert pinning.
 *
 * Why not just trust all certs? Because a LAN is not immune to MITM. An attacker on the same
 * network could intercept the WebSocket if we blindly trust every certificate after the first
 * connection.
 *
 * @param trustedFingerprint The SHA-256 fingerprint of the previously seen daemon cert
 * (colon-separated hex). Null during first-time pairing, meaning we accept any cert and let the
 * caller extract the fingerprint.
 */
class JibeTrustManager(private val trustedFingerprint: String? = null) : X509TrustManager {

    var lastSeenFingerprint: String? = null
        private set

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // Not applicable — we are the client, not the server.
    }

    /**
     * Core validation logic: either TOFU-accept or pin-verify.
     *
     * @throws CertificateException if the cert doesn't match the pinned fingerprint (reconnection
     * mode only).
     */
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) {
            throw CertificateException("Server presented no certificate")
        }

        val serverCert = chain[0]
        val fingerprint = computeFingerprint(serverCert)
        lastSeenFingerprint = fingerprint

        if (trustedFingerprint != null && fingerprint != trustedFingerprint) {
            throw CertificateException(
                    "Certificate fingerprint mismatch. " +
                            "Expected: $trustedFingerprint, " +
                            "Got: $fingerprint. " +
                            "The daemon may have regenerated its certificate. Re-pair to continue."
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        /**
         * Compute the SHA-256 fingerprint of a certificate.
         *
         * Returns a colon-separated uppercase hex string matching the format used by the daemon's
         * _get_cert_fingerprint() in tls.py: e.g. "AB:CD:EF:01:23:..."
         */
        fun computeFingerprint(cert: X509Certificate): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(cert.encoded)
            return hash.joinToString(":") { "%02X".format(it) }
        }
    }
}
