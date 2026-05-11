"""Presentation remote handler.

Receives ``remote.key`` messages from Android and dispatches the corresponding
key event to the focused Linux window via ``xdotool`` (X11) or ``ydotool``
(Wayland).  The tool is detected once and cached for the process lifetime.

On Wayland, ``ydotool`` requires the ``ydotoold`` companion daemon.  If the
socket is missing the first time a key is dispatched, we attempt to start
``ydotoold`` automatically and retry.
"""

from __future__ import annotations

import asyncio
import atexit
import logging
import os
import shutil
import signal

from jibe.core.api import JibeMessage, format_error
from jibe.core.config import REMOTE_KEY_ALLOWED
from jibe.network.connection import JibeConnection

logger = logging.getLogger(__name__)

XDOTOOL_KEY: dict[str, str] = {
    "next": "Right",
    "prev": "Left",
    "stop": "Escape",
    "blank": "b",
}

YDOTOOL_KEY: dict[str, tuple[str, str]] = {
    "next": ("106:1", "106:0"),
    "prev": ("105:1", "105:0"),
    "stop": ("1:1", "1:0"),
    "blank": ("48:1", "48:0"),
}

_detected_tool: str | None = None
_detection_done: bool = False
_ydotoold_proc: asyncio.subprocess.Process | None = None
_ydotoold_started: bool = False

YDOTOOLD_SETTLE_SECONDS = 0.6
YDOTOOLD_MAX_WAIT_SECONDS = 3.0


def _detect_tool() -> str | None:
    """Return the first available key-dispatch tool, or ``None``."""
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


async def _ensure_ydotoold() -> bool:
    """Start ``ydotoold`` if it is not already running.

    Returns ``True`` when the daemon is believed to be ready.
    """
    global _ydotoold_proc, _ydotoold_started

    if _ydotoold_started:
        if _ydotoold_proc is not None and _ydotoold_proc.returncode is None:
            return True
        logger.warning("ydotoold exited unexpectedly, restarting")
        _ydotoold_started = False

    ydotoold = shutil.which("ydotoold")
    if ydotoold is None:
        logger.warning(
            "ydotoold not found — install it alongside ydotool for presentation remote"
        )
        return False

    try:
        _ydotoold_proc = await asyncio.create_subprocess_exec(
            ydotoold,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.DEVNULL,
        )
        _ydotoold_started = True
        atexit.register(_kill_ydotoold_sync)
        logger.info("Started ydotoold (PID %d) for presentation remote", _ydotoold_proc.pid)

        await asyncio.sleep(YDOTOOLD_SETTLE_SECONDS)

        elapsed = YDOTOOLD_SETTLE_SECONDS
        while elapsed < YDOTOOLD_MAX_WAIT_SECONDS:
            if _ydotoold_proc.returncode is not None:
                logger.warning("ydotoold exited immediately with code %d", _ydotoold_proc.returncode)
                _ydotoold_started = False
                return False
            probe = await asyncio.create_subprocess_exec(
                "ydotool", "key", "0:0",
                stdout=asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.DEVNULL,
            )
            await probe.wait()
            if probe.returncode == 0:
                return True
            await asyncio.sleep(0.3)
            elapsed += 0.3

        return _ydotoold_proc.returncode is None
    except Exception:
        logger.exception("Failed to start ydotoold")
        return False


def _kill_ydotoold_sync() -> None:
    """Best-effort cleanup registered via ``atexit``."""
    proc = _ydotoold_proc
    if proc is not None and proc.returncode is None:
        try:
            proc.send_signal(signal.SIGTERM)
        except ProcessLookupError:
            pass


async def _dispatch_key(logical_key: str) -> bool:
    """Invoke the detected tool to send a single key event.

    Returns ``True`` on success, ``False`` when the tool exited non-zero.
    """
    tool = _detect_tool()
    if tool is None:
        return False

    if tool == "ydotool":
        press, release = YDOTOOL_KEY[logical_key]
        args = ["ydotool", "key", press, release]
    else:
        args = ["xdotool", "key", XDOTOOL_KEY[logical_key]]

    try:
        proc = await asyncio.create_subprocess_exec(
            *args,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.PIPE,
        )
        _, stderr_bytes = await proc.communicate()
        if proc.returncode != 0:
            detail = stderr_bytes.decode(errors="replace").strip() if stderr_bytes else ""
            logger.warning(
                "%s exited %d dispatching logical key %s%s",
                tool,
                proc.returncode,
                logical_key,
                f": {detail}" if detail else "",
            )
            return False
        return True
    except FileNotFoundError:
        logger.exception("%s not found — cannot dispatch key event", tool)
        return False
    except Exception:
        logger.exception("Key dispatch failed for logical key %s", logical_key)
        return False


async def handle_remote_key(
    conn: JibeConnection,
    msg: JibeMessage,
) -> None:
    """Dispatch the key event described in a ``remote.key`` message."""
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

    tool = _detect_tool()
    if tool is None:
        await conn.send(
            format_error(
                "remote_unavailable",
                "No key dispatcher found. Install xdotool (X11) or ydotool (Wayland).",
            )
        )
        return

    logger.debug("remote.key '%s' from %s", key, conn.id)
    ok = await _dispatch_key(key)

    if not ok and tool == "ydotool":
        logger.info("ydotool dispatch failed — attempting to start ydotoold")
        ready = await _ensure_ydotoold()
        if ready:
            await _dispatch_key(key)
        else:
            await conn.send(
                format_error(
                    "remote_unavailable",
                    "ydotoold could not be started. Check permissions on /dev/uinput.",
                )
            )
