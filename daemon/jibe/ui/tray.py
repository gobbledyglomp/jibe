"""System tray integration via pystray.

GTK 3 / Ayatana AppIndicator require every Gtk/AppIndicator call *and* the
GLib main loop to run on the **same OS thread**.  pystray documents
``Icon.run()`` as blocking — we satisfy GTK by performing the **lazy** pystray
import, Icon construction, and ``run()`` entirely inside a dedicated *tray*
thread so nothing initialises GTK on the asyncio thread.

Backend selection order on Linux:
  1. AppIndicator (D-Bus / SNI) — KDE Plasma 6 and GNOME
  2. GTK StatusIcon — legacy fallback
  3. XOrg XEmbed — last resort
"""

from __future__ import annotations

import asyncio
import importlib
import logging
import threading
import webbrowser
from pathlib import Path

from PIL import Image

from jibe.core.auth import AuthManager

logger = logging.getLogger(__name__)

_REPO_ROOT = Path(__file__).resolve().parent.parent.parent.parent
_DEFAULT_ICON = _REPO_ROOT / "branding" / "logos" / "jibe-icon-filled-512.png"

_TRAY_ICON_SIZE = 64
"""RGBA PNG size written for StatusNotifier / Gtk (px)."""

_BG_LUM_THRESHOLD = 80
"""Pixels darker than this (0–255) become fully transparent."""

_STOP_JOIN_TIMEOUT_SEC = 12.0


def _make_tray_image(path: Path) -> Image.Image:
    """Return a white-on-transparent PNG-ready image for the tray.

    The source asset is white strokes on a dark background.  We binarise by
    luminance so the panel background shows through, and we store **black**
    RGB with alpha 0 for transparent pixels.  Some compositors ignore alpha and
    only paint RGB — white RGB there becomes a solid square; (0, 0, 0, 0)
    avoids that artefact.
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


def _best_icon_class():  # type: ignore[return]
    """Return the pystray Icon implementation for this OS session.

    Must be invoked from the **tray** thread before GTK is initialised on any
    other thread.
    """
    for backend in ("_appindicator", "_gtk"):
        try:
            mod = importlib.import_module(f"pystray.{backend}")
            cls = getattr(mod, "Icon")
            logger.debug("pystray backend: %s", backend)
            return cls
        except Exception:
            pass
    import pystray

    logger.debug("pystray backend: default (xorg)")
    return pystray.Icon


class JibeTray:
    """pystray icon: open dashboard, pairing controls, quit."""

    def __init__(
        self,
        *,
        dashboard_url: str,
        loop: asyncio.AbstractEventLoop,
        shutdown_event: asyncio.Event,
        auth_manager: AuthManager,
        icon_path: Path | None = None,
    ) -> None:
        self._dashboard_url = dashboard_url
        self._loop = loop
        self._shutdown_event = shutdown_event
        self._auth = auth_manager
        self._icon_path = icon_path or _DEFAULT_ICON
        self._icon = None
        self._thread: threading.Thread | None = None

    def _open_dashboard(self) -> None:
        try:
            webbrowser.open_new_tab(self._dashboard_url)
        except Exception:
            logger.exception("Failed to open dashboard URL %s", self._dashboard_url)

    def _pairing_start(self) -> None:
        self._loop.call_soon_threadsafe(self._auth.start_pairing)

    def _pairing_stop(self) -> None:
        self._loop.call_soon_threadsafe(self._auth.stop_pairing)

    def start(self) -> None:
        """Load the icon and run the tray (blocking GLib loop) on its own thread."""

        if self._thread is not None:
            return

        loop = self._loop
        shutdown_event = self._shutdown_event

        def tray_worker() -> None:
            import pystray  # noqa: PLC0415 — must run on tray thread (GTK init)

            img = _make_tray_image(self._icon_path)
            IconClass = _best_icon_class()

            def on_quit(icon: pystray.Icon, _item: pystray.MenuItem) -> None:
                loop.call_soon_threadsafe(shutdown_event.set)
                icon.stop()

            menu = pystray.Menu(
                pystray.MenuItem(
                    "Open Dashboard",
                    lambda _icon, _item: self._open_dashboard(),
                    default=True,
                ),
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
            )

            icon = IconClass("jibe", img, "Jibe", menu=menu)
            self._icon = icon
            try:
                icon.run()
            finally:
                self._icon = None

        self._thread = threading.Thread(target=tray_worker, name="jibe-tray", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        """Stop the tray icon if running."""
        icon = self._icon
        if icon is not None:
            try:
                icon.stop()
            except Exception:
                logger.debug("tray icon stop", exc_info=True)
        if self._thread is not None:
            self._thread.join(timeout=_STOP_JOIN_TIMEOUT_SEC)
            self._thread = None
