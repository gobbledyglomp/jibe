"""Connection state machine for the Jibe daemon.

Every WebSocket connection gets wrapped in a `JibeConnection` object
that tracks its authentication state. This is the architectural heart
of the daemon — every future feature (clipboard, notifications, files)
will simply register a handler and get auth for free.

State machine:

    AWAITING_AUTH → AUTHENTICATED → DISCONNECTED
         │                               ↑
         └──────── (on close) ───────────┘

Rules:
  - The very first message on a new connection MUST be `auth.request`.
    Any other message type before authentication gets an
    `auth_required` error response.
  - After successful authentication, all valid message types are
    accepted and routed to their handlers.
  - On disconnect, the connection is cleaned up from the registry.

The `ConnectionRegistry` tracks all active connections, keyed by
device ID. This enables:
  - "Send clipboard to all connected devices"
  - Web UI's "connected devices" list
"""

import logging
import secrets
from enum import Enum

from aiohttp import web

logger = logging.getLogger(__name__)


class ConnectionState(str, Enum):
    """Possible states for a WebSocket connection.

    The `str` mixin allows clean logging and serialisation
    (e.g. `state.value` → "awaiting_auth").
    """

    AWAITING_AUTH = "awaiting_auth"
    AUTHENTICATED = "authenticated"
    DISCONNECTED = "disconnected"


class JibeConnection:
    """Wraps a WebSocket with authentication state and device identity.

    Each incoming WebSocket connection is represented by one instance.
    The connection starts in AWAITING_AUTH and transitions to
    AUTHENTICATED once a valid `auth.request` is processed.

    Attributes:
        id: A unique identifier for this connection (not the device ID).
        ws: The underlying aiohttp WebSocket response object.
        state: The current connection state.
        device_id: The paired device's ID (set after auth).
        device_name: Human-readable name (set after auth).
        client_ip: Remote IP address, used for logging and rate limiting.
    """

    def __init__(self, ws: web.WebSocketResponse, client_ip: str) -> None:
        self.id: str = secrets.token_hex(8)
        self.ws: web.WebSocketResponse = ws
        self.state: ConnectionState = ConnectionState.AWAITING_AUTH
        self.device_id: str | None = None
        self.device_name: str | None = None
        self.client_ip: str = client_ip

    @property
    def is_authenticated(self) -> bool:
        """Whether this connection has completed the auth handshake."""
        return self.state == ConnectionState.AUTHENTICATED

    async def send(self, data: str) -> None:
        """Send a JSON string to the connected client.

        Wraps `ws.send_str()` so callers don't need to touch the raw
        WebSocket object directly.
        """
        if not self.ws.closed:
            await self.ws.send_str(data)

    async def close(self) -> None:
        """Close the WebSocket connection and mark state as disconnected."""
        self.state = ConnectionState.DISCONNECTED
        if not self.ws.closed:
            await self.ws.close()

    def __repr__(self) -> str:
        device = self.device_name or "unknown"
        return f"<JibeConnection {self.id} [{self.state.value}] device={device}>"


class ConnectionRegistry:
    """Tracks all active WebSocket connections.

    Provides lookup by connection ID and by device ID, plus
    broadcast capabilities for features like clipboard sync.
    """

    def __init__(self) -> None:
        self._connections: dict[str, JibeConnection] = {}

    def add(self, conn: JibeConnection) -> None:
        """Register a new connection."""
        self._connections[conn.id] = conn
        logger.debug("Connection registered: %s", conn)

    def remove(self, conn: JibeConnection) -> None:
        """Unregister a connection (e.g. on disconnect)."""
        self._connections.pop(conn.id, None)
        logger.debug("Connection removed: %s", conn)

    def get_by_device_id(self, device_id: str) -> JibeConnection | None:
        """Find an active connection for a specific device."""
        for conn in self._connections.values():
            if conn.device_id == device_id:
                return conn
        return None

    def get_authenticated(self) -> list[JibeConnection]:
        """Return all currently authenticated connections."""
        return [conn for conn in self._connections.values() if conn.is_authenticated]

    @property
    def count(self) -> int:
        """Total number of active connections (including unauthenticated)."""
        return len(self._connections)

    @property
    def authenticated_count(self) -> int:
        """Number of currently authenticated connections."""
        return len(self.get_authenticated())
