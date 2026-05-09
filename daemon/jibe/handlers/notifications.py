"""Mirror Android notifications to the Linux desktop via ``notify-send``."""

from __future__ import annotations

import asyncio
import logging

from jibe.core.api import JibeMessage, format_error
from jibe.network.connection import JibeConnection

logger = logging.getLogger(__name__)


async def handle_notification(conn: JibeConnection, msg: JibeMessage) -> None:
    """Forward ``notification`` payloads to ``notify-send``."""
    payload = msg.payload
    app = payload.get("app")
    title = payload.get("title")
    body = payload.get("body")
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

    try:
        proc = await asyncio.create_subprocess_exec(
            "notify-send",
            "--app-name",
            app,
            title,
            body,
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
        await conn.send(format_error("internal_error", "notify-send is not available on this system"))
    except Exception:
        logger.exception("notify-send failed for connection %s", conn.id)
        await conn.send(format_error("internal_error", "Failed to display desktop notification"))
