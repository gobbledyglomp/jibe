"""Presentation remote handler.

Receives ``remote.key`` messages from Android and dispatches the corresponding
key event to the focused Linux window via ``xdotool`` (X11) or ``ydotool``
(Wayland).  The tool is detected once and cached for the process lifetime.

On Wayland, ``ydotool`` requires the ``ydotoold`` companion daemon (prefer the
user systemd unit from ``deploy/ydotoold.service``).  Jibe can try to start it
when the socket is missing, but uinput permissions must be configured on the host.
"""

from __future__ import annotations

import asyncio
import atexit
import logging
import os
import shutil
import signal
import time
from pathlib import Path

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
_ydotool_setup_hint_logged: bool = False
_last_ydotoold_attempt_monotonic: float = 0.0

YDOTOOLD_SETTLE_SECONDS = 0.6
YDOTOOLD_MAX_WAIT_SECONDS = 3.0
YDOTOOLD_RETRY_COOLDOWN_SECONDS = 60.0


def _ydotool_socket_path() -> Path:
    runtime = os.environ.get("XDG_RUNTIME_DIR")
    if runtime:
        return Path(runtime) / ".ydotool_socket"
    return Path(f"/run/user/{os.getuid()}") / ".ydotool_socket"


def _ydotool_socket_ready() -> bool:
    return _ydotool_socket_path().is_socket()


def _log_ydotool_setup_hint(*, ydotoold_stderr: str = "") -> None:
    global _ydotool_setup_hint_logged
    if _ydotool_setup_hint_logged:
        return
    _ydotool_setup_hint_logged = True

    detail = f" ({ydotoold_stderr.strip()})" if ydotoold_stderr.strip() else ""
    logger.warning(
        "Presentation remote on Wayland needs ydotoold with access to /dev/uinput%s.\n"
        "  1. sudo pacman -S ydotool   (or your distro equivalent)\n"
        "  2. sudo usermod -aG input $USER   then log out and back in\n"
        "  3. bash deploy/install.sh   (enables the ydotoold user service), or:\n"
        "       systemctl --user enable --now ydotoold\n"
        "  4. If /dev/uinput is missing: sudo modprobe uinput",
        detail,
    )


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


async def _try_systemd_ydotoold() -> bool:
    """Start ``ydotoold`` via the user systemd unit when install.sh registered it."""
    if shutil.which("systemctl") is None:
        return False
    try:
        proc = await asyncio.create_subprocess_exec(
            "systemctl",
            "--user",
            "start",
            "ydotoold",
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.PIPE,
        )
        _, stderr = await proc.communicate()
        if proc.returncode != 0:
            logger.debug(
                "systemctl --user start ydotoold failed (code %d): %s",
                proc.returncode,
                stderr.decode(errors="replace").strip(),
            )
            return False
        await asyncio.sleep(YDOTOOLD_SETTLE_SECONDS)
        return _ydotool_socket_ready()
    except Exception:
        logger.debug("systemctl ydotoold start failed", exc_info=True)
        return False


async def _start_ydotoold_subprocess() -> tuple[bool, str]:
    """Spawn ``ydotoold`` directly. Returns (ready, stderr_text)."""
    global _ydotoold_proc, _ydotoold_started

    ydotoold = shutil.which("ydotoold")
    if ydotoold is None:
        return False, "ydotoold not found in PATH"

    try:
        _ydotoold_proc = await asyncio.create_subprocess_exec(
            ydotoold,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.PIPE,
        )
        _ydotoold_started = True
        atexit.register(_kill_ydotoold_sync)

        await asyncio.sleep(0.25)
        if _ydotoold_proc.returncode is not None:
            stderr_bytes = (
                await _ydotoold_proc.stderr.read() if _ydotoold_proc.stderr else b""
            )
            stderr_text = stderr_bytes.decode(errors="replace").strip()
            logger.warning(
                "ydotoold exited immediately with code %d%s",
                _ydotoold_proc.returncode,
                f": {stderr_text}" if stderr_text else "",
            )
            _ydotoold_started = False
            return False, stderr_text

        logger.info("Started ydotoold (PID %d) for presentation remote", _ydotoold_proc.pid)
        await asyncio.sleep(YDOTOOLD_SETTLE_SECONDS)

        elapsed = YDOTOOLD_SETTLE_SECONDS
        stderr_text = ""
        while elapsed < YDOTOOLD_MAX_WAIT_SECONDS:
            if _ydotoold_proc.returncode is not None:
                _ydotoold_started = False
                return False, stderr_text
            if _ydotool_socket_ready():
                return True, stderr_text
            probe = await asyncio.create_subprocess_exec(
                "ydotool",
                "key",
                "0:0",
                stdout=asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.PIPE,
            )
            _, probe_err = await probe.communicate()
            if probe.returncode == 0:
                return True, stderr_text
            stderr_text = probe_err.decode(errors="replace").strip() or stderr_text
            await asyncio.sleep(0.3)
            elapsed += 0.3

        return _ydotool_socket_ready(), stderr_text
    except Exception:
        logger.exception("Failed to start ydotoold")
        _ydotoold_started = False
        return False, ""


async def _ensure_ydotoold() -> bool:
    """Ensure ``ydotoold`` is running and the socket is reachable."""
    global _last_ydotoold_attempt_monotonic

    if _ydotool_socket_ready():
        return True

    now = time.monotonic()
    if now - _last_ydotoold_attempt_monotonic < YDOTOOLD_RETRY_COOLDOWN_SECONDS:
        return False
    _last_ydotoold_attempt_monotonic = now

    if _ydotoold_started and _ydotoold_proc is not None and _ydotoold_proc.returncode is None:
        return _ydotool_socket_ready()

    if await _try_systemd_ydotoold():
        return True

    ready, stderr = await _start_ydotoold_subprocess()
    if not ready:
        _log_ydotool_setup_hint(ydotoold_stderr=stderr)
    return ready


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

    if tool == "ydotool" and not _ydotool_socket_ready():
        await _ensure_ydotoold()

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
        logger.info("ydotool dispatch failed — ensuring ydotoold is running")
        ready = await _ensure_ydotoold()
        if ready:
            ok = await _dispatch_key(key)
        if not ok:
            await conn.send(
                format_error(
                    "remote_unavailable",
                    "ydotoold is not available. See daemon logs for setup steps "
                    "(input group, systemctl --user start ydotoold).",
                )
            )
