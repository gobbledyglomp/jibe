"""Network server for the Jibe daemon.

Provides a WebSocket endpoint at `/ws` for JSON protocol messages and an HTTP
health/status endpoint for local debugging.
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
    JibeMessage,
    MessageType,
    format_error,
    parse_message,
)
from jibe.core.auth import AuthManager
from jibe.core.config import (
    AUTH_TIMEOUT_SECONDS,
    DEFAULT_PORT,
    WS_HEARTBEAT_SECONDS,
    WS_MAX_MESSAGE_BYTES,
)
from jibe.core.db import JibeDatabase
from jibe.handlers.clipboard import ClipboardMonitor, handle_clipboard_sync
from jibe.handlers.notifications import handle_notification
from jibe.handlers.ping import handle_ping
from jibe.handlers.router import MessageRouter
from jibe.handlers.transfer import (
    abort_transfers_for_connection,
    handle_file_chunk_binary,
    handle_file_done,
    handle_file_start,
)
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
        self._clipboard_monitor = ClipboardMonitor(self._registry)
        self._router = MessageRouter()
        self._register_handlers()
        self._app = web.Application()
        self._setup_routes()
        self._runner: web.AppRunner | None = None
        self._site: web.TCPSite | None = None
        self._clipboard_monitor_task: asyncio.Task[None] | None = None

    @property
    def auth(self) -> AuthManager:
        """Expose the auth manager for CLI commands (e.g. start pairing)."""
        return self._auth

    @property
    def registry(self) -> ConnectionRegistry:
        """Expose the registry for status queries."""
        return self._registry

    def _register_handlers(self) -> None:
        """Register message handlers with the router."""
        monitor = self._clipboard_monitor

        async def handle_clipboard(conn: JibeConnection, msg: JibeMessage) -> None:
            await handle_clipboard_sync(conn, msg, monitor)

        self._router.register(MessageType.PING, handle_ping)
        self._router.register(MessageType.CLIPBOARD_SYNC, handle_clipboard)
        self._router.register(MessageType.FILE_START, handle_file_start)
        self._router.register(MessageType.FILE_DONE, handle_file_done)
        self._router.register(MessageType.NOTIFICATION, handle_notification)

    def _setup_routes(self) -> None:
        """Configure the HTTP, WebSocket, and REST API routes."""
        self._app.router.add_get("/", self.handle_health)
        self._app.router.add_get("/ws", self.handle_websocket)
        self._app.router.add_get("/api/status", self.handle_api_status)
        self._app.router.add_get("/api/devices", self.handle_api_devices)
        self._app.router.add_delete(
            "/api/devices/{device_id}", self.handle_api_delete_device
        )

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
        ws = web.WebSocketResponse(
            heartbeat=WS_HEARTBEAT_SECONDS,
            max_msg_size=WS_MAX_MESSAGE_BYTES,
            compress=False,
        )
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
        conn._auth_timeout_task = timeout_task

        try:
            async for msg in ws:
                if msg.type == web.WSMsgType.TEXT:
                    await self._handle_text_message(conn, msg.data)

                elif msg.type == web.WSMsgType.BINARY:
                    await self._handle_binary_message(conn, msg.data)

                elif msg.type == web.WSMsgType.ERROR:
                    logger.error("WebSocket error on %s: %s", conn.id, ws.exception())
        except asyncio.CancelledError:
            pass
        finally:
            timeout_task.cancel()
            self._registry.remove(conn)
            abort_transfers_for_connection(conn.id)
            if conn.is_authenticated and conn.device_id:
                try:
                    await self._db.end_session(conn.id)
                except Exception:
                    logger.exception("Failed to end session %s", conn.id)
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
                result = await self._auth.handle_auth_request(
                    jibe_msg.payload, conn.client_ip
                )
            except AuthError as e:
                await conn.send(format_error("auth_rejected", str(e)))
                await conn.close()
                return

            if result.get("accepted"):
                conn.state = ConnectionState.AUTHENTICATED
                conn.device_id = result["device_id"]
                conn.device_name = result.get(
                    "device_name", jibe_msg.payload.get("device_name")
                )
                await self._db.start_session(conn.id, conn.device_id)
                if hasattr(conn, "_auth_timeout_task"):
                    conn._auth_timeout_task.cancel()

            await conn.send(json.dumps(result))
            return

        logger.debug(
            "[%s] %s message from %s",
            conn.device_name,
            jibe_msg.type.value,
            conn.id,
        )

        await self._router.dispatch(conn, jibe_msg)

    async def _handle_binary_message(self, conn: JibeConnection, data: bytes) -> None:
        """Authenticated-only binary payloads — currently file chunk frames only."""
        if not conn.is_authenticated:
            await conn.send(
                format_error(
                    "auth_required",
                    "You must authenticate before sending binary frames.",
                )
            )
            return

        logger.debug(
            "[%s] binary frame (%d bytes) from %s",
            conn.device_name,
            len(data),
            conn.id,
        )
        await handle_file_chunk_binary(conn, data)

    @staticmethod
    def _is_localhost(request: web.Request) -> bool:
        """Check if the request originates from localhost."""
        remote = request.remote or ""
        return remote in ("127.0.0.1", "::1", "localhost")

    async def handle_api_status(self, request: web.Request) -> web.Response:
        """GET /api/status — daemon status overview."""
        if not self._is_localhost(request):
            raise web.HTTPForbidden(text="REST API is localhost-only")

        return web.json_response(
            {
                "status": "running",
                "version": __version__,
                "connected_devices": self._registry.authenticated_count,
                "total_connections": self._registry.count,
            }
        )

    async def handle_api_devices(self, request: web.Request) -> web.Response:
        """GET /api/devices — list all paired devices."""
        if not self._is_localhost(request):
            raise web.HTTPForbidden(text="REST API is localhost-only")

        devices = await self._db.list_devices()
        return web.json_response({"devices": devices})

    async def handle_api_delete_device(self, request: web.Request) -> web.Response:
        """DELETE /api/devices/{device_id} — unpair a device."""
        if not self._is_localhost(request):
            raise web.HTTPForbidden(text="REST API is localhost-only")

        device_id = request.match_info["device_id"]
        removed = await self._db.remove_device(device_id)

        if not removed:
            raise web.HTTPNotFound(
                text=json.dumps({"error": f"Device '{device_id}' not found"}),
                content_type="application/json",
            )

        return web.json_response({"removed": device_id})

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

        self._clipboard_monitor_task = asyncio.create_task(self._clipboard_monitor.run())

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
        if self._clipboard_monitor_task is not None:
            self._clipboard_monitor_task.cancel()
            try:
                await self._clipboard_monitor_task
            except asyncio.CancelledError:
                pass
            self._clipboard_monitor_task = None
        await self._registry.close_all()
        if self._runner:
            await self._runner.cleanup()
            self._runner = None
            self._site = None
        logger.info("Server stopped")
