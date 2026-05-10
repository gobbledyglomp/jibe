"""Presentation remote handler.

Receives ``remote.key`` messages from Android and dispatches the corresponding
key event to the focused Linux window via ``xdotool`` (X11) or ``ydotool``
(Wayland).  The tool is detected once and cached for the process lifetime.
"""

from __future__ import annotations

import asyncio
import logging
import os
import shutil

from jibe.core.api import JibeMessage, format_error
from jibe.core.config import REMOTE_KEY_ALLOWED
from jibe.network.connection import JibeConnection

logger = logging.getLogger(__name__)

# X11: symbolic names for ``xdotool key``.
XDOTOOL_KEY: dict[str, str] = {
    "next": "Right",
    "prev": "Left",
    "stop": "Escape",
    "blank": "b",
}

# Wayland (``ydotool key``): Linux input codes press/release (see ``linux/input-event-codes.h``).
YDOTOOL_KEY: dict[str, tuple[str, str]] = {
    "next": ("106:1", "106:0"),
    "prev": ("105:1", "105:0"),
    "stop": ("1:1", "1:0"),
    "blank": ("48:1", "48:0"),
}

_detected_tool: str | None = None
_detection_done: bool = False


def _detect_tool() -> str | None:
    """Return the first available key-dispatch tool, or ``None`` if none found.

    Checks ``WAYLAND_DISPLAY`` first (ydotool); falls back to xdotool on X11.
    Result is cached after the first call.
    """
    global _detected_tool, _detection_done
    if _detection_done:
        return _detected_tool

    _detection_done = True
    if os.environ.get("WAYLAND_DISPLAY"):
        path = shutil.which("ydotool")
        if path:
            logger.debug("Presentation remote: using ydotool (Wayland)")
            _detected_tool = "ydotool"
            return _detected_tool
        logger.warning(
            "Wayland session detected but ydotool not found. "
            "Install ydotool for presentation remote support."
        )
        return None

    path = shutil.which("xdotool")
    if path:
        logger.debug("Presentation remote: using xdotool (X11)")
        _detected_tool = "xdotool"
        return _detected_tool

    logger.warning(
        "Neither xdotool nor ydotool found. "
        "Install one of them for presentation remote support."
    )
    return None


async def _dispatch_key(logical_key: str) -> None:
    """Invoke the detected tool to send a single key event.

    Args:
        logical_key: One of ``REMOTE_KEY_ALLOWED`` (``next``, ``prev``, …).
    """
    tool = _detect_tool()
    if tool is None:
        return

    if tool == "ydotool":
        press, release = YDOTOOL_KEY[logical_key]
        args = ["ydotool", "key", press, release]
    else:
        args = ["xdotool", "key", XDOTOOL_KEY[logical_key]]

    try:
        proc = await asyncio.create_subprocess_exec(
            *args,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.DEVNULL,
        )
        await proc.wait()
        if proc.returncode != 0:
            logger.warning(
                "%s exited %d dispatching logical key %s",
                tool,
                proc.returncode,
                logical_key,
            )
    except FileNotFoundError:
        logger.exception("%s not found — cannot dispatch key event", tool)
    except Exception:
        logger.exception("Key dispatch failed for logical key %s", logical_key)


async def handle_remote_key(
    conn: JibeConnection,
    msg: JibeMessage,
) -> None:
    """Dispatch the key event described in a ``remote.key`` message.

    Args:
        conn: Authenticated connection that sent the message.
        msg: Parsed message; expects ``key`` string from ``REMOTE_KEY_ALLOWED``.
    """
    key = msg.payload.get("key")

    if not isinstance(key, str) or key not in REMOTE_KEY_ALLOWED:
        allowed = ", ".join(sorted(REMOTE_KEY_ALLOWED))
        await conn.send(
            format_error(
                "invalid_key",
                f"remote.key 'key' must be one of: {allowed}",
            )
        )
        return

    logger.debug("remote.key '%s' from %s", key, conn.id)
    await _dispatch_key(key)
