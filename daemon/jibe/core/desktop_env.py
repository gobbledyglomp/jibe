"""Discover graphical session environment for tray and desktop integrations.

systemd user units often start without ``DISPLAY`` / ``WAYLAND_DISPLAY`` when
they are ordered only after ``network-online.target``, or after
``systemctl --user restart`` without a full graphical login context.  Tray
backends (pystray AppIndicator) also need ``DBUS_SESSION_BUS_ADDRESS`` and
``XDG_RUNTIME_DIR``.
"""

from __future__ import annotations

import logging
import os
from pathlib import Path

logger = logging.getLogger(__name__)

_DESKTOP_ENV_KEYS = (
    "WAYLAND_DISPLAY",
    "DISPLAY",
    "DBUS_SESSION_BUS_ADDRESS",
    "XDG_RUNTIME_DIR",
    "XDG_SESSION_TYPE",
)


def _runtime_dir() -> Path | None:
    explicit = os.environ.get("XDG_RUNTIME_DIR")
    if explicit:
        path = Path(explicit)
        if path.is_dir():
            return path
    fallback = Path(f"/run/user/{os.getuid()}")
    if fallback.is_dir():
        return fallback
    return None


def _setdefault_env(key: str, value: str) -> None:
    if key not in os.environ:
        os.environ[key] = value
        logger.debug("desktop env: set %s from session discovery", key)


def bootstrap_desktop_environment() -> None:
    """Fill missing display/D-Bus variables from the active user session.

    Safe to call multiple times.  Does not override variables already set in
    the process environment.
    """
    runtime = _runtime_dir()
    if runtime is not None:
        _setdefault_env("XDG_RUNTIME_DIR", str(runtime))

    if runtime is not None and not os.environ.get("DBUS_SESSION_BUS_ADDRESS"):
        bus = runtime / "bus"
        if bus.exists():
            _setdefault_env("DBUS_SESSION_BUS_ADDRESS", f"unix:path={bus}")

    if not os.environ.get("WAYLAND_DISPLAY") and runtime is not None:
        for sock in sorted(runtime.glob("wayland-*")):
            if sock.is_socket():
                _setdefault_env("WAYLAND_DISPLAY", sock.name)
                break

    if not os.environ.get("DISPLAY") and runtime is not None:
        # Xwayland / legacy X11: .X11-display or numeric X sockets under runtime.
        marker = runtime / ".X11-display"
        if marker.is_file():
            display = marker.read_text(encoding="utf-8", errors="replace").strip()
            if display:
                _setdefault_env("DISPLAY", display)
        for sock in sorted(runtime.glob("X*")):
            if sock.is_socket() and sock.name.startswith("X") and sock.name[1:].isdigit():
                _setdefault_env("DISPLAY", f":{sock.name[1:]}")
                break


def have_desktop_session() -> bool:
    """Return whether a graphical session is available for tray/UI."""
    bootstrap_desktop_environment()
    return bool(os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY"))


def desktop_env_summary() -> str:
    """Short summary of relevant env vars (for debug logs)."""
    parts = [f"{k}={os.environ[k]!r}" for k in _DESKTOP_ENV_KEYS if os.environ.get(k)]
    return ", ".join(parts) if parts else "(no desktop session env)"
