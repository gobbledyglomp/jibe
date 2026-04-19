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
import logging

from aiohttp import web

from jibe import __version__
from jibe.api import InvalidMessageError, format_error, parse_message
from jibe.config import DEFAULT_PORT

logger = logging.getLogger(__name__)


class JibeServer:
    """Manages the aiohttp application lifecycle.

    Provides `start()` and `stop()` async methods so the server can be
    run alongside other async services (like mDNS discovery) using
    `asyncio.gather()`.
    """

    def __init__(self, port: int = DEFAULT_PORT) -> None:
        """Initialise the server.

        Args:
            port: The TCP port to listen on.
        """
        self._port = port
        self._app = web.Application()
        self._setup_routes()
        self._runner: web.AppRunner | None = None
        self._site: web.TCPSite | None = None

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
            }
        )

    async def handle_websocket(self, request: web.Request) -> web.WebSocketResponse:
        """Handle incoming WebSocket connections.

        Upgrades the HTTP connection to a WebSocket and processes incoming
        messages in a loop.
        """
        ws = web.WebSocketResponse()

        await ws.prepare(request)

        client_ip = request.remote
        logger.info("WebSocket connected from %s", client_ip)

        try:
            async for msg in ws:
                if msg.type == web.WSMsgType.TEXT:
                    try:
                        jibe_msg = parse_message(msg.data)
                        logger.info("Parsed valid %s message", jibe_msg.type.value)

                        # In future, we will route jibe_msg to specific handlers based
                        # on its type. For now, we just log it to prove parsing works.
                        logger.debug("Payload: %s", jibe_msg.payload)

                    except InvalidMessageError as e:
                        logger.warning("Invalid message from %s: %s", client_ip, str(e))
                        error_json = format_error(e.code, str(e))
                        await ws.send_str(error_json)

                elif msg.type == web.WSMsgType.ERROR:
                    logger.error(
                        "WebSocket connection closed with exception %s",
                        ws.exception()
                    )
        except asyncio.CancelledError:
            # Handle server shutdown
            pass
        finally:
            logger.info("WebSocket disconnected from %s", client_ip)

        return ws

    async def start(self) -> None:
        """Start listening for incoming connections."""
        self._runner = web.AppRunner(self._app)
        await self._runner.setup()

        self._site = web.TCPSite(self._runner, "0.0.0.0", self._port)
        await self._site.start()

        logger.info("WebSocket server listening on ws://0.0.0.0:%d/ws", self._port)

    async def stop(self) -> None:
        """Stop listening and cleanly disconnect all clients."""
        logger.info("Stopping server...")
        if self._runner:
            await self._runner.cleanup()
            self._runner = None
            self._site = None
        logger.info("Server stopped")
