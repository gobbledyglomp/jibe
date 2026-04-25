"""Tests for the authentication module.

Covers both pairing paths (new device via PIN, trusted device via
fingerprint), pairing session lifecycle, rate limiting, and all
rejection scenarios.

The AuthManager depends on JibeDatabase — the shared `db` fixture
from conftest.py provides a fresh in-memory-backed database per test.
"""

import pytest
from jibe.core.api import AuthError, MessageType
from jibe.core.auth import PairingSession, _generate_fingerprint, _generate_pin
from jibe.core.config import MAX_PIN_ATTEMPTS, PIN_EXPIRY_SECONDS, PIN_LENGTH

# ── PIN generation ────────────────────────────────────────────────────────


def test_generate_pin_length():
    """PIN must be exactly PIN_LENGTH digits."""
    pin = _generate_pin()
    assert len(pin) == PIN_LENGTH
    assert pin.isdigit()


def test_generate_pin_zero_padded():
    """PINs shorter than PIN_LENGTH must be zero-padded."""
    pins = [_generate_pin() for _ in range(200)]
    assert all(len(p) == PIN_LENGTH for p in pins)


def test_generate_pin_randomness():
    """Two generated PINs should not be the same (almost certainly)."""
    pins = {_generate_pin() for _ in range(50)}
    assert len(pins) > 1


# ── Fingerprint generation ────────────────────────────────────────────────


def test_generate_fingerprint_format():
    """Fingerprint must be a 64-character hex string (SHA-256)."""
    fp = _generate_fingerprint("Pixel 8")
    assert len(fp) == 64
    assert all(c in "0123456789abcdef" for c in fp)


def test_generate_fingerprint_unique():
    """Two fingerprints for the same device name must differ (salt ensures this)."""
    fp1 = _generate_fingerprint("Pixel 8")
    fp2 = _generate_fingerprint("Pixel 8")
    assert fp1 != fp2


# ── PairingSession lifecycle ──────────────────────────────────────────────


def test_pairing_session_is_valid_on_creation():
    """A freshly created session must be valid."""
    session = PairingSession()
    assert session.is_valid()
    assert not session.is_expired()
    assert not session.used


def test_pairing_session_invalid_when_used():
    """A session marked as used must not be valid."""
    session = PairingSession()
    session.used = True
    assert not session.is_valid()


def test_pairing_session_invalid_when_expired(monkeypatch):
    """A session must be invalid after its time-to-live."""
    session = PairingSession()
    monkeypatch.setattr(
        "jibe.core.auth.time.time",
        lambda: session.created_at + PIN_EXPIRY_SECONDS + 1,
    )
    assert session.is_expired()
    assert not session.is_valid()


# ── AuthManager: pairing mode ─────────────────────────────────────────────


async def test_start_pairing_returns_pin(auth):
    """start_pairing() must return a valid 6-digit PIN string."""
    pin = auth.start_pairing()
    assert len(pin) == PIN_LENGTH
    assert pin.isdigit()


async def test_start_pairing_activates_mode(auth):
    """is_pairing_active must be True after start_pairing()."""
    assert not auth.is_pairing_active
    auth.start_pairing()
    assert auth.is_pairing_active


async def test_stop_pairing_deactivates_mode(auth):
    """is_pairing_active must be False after stop_pairing()."""
    auth.start_pairing()
    auth.stop_pairing()
    assert not auth.is_pairing_active


async def test_stop_pairing_is_idempotent(auth):
    """stop_pairing() when not in pairing mode must not raise."""
    auth.stop_pairing()


async def test_start_pairing_replaces_existing_session(auth):
    """Calling start_pairing() twice must generate a fresh PIN each time."""
    pin1 = auth.start_pairing()
    pin2 = auth.start_pairing()
    assert pin1 != pin2
    assert auth.is_pairing_active
    assert auth._pairing_session is not None


async def test_pairing_mode_expires(auth, monkeypatch):
    """is_pairing_active must return False after PIN_EXPIRY_SECONDS."""
    auth.start_pairing()
    created_at = auth._pairing_session.created_at

    monkeypatch.setattr(
        "jibe.core.auth.time.time",
        lambda: created_at + PIN_EXPIRY_SECONDS + 1,
    )
    assert not auth.is_pairing_active
    assert auth._pairing_session is None


# ── AuthManager: new device pairing ──────────────────────────────────────


async def test_correct_pin_accepted(auth_pairing):
    """Correct PIN must accept the device and persist it in the DB."""
    pin = auth_pairing._pairing_session.pin
    payload = {"device_name": "Pixel 8", "pin": pin}

    response = await auth_pairing.handle_auth_request(payload, "client-1")

    assert response["type"] == MessageType.AUTH_RESPONSE.value
    assert response["accepted"] is True
    assert response["reason"] == ""
    assert "device_id" in response
    assert "fingerprint" in response


async def test_correct_pin_stores_device_in_db(auth_pairing, db):
    """After a successful pairing, the device must be in the database."""
    pin = auth_pairing._pairing_session.pin
    payload = {"device_name": "Pixel 8", "pin": pin}

    response = await auth_pairing.handle_auth_request(payload, "client-1")

    device = await db.get_device_by_id(response["device_id"])
    assert device is not None
    assert device["name"] == "Pixel 8"
    assert device["fingerprint"] == response["fingerprint"]


async def test_correct_pin_marks_session_as_used(auth_pairing):
    """After a successful pairing, the PIN session must be marked used."""
    pin = auth_pairing._pairing_session.pin
    await auth_pairing.handle_auth_request(
        {"device_name": "Pixel 8", "pin": pin}, "client-1"
    )
    assert auth_pairing._pairing_session.used is True


async def test_pin_cannot_be_reused(auth_pairing):
    """A consumed PIN must not allow a second device to pair."""
    pin = auth_pairing._pairing_session.pin
    await auth_pairing.handle_auth_request(
        {"device_name": "Pixel 8", "pin": pin}, "client-1"
    )

    response = await auth_pairing.handle_auth_request(
        {"device_name": "Other Phone", "pin": pin}, "client-2"
    )
    assert response["accepted"] is False


# ── AuthManager: rejection scenarios ─────────────────────────────────────


async def test_wrong_pin_rejected(auth_pairing):
    """Wrong PIN must return accepted=False."""
    payload = {"device_name": "Pixel 8", "pin": "000000"}

    response = await auth_pairing.handle_auth_request(payload, "client-1")

    assert response["accepted"] is False
    assert "Invalid PIN" in response["reason"]


async def test_missing_pin_rejected(auth_pairing):
    """Missing pin field must return accepted=False."""
    payload = {"device_name": "Pixel 8"}

    response = await auth_pairing.handle_auth_request(payload, "client-1")

    assert response["accepted"] is False


async def test_no_pairing_mode_rejected(auth):
    """auth.request when pairing mode is inactive must be rejected."""
    payload = {"device_name": "Pixel 8", "pin": "123456"}

    response = await auth.handle_auth_request(payload, "client-1")

    assert response["accepted"] is False
    assert "not active" in response["reason"]


async def test_wrong_pin_does_not_store_device(auth_pairing, db):
    """A rejected auth attempt must not create a device record."""
    payload = {"device_name": "Attacker", "pin": "000000"}
    await auth_pairing.handle_auth_request(payload, "client-1")

    devices = await db.list_devices()
    assert devices == []


# ── AuthManager: rate limiting ────────────────────────────────────────────


async def test_rate_limit_triggered_after_max_attempts(auth_pairing):
    """After MAX_PIN_ATTEMPTS failures, the next attempt must raise AuthError."""
    payload = {"device_name": "Attacker", "pin": "000000"}

    for _ in range(MAX_PIN_ATTEMPTS):
        await auth_pairing.handle_auth_request(payload, "client-1")

    with pytest.raises(AuthError):
        await auth_pairing.handle_auth_request(payload, "client-1")


async def test_rate_limit_is_per_client(auth_pairing):
    """Rate limiting must be tracked per client_id, not globally."""
    bad_payload = {"device_name": "Attacker", "pin": "000000"}

    for _ in range(MAX_PIN_ATTEMPTS):
        await auth_pairing.handle_auth_request(bad_payload, "client-1")

    pin = auth_pairing._pairing_session.pin
    response = await auth_pairing.handle_auth_request(
        {"device_name": "Legit Phone", "pin": pin}, "client-2"
    )
    assert response["accepted"] is True


async def test_successful_auth_clears_failed_attempts(auth_pairing):
    """A successful auth must reset the failed attempts counter."""
    await auth_pairing.handle_auth_request(
        {"device_name": "Phone", "pin": "000000"}, "client-1"
    )
    assert auth_pairing._failed_attempts.get("client-1", 0) == 1

    pin = auth_pairing._pairing_session.pin
    await auth_pairing.handle_auth_request(
        {"device_name": "Phone", "pin": pin}, "client-1"
    )
    assert "client-1" not in auth_pairing._failed_attempts


# ── AuthManager: trusted device reconnection ──────────────────────────────


async def test_trusted_device_reconnects_without_pin(auth_pairing, db):
    """A previously paired device must reconnect via fingerprint, no PIN."""
    pin = auth_pairing._pairing_session.pin
    response = await auth_pairing.handle_auth_request(
        {"device_name": "Pixel 8", "pin": pin}, "client-1"
    )
    fingerprint = response["fingerprint"]

    auth_pairing.stop_pairing()
    assert not auth_pairing.is_pairing_active

    reconnect = await auth_pairing.handle_auth_request(
        {"device_name": "Pixel 8", "fingerprint": fingerprint}, "client-1"
    )
    assert reconnect["accepted"] is True


async def test_unknown_fingerprint_falls_through_to_pin(auth_pairing):
    """An unrecognised fingerprint must not grant access — falls to PIN check."""
    response = await auth_pairing.handle_auth_request(
        {"device_name": "Stranger", "fingerprint": "a" * 64}, "client-1"
    )
    assert response["accepted"] is False


async def test_trusted_reconnect_updates_last_seen(auth_pairing, db):
    """Reconnecting a trusted device must update its last_seen timestamp."""
    pin = auth_pairing._pairing_session.pin
    pair_response = await auth_pairing.handle_auth_request(
        {"device_name": "Pixel 8", "pin": pin}, "client-1"
    )
    fingerprint = pair_response["fingerprint"]
    device_id = pair_response["device_id"]

    original_last_seen = (await db.get_device_by_id(device_id))["last_seen"]

    auth_pairing.stop_pairing()
    await auth_pairing.handle_auth_request(
        {"device_name": "Pixel 8", "fingerprint": fingerprint}, "client-1"
    )

    updated_last_seen = (await db.get_device_by_id(device_id))["last_seen"]
    assert updated_last_seen >= original_last_seen
