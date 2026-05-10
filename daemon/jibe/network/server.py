"""Network server for the Jibe daemon.

Provides WebSocket at ``/ws``, static dashboard under ``/web/``, and JWT-protected
REST under ``/api/`` (localhost only).
"""

from __future__ import annotations

import asyncio
import json
import logging
import secrets
import shutil
import ssl
import time
from pathlib import Path
from typing import Awaitable, Callable

import aiosqlite
import bcrypt
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
from jibe.core.auth_jwt import JWTAuth, JWTAuthError
from jibe.core.config import (
    AUTH_TIMEOUT_SECONDS,
    CERTS_DIR,
    CERT_FILE,
    DEFAULT_PORT,
    WS_HEARTBEAT_SECONDS,
    WS_MAX_MESSAGE_BYTES,
)
from jibe.core.db import JibeDatabase
from jibe.core.tls import generate_self_signed_cert, get_cert_fingerprint
from jibe.handlers.clipboard import ClipboardMonitor, handle_clipboard_sync
from jibe.handlers.notifications import handle_notification
from jibe.handlers.ping import handle_ping
from jibe.handlers.pong import handle_pong
from jibe.handlers.router import MessageRouter
from jibe.handlers.transfer import (
    abort_transfers_for_connection,
    handle_file_cancel,
    handle_file_chunk_binary,
    handle_file_done,
    handle_file_start,
)
from jibe.network.connection import ConnectionRegistry, ConnectionState, JibeConnection
from jibe.network.ping_activity import PingActivityLog, PingProbeTracker

logger = logging.getLogger(__name__)

_PACKAGE_ROOT = Path(__file__).resolve().parent.parent.parent
STATIC_WEB_DIR = _PACKAGE_ROOT / "web" / "static"


Handler = Callable[[web.Request], Awaitable[web.StreamResponse]]


def _request_is_localhost(request: web.Request) -> bool:
    remote = request.remote or ""
    return remote in ("127.0.0.1", "::1", "localhost")


def _localhost_only_middleware() -> Callable[..., Awaitable[web.StreamResponse]]:
    @web.middleware
    async def _mw(request: web.Request, handler: Handler) -> web.StreamResponse:
        if request.path.startswith("/api/") and not _request_is_localhost(request):
            raise web.HTTPForbidden(text="REST API is localhost-only")
        return await handler(request)

    return _mw


def _jwt_auth_middleware(jwt_auth: JWTAuth) -> Callable[..., Awaitable[web.StreamResponse]]:
    @web.middleware
    async def _mw(request: web.Request, handler: Handler) -> web.StreamResponse:
        path = request.path
        if path.startswith("/web"):
            return await handler(request)
        if path == "/" or path == "/health" or path.startswith("/ws"):
            return await handler(request)
        if path.startswith("/api/"):
            if path in (
                "/api/auth/login",
                "/api/auth/logout",
                "/api/auth/recovery-status",
                "/api/auth/recovery-reset",
            ):
                return await handler(request)
            auth_header = request.headers.get("Authorization", "")
            if not auth_header.startswith("Bearer "):
                return web.json_response({"error": "unauthorized"}, status=401)
            token = auth_header.removeprefix("Bearer ").strip()
            if not token:
                return web.json_response({"error": "unauthorized"}, status=401)
            try:
                request["jwt_user"] = await jwt_auth.verify_token(token)
            except JWTAuthError:
                return web.json_response({"error": "unauthorized"}, status=401)
        return await handler(request)

    return _mw


class JibeServer:
    """aiohttp application: WebSocket, REST, static dashboard."""

    def __init__(
        self,
        db: JibeDatabase,
        port: int = DEFAULT_PORT,
        ssl_context: ssl.SSLContext | None = None,
        cert_path: Path | None = None,
    ) -> None:
        self._port = port
        self._db = db
        self._ssl_context = ssl_context
        self._cert_path = cert_path
        # When TLS is active the main site is WSS-only; the dashboard is served
        # on a separate plain-HTTP site on the next port (localhost-only).
        self._dashboard_port = (port + 1) if ssl_context is not None else port
        self._auth = AuthManager(db)
        self._registry = ConnectionRegistry()
        self._clipboard_monitor = ClipboardMonitor(self._registry, db=db)
        self._router = MessageRouter()
        self._jwt_auth = JWTAuth(db)
        self._ping_log = PingActivityLog()
        self._probe_tracker = PingProbeTracker()
        self._register_handlers()
        self._started_at_monotonic: float | None = None
        self._app = web.Application(
            middlewares=[
                _localhost_only_middleware(),
                _jwt_auth_middleware(self._jwt_auth),
            ]
        )
        self._setup_routes()
        self._runner: web.AppRunner | None = None
        self._site: web.TCPSite | None = None
        self._dashboard_site: web.TCPSite | None = None
        self._clipboard_monitor_task: asyncio.Task[None] | None = None

    @property
    def dashboard_port(self) -> int:
        """TCP port for the plain-HTTP dashboard / REST API listener."""
        return self._dashboard_port

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
        db = self._db

        async def handle_clipboard(conn: JibeConnection, msg: JibeMessage) -> None:
            await handle_clipboard_sync(conn, msg, monitor, db=db)

        async def handle_transfer_start(conn: JibeConnection, msg: JibeMessage) -> None:
            await handle_file_start(conn, msg, db=db)

        async def handle_transfer_cancel(conn: JibeConnection, msg: JibeMessage) -> None:
            await handle_file_cancel(conn, msg, db=db)

        async def handle_transfer_done(conn: JibeConnection, msg: JibeMessage) -> None:
            await handle_file_done(conn, msg, db=db)

        async def handle_notify(conn: JibeConnection, msg: JibeMessage) -> None:
            await handle_notification(conn, msg, db=db)

        ping_log = self._ping_log
        tracker = self._probe_tracker

        async def _ping_route(conn: JibeConnection, msg: JibeMessage) -> None:
            await handle_ping(conn, msg, activity_log=ping_log)

        async def _pong_route(conn: JibeConnection, msg: JibeMessage) -> None:
            await handle_pong(conn, msg, probe_tracker=tracker, activity_log=ping_log)

        self._router.register(MessageType.PING, _ping_route)
        self._router.register(MessageType.PONG, _pong_route)
        self._router.register(MessageType.CLIPBOARD_SYNC, handle_clipboard)
        self._router.register(MessageType.FILE_START, handle_transfer_start)
        self._router.register(MessageType.FILE_CANCEL, handle_transfer_cancel)
        self._router.register(MessageType.FILE_DONE, handle_transfer_done)
        self._router.register(MessageType.NOTIFICATION, handle_notify)

    def _setup_routes(self) -> None:
        """Configure HTTP, WebSocket, static files, and REST."""
        app = self._app
        app.router.add_get("/", self.handle_root_redirect)
        app.router.add_get("/health", self.handle_health)
        app.router.add_get("/ws", self.handle_websocket)
        app.router.add_static("/web/", STATIC_WEB_DIR, show_index=False)

        app.router.add_post("/api/auth/login", self.handle_api_login)
        app.router.add_post("/api/auth/logout", self.handle_api_logout)
        app.router.add_get("/api/auth/recovery-status", self.handle_api_auth_recovery_status)
        app.router.add_post("/api/auth/recovery-reset", self.handle_api_auth_recovery_reset)
        app.router.add_post("/api/auth/change-password", self.handle_api_auth_change_password)

        app.router.add_post("/api/settings/data/clear-history", self.handle_api_settings_clear_history)
        app.router.add_post(
            "/api/settings/data/clear-statistics",
            self.handle_api_settings_clear_statistics,
        )
        app.router.add_post(
            "/api/settings/recovery/regenerate",
            self.handle_api_settings_recovery_regenerate,
        )

        app.router.add_get("/api/status", self.handle_api_status)
        app.router.add_get("/api/devices", self.handle_api_devices)
        app.router.add_get("/api/devices/{device_id}", self.handle_api_device_one)
        app.router.add_patch("/api/devices/{device_id}", self.handle_api_patch_device)
        app.router.add_delete("/api/devices/{device_id}", self.handle_api_delete_device)

        app.router.add_get("/api/history/transfers", self.handle_api_history_transfers)
        app.router.add_get(
            "/api/history/transfers/{transfer_id}",
            self.handle_api_history_transfer_one,
        )
        app.router.add_get("/api/history/clipboard", self.handle_api_history_clipboard)
        app.router.add_get(
            "/api/history/clipboard/{event_id}",
            self.handle_api_history_clipboard_one,
        )
        app.router.add_get(
            "/api/history/notifications",
            self.handle_api_history_notifications,
        )
        app.router.add_get(
            "/api/history/notifications/{event_id}",
            self.handle_api_history_notification_one,
        )

        app.router.add_get("/api/stats", self.handle_api_stats)

        app.router.add_get("/api/users", self.handle_api_users_list)
        app.router.add_post("/api/users", self.handle_api_users_create)
        app.router.add_patch("/api/users/{user_id}", self.handle_api_users_patch)
        app.router.add_delete("/api/users/{user_id}", self.handle_api_users_delete)

        app.router.add_get("/api/daemon/status", self.handle_api_daemon_status)
        app.router.add_post("/api/daemon/pairing/start", self.handle_api_daemon_pairing_start)
        app.router.add_post("/api/daemon/pairing/stop", self.handle_api_daemon_pairing_stop)
        app.router.add_get("/api/daemon/pairing/status", self.handle_api_daemon_pairing_status)
        app.router.add_post("/api/daemon/certs/regen", self.handle_api_daemon_certs_regen)
        app.router.add_get("/api/daemon/ping-activity", self.handle_api_daemon_ping_activity)
        app.router.add_post("/api/daemon/ping-send", self.handle_api_daemon_ping_send)

    async def handle_root_redirect(self, request: web.Request) -> web.Response:
        """Redirect browser root to the login page."""
        raise web.HTTPFound(location="/web/index.html")

    async def handle_health(self, request: web.Request) -> web.Response:
        """Lightweight JSON health (no JWT)."""
        return web.json_response(
            {
                "status": "running",
                "version": __version__,
                "connected_devices": self._registry.authenticated_count,
            }
        )

    async def handle_websocket(self, request: web.Request) -> web.WebSocketResponse:
        """WebSocket protocol endpoint."""
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

        async def auth_timeout() -> None:
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
            await abort_transfers_for_connection(conn.id, db=self._db)
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
        """Parse and route one text frame."""
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
        """Authenticated binary frames (file chunks)."""
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
        await handle_file_chunk_binary(conn, data, db=self._db)

    @staticmethod
    def _is_localhost(request: web.Request) -> bool:
        return _request_is_localhost(request)

    @staticmethod
    def _require_user(request: web.Request) -> dict[str, str]:
        user = request.get("jwt_user")
        if not user:
            raise web.HTTPUnauthorized(text=json.dumps({"error": "unauthorized"}))
        return user

    @staticmethod
    def _require_admin(user: dict[str, str]) -> None:
        if user.get("role") != "admin":
            raise web.HTTPForbidden(text=json.dumps({"error": "forbidden"}))

    async def handle_api_login(self, request: web.Request) -> web.Response:
        """POST /api/auth/login — bcrypt verify + JWT."""
        try:
            body = await request.json()
        except json.JSONDecodeError:
            raise web.HTTPBadRequest(text=json.dumps({"error": "invalid json"}))

        username = body.get("username")
        password = body.get("password")
        if not isinstance(username, str) or not isinstance(password, str):
            raise web.HTTPBadRequest(text=json.dumps({"error": "username and password required"}))

        row = await self._db.get_user_by_username(username)
        if row is None:
            raise web.HTTPUnauthorized(text=json.dumps({"error": "invalid credentials"}))

        if not bcrypt.checkpw(password.encode(), row["password_hash"].encode()):
            raise web.HTTPUnauthorized(text=json.dumps({"error": "invalid credentials"}))

        token, exp = await self._jwt_auth.create_access_token(
            row["id"], row["username"], row["role"]
        )
        return web.json_response(
            {
                "token": token,
                "role": row["role"],
                "username": row["username"],
                "expires_at": exp,
            }
        )

    async def handle_api_logout(self, request: web.Request) -> web.Response:
        """POST /api/auth/logout — stateless OK."""
        return web.json_response({"ok": True})

    async def handle_api_auth_recovery_status(self, request: web.Request) -> web.Response:
        """GET /api/auth/recovery-status — whether a recovery token file exists."""
        enabled = self._db.recovery_key_configured()
        payload: dict = {"recovery_enabled": enabled}
        if enabled:
            payload["recovery_file_path"] = str(self._db.recovery_key_path().resolve())
        return web.json_response(payload)

    async def handle_api_auth_recovery_reset(self, request: web.Request) -> web.Response:
        """POST /api/auth/recovery-reset — set oldest admin password using recovery token."""
        try:
            body = await request.json()
        except json.JSONDecodeError:
            raise web.HTTPBadRequest(text=json.dumps({"error": "invalid json"}))

        token = body.get("recovery_token")
        new_password = body.get("new_password")
        if not isinstance(token, str) or not isinstance(new_password, str):
            raise web.HTTPBadRequest(text=json.dumps({"error": "recovery_token and new_password required"}))
        if len(new_password) < 10:
            raise web.HTTPBadRequest(
                text=json.dumps({"error": "new_password must be at least 10 characters"})
            )

        stored = self._db.read_recovery_key_secret()
        if not stored:
            raise web.HTTPBadRequest(text=json.dumps({"error": "recovery is not configured"}))

        if not secrets.compare_digest(stored, token.strip()):
            raise web.HTTPUnauthorized(text=json.dumps({"error": "invalid recovery token"}))

        admin_id = await self._db.first_admin_user_id()
        if admin_id is None:
            raise web.HTTPBadRequest(text=json.dumps({"error": "no admin user"}))

        await self._db.update_user_password(admin_id, new_password)
        logger.warning("Dashboard admin password reset via recovery token (localhost)")
        return web.json_response({"ok": True})

    async def handle_api_auth_change_password(self, request: web.Request) -> web.Response:
        """POST /api/auth/change-password — JWT user supplies current password."""
        jwt_user = self._require_user(request)
        try:
            body = await request.json()
        except json.JSONDecodeError:
            raise web.HTTPBadRequest(text=json.dumps({"error": "invalid json"}))

        current_password = body.get("current_password")
        new_password = body.get("new_password")
        if not isinstance(current_password, str) or not isinstance(new_password, str):
            raise web.HTTPBadRequest(
                text=json.dumps({"error": "current_password and new_password required"})
            )
        if len(new_password) < 10:
            raise web.HTTPBadRequest(
                text=json.dumps({"error": "new_password must be at least 10 characters"})
            )

        row = await self._db.get_user_by_username(jwt_user["username"])
        if row is None:
            raise web.HTTPUnauthorized(text=json.dumps({"error": "invalid credentials"}))

        if not bcrypt.checkpw(current_password.encode(), row["password_hash"].encode()):
            raise web.HTTPUnauthorized(text=json.dumps({"error": "current password incorrect"}))

        await self._db.update_user_password(row["id"], new_password)
        return web.json_response({"ok": True})

    async def handle_api_settings_clear_history(self, request: web.Request) -> web.Response:
        """POST — wipe transfer, clipboard, and notification logs (admin)."""
        user = self._require_user(request)
        self._require_admin(user)
        await self._db.clear_transfer_history()
        await self._db.clear_clipboard_history()
        await self._db.clear_notification_log()
        return web.json_response({"ok": True})

    async def handle_api_settings_clear_statistics(self, request: web.Request) -> web.Response:
        """POST — wipe session records (admin); summary charts use activity tables."""
        user = self._require_user(request)
        self._require_admin(user)
        n = await self._db.clear_sessions()
        return web.json_response({"ok": True, "sessions_removed": n})

    async def handle_api_settings_recovery_regenerate(self, request: web.Request) -> web.Response:
        """POST — issue a new recovery token; returned once (admin)."""
        user = self._require_user(request)
        self._require_admin(user)
        token = secrets.token_urlsafe(32)
        self._db.write_recovery_key_secret(token)
        logger.warning("Dashboard recovery token regenerated (localhost)")
        return web.json_response({"recovery_token": token})

    async def handle_api_daemon_ping_activity(self, request: web.Request) -> web.Response:
        user = self._require_user(request)
        self._require_admin(user)
        return web.json_response({"items": self._ping_log.list_dicts()})

    async def handle_api_daemon_ping_send(self, request: web.Request) -> web.Response:
        """Broadcast an application ping with a probe id so ``pong`` can measure RTT."""
        user = self._require_user(request)
        self._require_admin(user)
        probe = self._probe_tracker.start_probe()
        payload = json.dumps({"type": MessageType.PING.value, "probe": probe})
        n = 0
        for conn in self._registry.get_authenticated():
            await conn.send(payload)
            n += 1
        return web.json_response({"sent": n, "probe": probe})

    async def handle_api_status(self, request: web.Request) -> web.Response:
        """GET /api/status."""
        self._require_user(request)
        return web.json_response(
            {
                "status": "running",
                "version": __version__,
                "connected_devices": self._registry.authenticated_count,
                "total_connections": self._registry.count,
            }
        )

    async def handle_api_devices(self, request: web.Request) -> web.Response:
        """GET /api/devices — include ``online``."""
        self._require_user(request)
        devices = await self._db.list_devices()
        enriched = []
        for d in devices:
            online = self._registry.get_by_device_id(d["id"]) is not None
            enriched.append({**d, "online": online})
        return web.json_response({"devices": enriched})

    async def handle_api_device_one(self, request: web.Request) -> web.Response:
        """GET /api/devices/{device_id}."""
        self._require_user(request)
        device_id = request.match_info["device_id"]
        row = await self._db.get_device_by_id(device_id)
        if row is None:
            raise web.HTTPNotFound(text=json.dumps({"error": "not found"}))
        online = self._registry.get_by_device_id(device_id) is not None
        return web.json_response({**row, "online": online})

    async def handle_api_patch_device(self, request: web.Request) -> web.Response:
        """PATCH /api/devices/{device_id} — rename (admin)."""
        user = self._require_user(request)
        self._require_admin(user)
        device_id = request.match_info["device_id"]
        try:
            body = await request.json()
        except json.JSONDecodeError:
            raise web.HTTPBadRequest(text=json.dumps({"error": "invalid json"}))
        name = body.get("name")
        if not isinstance(name, str) or not name.strip():
            raise web.HTTPBadRequest(text=json.dumps({"error": "name required"}))
        ok = await self._db.rename_device(device_id, name.strip())
        if not ok:
            raise web.HTTPNotFound(text=json.dumps({"error": "not found"}))
        row = await self._db.get_device_by_id(device_id)
        return web.json_response(row)

    async def handle_api_delete_device(self, request: web.Request) -> web.Response:
        """DELETE /api/devices/{device_id} — admin + disconnect."""
        user = self._require_user(request)
        self._require_admin(user)
        device_id = request.match_info["device_id"]
        conn = self._registry.get_by_device_id(device_id)
        if conn is not None:
            await conn.close()
        removed = await self._db.remove_device(device_id)
        if not removed:
            raise web.HTTPNotFound(text=json.dumps({"error": "not found"}))
        return web.json_response({"removed": device_id})

    def _parse_list_params(self, request: web.Request) -> tuple[int, int, dict[str, str | None]]:
        q = request.rel_url.query
        try:
            page = max(1, int(q.get("page", "1")))
        except (ValueError, TypeError):
            page = 1
        try:
            per_page = max(1, min(100, int(q.get("per_page", "25"))))
        except (ValueError, TypeError):
            per_page = 25
        filters = {
            "device_id": q.get("device_id"),
            "status": q.get("status"),
            "direction": q.get("direction"),
            "source": q.get("source"),
            "app": q.get("app"),
            "from": q.get("from"),
            "to": q.get("to"),
        }
        return page, per_page, filters

    async def handle_api_history_transfers(self, request: web.Request) -> web.Response:
        self._require_user(request)
        page, per_page, f = self._parse_list_params(request)
        items, meta = await self._db.list_transfers(
            device_id=f["device_id"],
            status=f["status"],
            direction=f["direction"],
            date_from=f["from"],
            date_to=f["to"],
            page=page,
            per_page=per_page,
        )
        return web.json_response({"items": items, **meta})

    async def handle_api_history_transfer_one(self, request: web.Request) -> web.Response:
        self._require_user(request)
        tid = request.match_info["transfer_id"]
        row = await self._db.get_transfer_by_id(tid)
        if row is None:
            raise web.HTTPNotFound(text=json.dumps({"error": "not found"}))
        return web.json_response(row)

    async def handle_api_history_clipboard(self, request: web.Request) -> web.Response:
        self._require_user(request)
        q = request.rel_url.query
        page = max(1, int(q.get("page", "1")))
        per_page = max(1, min(100, int(q.get("per_page", "25"))))
        source_filter = q.get("source") or q.get("device_id")
        items, meta = await self._db.list_clipboard_events(
            source=source_filter,
            direction=q.get("direction"),
            date_from=q.get("from"),
            date_to=q.get("to"),
            page=page,
            per_page=per_page,
        )
        return web.json_response({"items": items, **meta})

    async def handle_api_history_clipboard_one(self, request: web.Request) -> web.Response:
        self._require_user(request)
        eid = request.match_info["event_id"]
        row = await self._db.get_clipboard_event(eid)
        if row is None:
            raise web.HTTPNotFound(text=json.dumps({"error": "not found"}))
        return web.json_response(row)

    async def handle_api_history_notifications(self, request: web.Request) -> web.Response:
        self._require_user(request)
        page, per_page, f = self._parse_list_params(request)
        items, meta = await self._db.list_notifications(
            device_id=f["device_id"],
            app=f["app"],
            date_from=f["from"],
            date_to=f["to"],
            page=page,
            per_page=per_page,
        )
        return web.json_response({"items": items, **meta})

    async def handle_api_history_notification_one(self, request: web.Request) -> web.Response:
        self._require_user(request)
        eid = request.match_info["event_id"]
        row = await self._db.get_notification(eid)
        if row is None:
            raise web.HTTPNotFound(text=json.dumps({"error": "not found"}))
        return web.json_response(row)

    async def handle_api_stats(self, request: web.Request) -> web.Response:
        self._require_user(request)
        stats = await self._db.get_stats()
        return web.json_response(stats)

    async def handle_api_users_list(self, request: web.Request) -> web.Response:
        user = self._require_user(request)
        self._require_admin(user)
        rows = await self._db.list_users()
        return web.json_response({"users": rows})

    async def handle_api_users_create(self, request: web.Request) -> web.Response:
        user = self._require_user(request)
        self._require_admin(user)
        try:
            body = await request.json()
        except json.JSONDecodeError:
            raise web.HTTPBadRequest(text=json.dumps({"error": "invalid json"}))
        username = body.get("username")
        password = body.get("password")
        role = body.get("role", "viewer")
        if not isinstance(username, str) or not isinstance(password, str):
            raise web.HTTPBadRequest(text=json.dumps({"error": "username and password required"}))
        if role not in ("admin", "viewer"):
            raise web.HTTPBadRequest(text=json.dumps({"error": "invalid role"}))
        try:
            created = await self._db.add_user(username, password, role)
        except aiosqlite.IntegrityError:
            raise web.HTTPBadRequest(
                body=json.dumps({"error": "username already exists"}).encode(),
                content_type="application/json",
            ) from None
        except Exception:
            logger.exception("create user failed")
            raise web.HTTPBadRequest(
                body=json.dumps({"error": "could not create user"}).encode(),
                content_type="application/json",
            ) from None
        return web.json_response(created, status=201)

    async def handle_api_users_patch(self, request: web.Request) -> web.Response:
        user = self._require_user(request)
        self._require_admin(user)
        uid = request.match_info["user_id"]
        try:
            body = await request.json()
        except json.JSONDecodeError:
            raise web.HTTPBadRequest(text=json.dumps({"error": "invalid json"}))
        role = body.get("role")
        if role not in ("admin", "viewer"):
            raise web.HTTPBadRequest(text=json.dumps({"error": "invalid role"}))
        ok = await self._db.update_user_role(uid, role)
        if not ok:
            raise web.HTTPNotFound(text=json.dumps({"error": "not found"}))
        row = await self._db.get_user_by_id(uid)
        return web.json_response(row)

    async def handle_api_users_delete(self, request: web.Request) -> web.Response:
        user = self._require_user(request)
        self._require_admin(user)
        uid = request.match_info["user_id"]
        if uid == user["id"]:
            raise web.HTTPBadRequest(text=json.dumps({"error": "cannot delete self"}))
        ok = await self._db.delete_user(uid)
        if not ok:
            raise web.HTTPNotFound(text=json.dumps({"error": "not found"}))
        return web.json_response({"removed": uid})

    def _tls_fingerprint_optional(self) -> str | None:
        path = self._cert_path or (CERTS_DIR / CERT_FILE)
        if not path.exists():
            return None
        try:
            return get_cert_fingerprint(path)
        except Exception:
            logger.exception("Could not read TLS fingerprint")
            return None

    async def handle_api_daemon_status(self, request: web.Request) -> web.Response:
        user = self._require_user(request)
        self._require_admin(user)
        uptime = 0.0
        if self._started_at_monotonic is not None:
            uptime = time.monotonic() - self._started_at_monotonic
        snap = self._auth.pairing_status_snapshot()
        return web.json_response(
            {
                "version": __version__,
                "uptime_seconds": uptime,
                "connected_devices": self._registry.authenticated_count,
                "pairing_active": snap["active"],
                "tls_enabled": self._ssl_context is not None,
                "tls_fingerprint": self._tls_fingerprint_optional(),
            }
        )

    async def handle_api_daemon_pairing_start(self, request: web.Request) -> web.Response:
        user = self._require_user(request)
        self._require_admin(user)
        pin = self._auth.start_pairing()
        snap = self._auth.pairing_status_snapshot()
        return web.json_response({"pin": pin, "expires_at": snap.get("expires_at")})

    async def handle_api_daemon_pairing_stop(self, request: web.Request) -> web.Response:
        user = self._require_user(request)
        self._require_admin(user)
        self._auth.stop_pairing()
        return web.json_response({"ok": True})

    async def handle_api_daemon_pairing_status(self, request: web.Request) -> web.Response:
        user = self._require_user(request)
        self._require_admin(user)
        return web.json_response(self._auth.pairing_status_snapshot())

    async def handle_api_daemon_certs_regen(self, request: web.Request) -> web.Response:
        user = self._require_user(request)
        self._require_admin(user)
        if CERTS_DIR.exists():
            shutil.rmtree(CERTS_DIR)
        cert_path, _ = generate_self_signed_cert()
        fp = get_cert_fingerprint(cert_path)
        self._cert_path = cert_path
        return web.json_response(
            {"fingerprint": fp, "note": "restart required — reload TLS context"}
        )

    async def start(self) -> None:
        """Start listening for incoming connections."""
        self._started_at_monotonic = time.monotonic()
        self._runner = web.AppRunner(self._app)
        await self._runner.setup()

        self._site = web.TCPSite(
            self._runner,
            "0.0.0.0",
            self._port,
            ssl_context=self._ssl_context,
        )
        await self._site.start()

        if self._ssl_context is not None:
            # The TLS site handles WebSocket only; serve the dashboard and REST
            # API over a plain-HTTP site bound to localhost so the browser can
            # reach it without TLS certificate warnings.
            self._dashboard_site = web.TCPSite(
                self._runner,
                "127.0.0.1",
                self._dashboard_port,
            )
            await self._dashboard_site.start()
            logger.info(
                "Dashboard HTTP listening on http://127.0.0.1:%d/",
                self._dashboard_port,
            )

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
            self._dashboard_site = None
        logger.info("Server stopped")
