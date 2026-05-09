"""Device authentication for the Jibe daemon.

Handles two flows:
1. First-time pairing via a short-lived PIN (pairing mode).
2. Trusted reconnection via a stored device fingerprint.
"""

import hashlib
import logging
import secrets
import time

from jibe.core.api import AuthError, MessageType
from jibe.core.config import MAX_PIN_ATTEMPTS, PIN_EXPIRY_SECONDS, PIN_LENGTH
from jibe.core.db import JibeDatabase

logger = logging.getLogger(__name__)


def _generate_pin() -> str:
    """Generate a cryptographically secure random PIN.

    Returns:
        A zero-padded numeric string of PIN_LENGTH digits
        (e.g. "048291" — leading zeros preserved).
    """
    return str(secrets.randbelow(10**PIN_LENGTH)).zfill(PIN_LENGTH)


def _generate_fingerprint(device_name: str) -> str:
    """Generate a unique device fingerprint.

    The fingerprint is a SHA-256 hash of the device name, current
    timestamp, and a random salt. The salt ensures two devices with
    the same name get different fingerprints.

    Args:
        device_name: The human-readable device name from auth.request.

    Returns:
        A 64-character lowercase hex string (SHA-256 digest).
    """
    salt = secrets.token_hex(16)
    raw = f"{device_name}:{time.time()}:{salt}"
    return hashlib.sha256(raw.encode()).hexdigest()


class PairingSession:
    """Represents an active pairing window.

    Created when the user enters pairing mode, destroyed when pairing
    completes, is cancelled, or times out. Only one PairingSession
    can be active at a time — starting a new one replaces the old one.

    Attributes:
        pin: The 6-digit PIN the connecting device must provide.
        created_at: Unix timestamp when this session was created.
        used: Whether this PIN has already been used to pair a device.
    """

    def __init__(self) -> None:
        self.pin: str = _generate_pin()
        self.created_at: float = time.time()
        self.used: bool = False

    def is_expired(self) -> bool:
        """Check whether the PIN has exceeded its time-to-live."""
        return (time.time() - self.created_at) >= PIN_EXPIRY_SECONDS

    def is_valid(self) -> bool:
        """Check whether this pairing session can still accept a PIN.

        A session is valid only if it hasn't expired and hasn't already
        been consumed by a successful pairing.
        """
        return not self.is_expired() and not self.used


class AuthManager:
    """Manages device authentication and pairing for the daemon.

    This class is the single authority on "is this device allowed to
    talk to us?" It handles both new-device pairing (via PIN) and
    trusted-device reconnection (via fingerprint).

    The manager is stateful: it holds the active PairingSession (if any)
    and tracks failed PIN attempts per connection to enforce rate limiting.

    Usage:
        auth = AuthManager(db)
        pin = auth.start_pairing()  # prints PIN to logs
        result = await auth.handle_auth_request(payload)
    """

    def __init__(self, db: JibeDatabase) -> None:
        """Initialise the auth manager.

        Args:
            db: The database instance for persisting trusted devices.
        """
        self._db = db
        self._pairing_session: PairingSession | None = None
        self._failed_attempts: dict[str, int] = {}

    def start_pairing(self) -> str:
        """Enter pairing mode by generating a new PIN.

        Any existing pairing session is replaced. The PIN is logged
        and returned so callers (tray, web UI) can display it.

        Also resets the per-client failure counters so the new session
        starts with a clean slate of MAX_PIN_ATTEMPTS.

        Returns:
            The generated 6-digit PIN string.
        """
        self._pairing_session = PairingSession()
        self._failed_attempts.clear()
        logger.info(
            "Pairing mode active — PIN: %s (expires in %ds)",
            self._pairing_session.pin,
            PIN_EXPIRY_SECONDS,
        )
        return self._pairing_session.pin

    def stop_pairing(self) -> None:
        """Exit pairing mode, discarding any active PIN."""
        if self._pairing_session:
            self._pairing_session = None
            self._failed_attempts.clear()
            logger.debug("Pairing mode deactivated")

    @property
    def is_pairing_active(self) -> bool:
        """Whether a valid (non-expired) pairing session exists."""
        if self._pairing_session is None:
            return False
        if self._pairing_session.is_expired():
            logger.debug("Pairing PIN expired, deactivating pairing mode")
            self._pairing_session = None
            self._failed_attempts.clear()
            return False
        return self._pairing_session.is_valid()

    async def handle_auth_request(self, payload: dict, client_id: str) -> dict:
        """Process an incoming `auth.request` message.

        Returns a JSON `auth.response` string. Handles three paths:
          1. Trusted device reconnection (fingerprint in DB)
          2. New device pairing (valid PIN)
          3. Rejection (wrong PIN, no pairing mode, rate limited)

        Args:
            payload: The parsed message dict. Expected fields:
                     device_name, and either pin or fingerprint.
            client_id: Identifier for rate limiting (e.g. remote IP).

        Returns:
            A JSON string containing the `auth.response` message.

        Raises:
            AuthError: If the client has exceeded the rate limit.
        """
        device_name = payload.get("device_name", "Unknown device")
        fingerprint = payload.get("fingerprint")
        pin = payload.get("pin")
        if fingerprint:
            device = await self._db.get_device_by_fingerprint(fingerprint)
            if device:
                await self._db.update_last_seen(device["id"])
                logger.info(
                    "Trusted device reconnected: %s (%s)",
                    device["name"],
                    device["id"],
                )
                return self._accept_response(device["id"], device["fingerprint"])

        if not self.is_pairing_active:
            if not fingerprint:
                self.start_pairing()
                return self._reject_response(
                    "Pairing started — check daemon terminal for PIN."
                )
            self._record_failure(client_id)
            return self._reject_response(
                "Pairing mode is not active. Start pairing on the daemon first."
            )

        if not pin:
            # Probe without PIN after reconnect — fresh pairing UX (e.g. Android Retry).
            self._failed_attempts.pop(client_id, None)
            return self._reject_response("Enter the PIN shown on the daemon.")

        attempts = self._failed_attempts.get(client_id, 0)
        if attempts >= MAX_PIN_ATTEMPTS:
            logger.warning(
                "Rate limit exceeded for %s (%d attempts)",
                client_id,
                attempts,
            )
            raise AuthError(
                f"Too many failed attempts ({MAX_PIN_ATTEMPTS}). "
                "Connection will be closed."
            )

        if pin != self._pairing_session.pin:
            self._record_failure(client_id)
            remaining = MAX_PIN_ATTEMPTS - self._failed_attempts[client_id]
            return self._reject_response(
                f"Invalid PIN. {remaining} attempt(s) remaining."
            )
        new_fingerprint = _generate_fingerprint(device_name)
        device_id = secrets.token_hex(16)

        device = await self._db.add_device(
            device_id=device_id,
            name=device_name,
            fingerprint=new_fingerprint,
        )

        self._pairing_session.used = True
        logger.info("New device paired: %s (%s)", device_name, device_id)

        self._failed_attempts.pop(client_id, None)

        return self._accept_response(device_id, new_fingerprint)

    def _accept_response(self, device_id: str, fingerprint: str) -> dict:
        """Build a successful auth.response JSON string.

        Includes the device_id and fingerprint so the Android app can
        store them for automatic reconnection in the future.
        """
        return {
            "type": MessageType.AUTH_RESPONSE.value,
            "accepted": True,
            "reason": "",
            "device_id": device_id,
            "fingerprint": fingerprint,
        }

    def _reject_response(self, reason: str) -> dict:
        """Build a failed auth.response JSON string."""
        return {
            "type": MessageType.AUTH_RESPONSE.value,
            "accepted": False,
            "reason": reason,
        }

    def _record_failure(self, client_id: str) -> None:
        """Increment the failed attempt counter for a client."""
        self._failed_attempts[client_id] = self._failed_attempts.get(client_id, 0) + 1
        logger.warning(
            "Failed auth attempt from %s (%d/%d)",
            client_id,
            self._failed_attempts[client_id],
            MAX_PIN_ATTEMPTS,
        )
