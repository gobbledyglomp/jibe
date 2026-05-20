"""System tray integration via pystray.

GTK 3 / Ayatana AppIndicator require every Gtk/AppIndicator call *and* the
GLib main loop to run on the **same OS thread**.  pystray documents
``Icon.run()`` as blocking — we satisfy GTK by performing the **lazy** pystray
import, Icon construction, and ``run()`` entirely inside a dedicated *tray*
thread so nothing initialises GTK on the asyncio thread.

Backend selection order on Linux:
  1. AppIndicator (D-Bus / SNI) — KDE Plasma 6 and GNOME (requires gi)
  2. GTK StatusIcon — legacy X11 fallback (requires gi)
  3. XOrg XEmbed — only on X11, never on Wayland

``gi`` (PyGObject) ships as a system package and is intentionally excluded
from virtualenvs.  ``_ensure_gi_importable()`` adds the system site-packages
directory to ``sys.path`` once, inside the tray thread, so the AppIndicator
and GTK backends can load without recreating the venv with
``--system-site-packages``.
"""

from __future__ import annotations

import asyncio
import importlib
import logging
import os
import sys
import threading
import webbrowser
from pathlib import Path

from PIL import Image

from collections.abc import Callable

from jibe.core.auth import AuthManager
from jibe.ui.icons import default_tray_icon_path

logger = logging.getLogger(__name__)

_DEFAULT_ICON = default_tray_icon_path()

_TRAY_ICON_SIZE = 64
"""RGBA PNG size passed to StatusNotifier / Gtk (px)."""

_BG_LUM_THRESHOLD = 80
"""Pixels darker than this (0–255 luminance) become fully transparent."""

_STOP_JOIN_TIMEOUT_SEC = 12.0


# ---------------------------------------------------------------------------
# Icon image helpers
# ---------------------------------------------------------------------------

def _make_tray_image(path: Path) -> Image.Image:
    """Return a white-on-transparent PNG-ready image for the tray.

    The source asset is white strokes on a dark background.  We threshold by
    luminance: dark pixels → ``(0, 0, 0, 0)`` (fully transparent), bright
    pixels → opaque white.  Using black RGB with zero alpha for background
    pixels avoids a common compositor artifact where the compositor ignores
    alpha and paints a solid white square.
    """
    if path.exists():
        src = Image.open(path).convert("RGBA")
        lum = src.convert("L")
        w, h = src.size
        hi = 255 - _BG_LUM_THRESHOLD
        out_px: list[tuple[int, int, int, int]] = []
        for lum_v in lum.getdata():
            if lum_v <= _BG_LUM_THRESHOLD:
                out_px.append((0, 0, 0, 0))
            else:
                alpha = min(255, max(0, (lum_v - _BG_LUM_THRESHOLD) * 255 // hi))
                out_px.append((255, 255, 255, alpha))
        result = Image.new("RGBA", (w, h))
        result.putdata(out_px)
        return result.resize((_TRAY_ICON_SIZE, _TRAY_ICON_SIZE), Image.Resampling.LANCZOS)

    logger.warning("Tray icon not found at %s — using blank placeholder", path)
    return Image.new("RGBA", (_TRAY_ICON_SIZE, _TRAY_ICON_SIZE), (0, 0, 0, 0))


# ---------------------------------------------------------------------------
# Backend selection helpers
# ---------------------------------------------------------------------------

def _ensure_gi_importable() -> None:
    """Inject the system gi (PyGObject) path when running inside a venv.

    PyGObject is a compiled C extension delivered as an OS package.  It is
    intentionally absent from virtualenvs created without
    ``--system-site-packages``.  We probe the standard system site-packages
    directory for the running Python version and prepend it to ``sys.path``
    exactly once so pystray's GTK / AppIndicator backends can import ``gi``.
    """
    try:
        import gi as _gi  # noqa: F401
        return  # already importable
    except ImportError:
        pass

    ver = sys.version_info
    candidates = [
        f"/usr/lib/python{ver.major}.{ver.minor}/site-packages",
        f"/usr/local/lib/python{ver.major}.{ver.minor}/site-packages",
        "/usr/lib/python3/dist-packages",
        "/usr/local/lib/python3/dist-packages",
    ]
    for path in candidates:
        if path in sys.path:
            continue
        sys.path.insert(0, path)
        try:
            import gi as _gi  # noqa: F401
            logger.debug("gi found via system path: %s", path)
            return
        except ImportError:
            sys.path.remove(path)

    logger.warning(
        "gi (PyGObject) not found — install python-gobject system package "
        "for a fully functional tray icon."
    )


def _on_wayland() -> bool:
    return bool(os.environ.get("WAYLAND_DISPLAY"))


def _best_icon_class():
    """Return the pystray Icon class best suited to the current session.

    Must be called from the tray thread (GTK must initialise there).
    Returns ``None`` when no suitable backend is available (Wayland without
    gi, or headless environment) — callers must handle that case.
    """
    _ensure_gi_importable()

    for backend in ("_appindicator", "_gtk"):
        try:
            mod = importlib.import_module(f"pystray.{backend}")
            cls = getattr(mod, "Icon")
            logger.debug("pystray backend: %s", backend)
            return cls
        except Exception:
            pass

    if _on_wayland():
        logger.error(
            "No AppIndicator/GTK backend available on Wayland — "
            "tray icon disabled.  Install the 'python-gobject' system package "
            "and ensure libayatana-appindicator3 or libappindicator3 is present."
        )
        return None

    import pystray
    logger.debug("pystray backend: default (xorg)")
    return pystray.Icon


# ---------------------------------------------------------------------------
# JibeTray
# ---------------------------------------------------------------------------

class JibeTray:
    """pystray icon: open dashboard, pairing controls, ring phone, quit."""

    def __init__(
        self,
        *,
        dashboard_url: str,
        loop: asyncio.AbstractEventLoop,
        shutdown_event: asyncio.Event,
        auth_manager: AuthManager,
        icon_path: Path | None = None,
        get_battery_fn: "Callable[[], dict] | None" = None,
        ring_fn: "Callable[[], None] | None" = None,
    ) -> None:
        self._dashboard_url = dashboard_url
        self._loop = loop
        self._shutdown_event = shutdown_event
        self._auth = auth_manager
        self._icon_path = icon_path or _DEFAULT_ICON
        self._icon = None
        self._thread: threading.Thread | None = None
        self._get_battery = get_battery_fn
        self._ring_fn = ring_fn

    def _open_dashboard(self) -> None:
        try:
            webbrowser.open_new_tab(self._dashboard_url)
        except Exception:
            logger.exception("Failed to open dashboard URL %s", self._dashboard_url)

    def _pairing_start(self) -> None:
        self._loop.call_soon_threadsafe(self._auth.start_pairing)

    def _pairing_stop(self) -> None:
        self._loop.call_soon_threadsafe(self._auth.stop_pairing)

    def _do_ring(self) -> None:
        if self._ring_fn is not None:
            self._ring_fn()

    def _battery_label(self) -> str:
        """Build a human-readable battery string for the menu, or empty string."""
        if self._get_battery is None:
            return ""
        batteries = self._get_battery()
        if not batteries:
            return ""
        parts: list[str] = []
        for info in batteries.values():
            level = info.get("level", "?")
            charging = info.get("charging", False)
            icon = "⚡" if charging else "🔋"
            parts.append(f"{level}% {icon}")
        return " | ".join(parts)

    def start(self) -> None:
        """Load the icon and run the GLib/tray event loop on a dedicated thread."""
        if self._thread is not None:
            return

        loop = self._loop
        shutdown_event = self._shutdown_event

        def tray_worker() -> None:
            # All gi / pystray imports happen here — GTK must never be
            # initialised on the asyncio thread.
            import pystray  # noqa: PLC0415

            IconClass = _best_icon_class()
            if IconClass is None:
                return  # backend unavailable; exit silently (already logged)

            img = _make_tray_image(self._icon_path)

            def on_quit(icon: pystray.Icon, _item: pystray.MenuItem) -> None:
                loop.call_soon_threadsafe(shutdown_event.set)
                icon.stop()

            get_battery = self._get_battery
            ring_fn = self._ring_fn

            menu_items: list = [
                pystray.MenuItem(
                    "Open Dashboard",
                    lambda _icon, _item: self._open_dashboard(),
                    default=True,
                ),
            ]

            if get_battery is not None:

                def _battery_menu_title(_item: object) -> str:
                    label = self._battery_label()
                    return f"Battery: {label}" if label else "Battery: —"

                menu_items.append(pystray.Menu.SEPARATOR)
                menu_items.append(pystray.MenuItem(_battery_menu_title, None, enabled=False))

            if ring_fn is not None:
                menu_items.append(pystray.Menu.SEPARATOR)
                menu_items.append(
                    pystray.MenuItem(
                        "Ring phone",
                        lambda _icon, _item: self._do_ring(),
                    )
                )

            menu_items += [
                pystray.Menu.SEPARATOR,
                pystray.MenuItem(
                    "Start Pairing",
                    lambda _icon, _item: self._pairing_start(),
                ),
                pystray.MenuItem(
                    "Stop Pairing",
                    lambda _icon, _item: self._pairing_stop(),
                ),
                pystray.Menu.SEPARATOR,
                pystray.MenuItem("Quit", on_quit),
            ]

            menu = pystray.Menu(*menu_items)

            icon = IconClass("jibe", img, "Jibe", menu=menu)
            self._icon = icon
            try:
                icon.run()
            finally:
                self._icon = None

        self._thread = threading.Thread(target=tray_worker, name="jibe-tray", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        """Stop the tray icon and join the tray thread."""
        icon = self._icon
        if icon is not None:
            try:
                icon.stop()
            except Exception:
                logger.debug("tray icon stop", exc_info=True)
        if self._thread is not None:
            self._thread.join(timeout=_STOP_JOIN_TIMEOUT_SEC)
            self._thread = None
