"""Mirror Android notifications to the Linux desktop via ``notify-send``.

Incoming ``notification`` messages may carry:
- ``app_name``  — human-readable application label (falls back to ``app`` package name).
- ``icon``      — base64 PNG of the app's small icon (``--icon``, desktop chrome).
"""

from __future__ import annotations

import asyncio
import base64
import binascii
import logging
import os
import tempfile

from jibe.core.api import JibeMessage, format_error
from jibe.network.connection import JibeConnection

logger = logging.getLogger(__name__)


def _decode_b64_field(value: object, label: str) -> bytes | None:
    """Decode an optional base64 string field; log and return None on bad data."""
    if not isinstance(value, str) or not value:
        return None
    try:
        return base64.b64decode(value)
    except (binascii.Error, ValueError):
        logger.debug("Ignoring invalid %s base64 in notification payload", label)
        return None


def _write_temp_png(data: bytes, prefix: str) -> str:
    """Write *data* to a named temp file with a ``.png`` suffix; return its path."""
    fd, path = tempfile.mkstemp(suffix=".png", prefix=f"jibe_{prefix}_")
    try:
        os.write(fd, data)
    finally:
        os.close(fd)
    return path


def _unlink_silently(path: str | None) -> None:
    if path is None:
        return
    try:
        os.unlink(path)
    except FileNotFoundError:
        pass


async def handle_notification(conn: JibeConnection, msg: JibeMessage) -> None:
    """Forward ``notification`` payloads to ``notify-send``."""
    payload = msg.payload
    app = payload.get("app")
    title = payload.get("title", "")
    body = payload.get("body", "")
    ts = payload.get("timestamp")

    if not isinstance(app, str) or not app.strip():
        await conn.send(format_error("malformed_payload", "notification requires app"))
        return
    if not isinstance(title, str):
        await conn.send(format_error("malformed_payload", "notification requires title string"))
        return
    if not isinstance(body, str):
        await conn.send(format_error("malformed_payload", "notification requires body string"))
        return
    if not isinstance(ts, int):
        await conn.send(format_error("malformed_payload", "notification requires integer timestamp"))
        return

    raw_app_name = payload.get("app_name")
    display_name = (
        raw_app_name if isinstance(raw_app_name, str) and raw_app_name.strip() else app
    )

    icon_data = _decode_b64_field(payload.get("icon"), "icon")

    icon_path: str | None = None

    try:
        if icon_data:
            icon_path = await asyncio.to_thread(_write_temp_png, icon_data, "icon")

        args = ["notify-send", "--app-name", display_name]
        if icon_path:
            args += ["--icon", icon_path]
        args += [title, body]

        try:
            proc = await asyncio.create_subprocess_exec(
                *args,
                stdout=asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.DEVNULL,
            )
            await proc.wait()
            if proc.returncode != 0:
                logger.warning(
                    "notify-send exited %s for notification from %s",
                    proc.returncode,
                    conn.id,
                )
        except FileNotFoundError:
            logger.exception("notify-send not found — cannot display mirrored notification")
            await conn.send(
                format_error("internal_error", "notify-send is not available on this system")
            )
        except Exception:
            logger.exception("notify-send failed for connection %s", conn.id)
            await conn.send(format_error("internal_error", "Failed to display desktop notification"))
    finally:
        _unlink_silently(icon_path)
