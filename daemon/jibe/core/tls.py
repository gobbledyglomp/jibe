"""TLS certificate management for the Jibe daemon.

This module handles the self-signed certificate that encrypts all
WebSocket traffic between the daemon and Android clients.

The daemon follows an SSH-like trust model:
  - A permanent RSA key pair and self-signed certificate are generated
    on first run and stored in `~/.local/share/jibe/certs/`.
  - The same certificate is reused across every daemon restart.
  - The certificate fingerprint (SHA-256) is logged on startup and will
    be embedded in the QR code for out-of-band TOFU when the tray/web
    UI is built.

Why self-signed instead of Let's Encrypt?
  The daemon runs on a local network without a public domain name.
  Let's Encrypt requires domain validation, which isn't possible here.
  Self-signed + certificate pinning (via QR code) is the standard
  approach for local-network services (similar to SSH, Syncthing, etc).

Why RSA 4096?
  Provides a strong security margin. Ed25519 would be more elegant but
  Android's OkHttp TLS stack has inconsistent Ed25519 support across
  API levels. RSA 4096 is universally supported.
"""

import hashlib
import ipaddress
import logging
import ssl
from datetime import datetime, timedelta, timezone
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

from jibe.core.config import (
    CERT_FILE,
    CERT_KEY_SIZE,
    CERT_VALIDITY_DAYS,
    CERTS_DIR,
    KEY_FILE,
)

logger = logging.getLogger(__name__)


def _get_cert_fingerprint(cert_path: Path) -> str:
    """Compute the SHA-256 fingerprint of a PEM certificate.

    This is the same format browsers display ("SHA-256: AB:CD:EF:...")
    and what the QR code will contain for certificate pinning.

    Args:
        cert_path: Path to the PEM-encoded certificate file.

    Returns:
        Colon-separated uppercase hex string of the SHA-256 digest.
    """
    cert_pem = cert_path.read_bytes()
    cert = x509.load_pem_x509_certificate(cert_pem)
    digest = hashlib.sha256(cert.public_bytes(serialization.Encoding.DER)).digest()
    return ":".join(f"{b:02X}" for b in digest)


def generate_self_signed_cert(
    certs_dir: Path = CERTS_DIR,
    cert_name: str = CERT_FILE,
    key_name: str = KEY_FILE,
) -> tuple[Path, Path]:
    """Generate a self-signed RSA certificate and private key.

    The certificate is written to `certs_dir/cert_name` and the key to
    `certs_dir/key_name`. The parent directory is created if it doesn't
    exist.

    If both files already exist, this function is a no-op — it returns
    the existing paths without regenerating. This is intentional: the
    certificate is permanent (SSH-like model).

    Args:
        certs_dir: Directory to store cert and key files.
        cert_name: Filename for the certificate.
        key_name: Filename for the private key.

    Returns:
        A tuple of (cert_path, key_path).
    """
    cert_path = certs_dir / cert_name
    key_path = certs_dir / key_name

    if cert_path.exists() and key_path.exists():
        fingerprint = _get_cert_fingerprint(cert_path)
        logger.debug("Using existing TLS certificate (fingerprint: %s)", fingerprint)
        return cert_path, key_path

    logger.info("Generating new self-signed TLS certificate...")

    certs_dir.mkdir(parents=True, exist_ok=True)

    key = rsa.generate_private_key(
        public_exponent=65537,
        key_size=CERT_KEY_SIZE,
    )

    subject = issuer = x509.Name(
        [
            x509.NameAttribute(NameOID.COMMON_NAME, "Jibe Daemon"),
            x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Jibe"),
        ]
    )

    now = datetime.now(timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(issuer)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now)
        .not_valid_after(now + timedelta(days=CERT_VALIDITY_DAYS))
        .add_extension(
            x509.SubjectAlternativeName(
                [
                    x509.DNSName("localhost"),
                    x509.DNSName("jibe.local"),
                    x509.IPAddress(ipaddress.IPv4Address("127.0.0.1")),
                ]
            ),
            critical=False,
        )
        .sign(key, hashes.SHA256())
    )

    key_path.write_bytes(
        key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.TraditionalOpenSSL,
            encryption_algorithm=serialization.NoEncryption(),
        )
    )
    key_path.chmod(0o600)

    cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))

    fingerprint = _get_cert_fingerprint(cert_path)
    logger.info("TLS certificate generated (fingerprint: %s)", fingerprint)

    return cert_path, key_path


def create_ssl_context(
    cert_path: Path,
    key_path: Path,
) -> ssl.SSLContext:
    """Create an SSL context for the aiohttp server.

    Configures TLS 1.2+ with the daemon's self-signed certificate.

    Args:
        cert_path: Path to the PEM certificate file.
        key_path: Path to the PEM private key file.

    Returns:
        A configured ssl.SSLContext ready for aiohttp.
    """
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain(certfile=str(cert_path), keyfile=str(key_path))
    return ctx
