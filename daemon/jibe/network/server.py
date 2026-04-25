"""WebSocket and HTTP server for the Jibe daemon.

This module runs the main network-facing server using aiohttp. It serves
two purposes:

1. **WebSocket endpoint** (`/ws`) — The primary communication channel.
   Android clients connect here and exchange JSON messages following the
   protocol defined in `docs/protocol.md`.

2. **HTTP health endpoint** (`/`) — A simple GET endpoint that returns
   the daemon's status and version as JSON, useful for debugging and
   monitoring.

Why aiohttp?
  aiohttp is a mature async HTTP framework that natively supports WebSockets.
  Unlike FastAPI (which adds REST-specific abstractions we don't need) or
  raw `websockets` (which lacks HTTP routing), aiohttp gives us both
  WebSocket and HTTP handling in a single, lightweight server.
"""

import asyncio
import json
import logging
import ssl

from aiohttp import web

from jibe import __version__
from jibe.core.api import (
    AuthError,
    InvalidMessageError,
    MessageType,
    format_error,
    parse_message,
)
from jibe.core.auth import AuthManager
from jibe.core.config import AUTH_TIMEOUT_SECONDS, DEFAULT_PORT, WS_HEARTBEAT_SECONDS
from jibe.core.db import JibeDatabase
from jibe.network.connection import ConnectionRegistry, ConnectionState, JibeConnection

logger = logging.getLogger(__name__)


class JibeServer:
    """Manages the aiohttp application lifecycle.

    Provides `start()` and `stop()` async methods so the server can be
    run alongside other async services (like mDNS discovery) using
    `asyncio.gather()`.

    The server owns the database, auth manager, and connection registry.
    These are injected via the constructor so tests can provide mocks or
    in-memory implementations.
    """

    def __init__(
        self,
        db: JibeDatabase,
        port: int = DEFAULT_PORT,
        ssl_context: ssl.SSLContext | None = None,
    ) -> None:
        """Initialise the server.

        Args:
            db: The database instance for persistence.
            port: The TCP port to listen on.
            ssl_context: If provided, serve wss:// instead of ws://.
        """
        self._port = port
        self._db = db
        self._ssl_context = ssl_context
        self._auth = AuthManager(db)
        self._registry = ConnectionRegistry()
        self._app = web.Application()
        self._setup_routes()
        self._runner: web.AppRunner | None = None
        self._site: web.TCPSite | None = None

    @property
    def auth(self) -> AuthManager:
        """Expose the auth manager for CLI commands (e.g. start pairing)."""
        return self._auth

    @property
    def registry(self) -> ConnectionRegistry:
        """Expose the registry for status queries."""
        return self._registry

    def _setup_routes(self) -> None:
        """Configure the HTTP and WebSocket routes."""
        self._app.router.add_get("/", self.handle_health)
        self._app.router.add_get("/ws", self.handle_websocket)

    async def handle_health(self, request: web.Request) -> web.Response:
        """Handle GET requests to the root path.

        Returns a simple JSON payload confirming the daemon is running.
        """
        return web.json_response(
            {
                "status": "running",
                "version": __version__,
                "connected_devices": self._registry.authenticated_count,
            }
        )

    async def handle_websocket(self, request: web.Request) -> web.WebSocketResponse:
        """Handle incoming WebSocket connections.

        Upgrades the HTTP connection to a WebSocket, wraps it in a
        JibeConnection, and processes messages through the state machine:

        1. AWAITING_AUTH: only `auth.request` is accepted
        2. AUTHENTICATED: all valid message types are routed
        """
        ws = web.WebSocketResponse(heartbeat=WS_HEARTBEAT_SECONDS)
        await ws.prepare(request)

        client_ip = request.remote or "unknown"
        conn = JibeConnection(ws=ws, client_ip=client_ip)
        self._registry.add(conn)

        logger.info("WebSocket connected: %s from %s", conn.id, client_ip)

        async def auth_timeout():
            await asyncio.sleep(AUTH_TIMEOUT_SECONDS)
            if not conn.is_authenticated:
                logger.warning("Connection %s timed out waiting for auth", conn.id)
                await conn.close()

        timeout_task = asyncio.create_task(auth_timeout())

        try:
            async for msg in ws:
                if msg.type == web.WSMsgType.TEXT:
                    await self._handle_text_message(conn, msg.data)

                elif msg.type == web.WSMsgType.ERROR:
                    logger.error("WebSocket error on %s: %s", conn.id, ws.exception())
        except asyncio.CancelledError:
            pass
        finally:
            timeout_task.cancel()
            self._registry.remove(conn)
            if conn.is_authenticated and conn.device_id:
                asyncio.create_task(self._db.end_session(conn.id))
            conn.state = ConnectionState.DISCONNECTED
            logger.info(
                "WebSocket disconnected: %s (%s)",
                conn.id,
                conn.device_name or "unauthenticated",
            )

        return ws

    async def _handle_text_message(self, conn: JibeConnection, raw: str) -> None:
        """Parse, validate, and route a single text message.

        Enforces the state machine: unauthenticated connections may
        only send `auth.request`. Everything else gets an error.
        """
        try:
            jibe_msg = parse_message(raw)
        except InvalidMessageError as e:
            logger.warning("Invalid message from %s: %s", conn.id, e)
            await conn.send(format_error(e.code, str(e)))
            return

        # ── State: AWAITING_AUTH ─────────────────────────────────────
        if not conn.is_authenticated:
            if jibe_msg.type != MessageType.AUTH_REQUEST:
                await conn.send(
                    format_error(
                        "auth_required",
                        "You must authenticate before sending other messages.",
                    )
                )
                return

            try:
                response = await self._auth.handle_auth_request(
                    jibe_msg.payload, conn.client_ip
                )
            except AuthError as e:
                await conn.send(format_error("auth_rejected", str(e)))
                await conn.close()
                return

            result = json.loads(response)
            if result.get("accepted"):
                conn.state = ConnectionState.AUTHENTICATED
                conn.device_id = result["device_id"]
                conn.device_name = result.get(
                    "device_name", jibe_msg.payload.get("device_name")
                )
                await self._db.start_session(conn.id, conn.device_id)

            await conn.send(response)
            return

        # ── State: AUTHENTICATED ─────────────────────────────────────
        logger.info(
            "[%s] %s message from %s",
            conn.device_name,
            jibe_msg.type.value,
            conn.id,
        )
        logger.debug("Payload: %s", jibe_msg.payload)

        # TODO: Route to specific handlers via message router (feat/message-router)

    async def start(self) -> None:
        """Start listening for incoming connections."""
        self._runner = web.AppRunner(self._app)
        await self._runner.setup()

        self._site = web.TCPSite(
            self._runner,
            "0.0.0.0",
            self._port,
            ssl_context=self._ssl_context,
        )
        await self._site.start()

        protocol = "wss" if self._ssl_context else "ws"
        logger.info(
            "%s server listening on %s://0.0.0.0:%d/ws",
            protocol.upper(),
            protocol,
            self._port,
        )

    async def stop(self) -> None:
        """Stop listening and cleanly disconnect all clients."""
        logger.info("Stopping server...")
        if self._runner:
            await self._runner.cleanup()
            self._runner = None
            self._site = None
        logger.info("Server stopped")
