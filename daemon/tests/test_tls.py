"""Unit tests for TLS certificate generation and management."""

import ssl

from cryptography import x509
from cryptography.x509.oid import NameOID
from jibe.tls import (
    _get_cert_fingerprint,
    create_ssl_context,
    generate_self_signed_cert,
)


def test_generate_self_signed_cert_creates_files(certs_dir):
    """It should create a certificate and private key."""
    cert_path, key_path = generate_self_signed_cert(
        certs_dir=certs_dir,
        cert_name="test.crt",
        key_name="test.key",
    )

    assert cert_path.exists()
    assert key_path.exists()
    assert cert_path.name == "test.crt"
    assert key_path.name == "test.key"


def test_generate_self_signed_cert_is_idempotent(certs_dir):
    """It should not overwrite existing certificates."""
    cert_path, key_path = generate_self_signed_cert(
        certs_dir=certs_dir,
    )

    cert_mtime = cert_path.stat().st_mtime
    key_mtime = key_path.stat().st_mtime

    cert_path2, key_path2 = generate_self_signed_cert(
        certs_dir=certs_dir,
    )

    assert cert_path == cert_path2
    assert key_path == key_path2
    assert cert_path.stat().st_mtime == cert_mtime
    assert key_path.stat().st_mtime == key_mtime


def test_certificate_properties(certs_dir):
    """The generated certificate should have correct properties."""
    cert_path, _ = generate_self_signed_cert(certs_dir=certs_dir)

    cert_pem = cert_path.read_bytes()
    cert = x509.load_pem_x509_certificate(cert_pem)

    subjects = cert.subject.get_attributes_for_oid(NameOID.COMMON_NAME)
    assert subjects[0].value == "Jibe Daemon"

    issuers = cert.issuer.get_attributes_for_oid(NameOID.COMMON_NAME)
    assert issuers[0].value == "Jibe Daemon"

    delta = cert.not_valid_after_utc - cert.not_valid_before_utc
    assert delta.days == 3650


def test_get_cert_fingerprint(certs_dir):
    """Fingerprint should be a valid SHA-256 hex string."""
    cert_path, _ = generate_self_signed_cert(certs_dir=certs_dir)
    fingerprint = _get_cert_fingerprint(cert_path)

    assert len(fingerprint) == 95
    assert ":" in fingerprint
    assert all(c in "0123456789ABCDEF:" for c in fingerprint)


def test_create_ssl_context(certs_dir):
    """It should create a valid SSL context loaded with the certs."""
    cert_path, key_path = generate_self_signed_cert(certs_dir=certs_dir)
    ctx = create_ssl_context(cert_path, key_path)

    assert isinstance(ctx, ssl.SSLContext)
    assert ctx.protocol == ssl.PROTOCOL_TLS_SERVER
    assert ctx.minimum_version == ssl.TLSVersion.TLSv1_2
