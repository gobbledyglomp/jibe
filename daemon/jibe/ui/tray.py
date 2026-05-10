"""System tray integration via pystray.

Backend selection order on Linux:
  1. AppIndicator (D-Bus / SNI) — required for KDE Plasma 6 and GNOME
  2. GTK StatusIcon — legacy fallback
  3. XOrg XEmbed — last resort (modern desktops may not show menus)
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


def _make_tray_image(path: Path) -> Image.Image:
    """Return a 64×64 white-on-transparent icon suitable for the system tray.

    The source PNG has a solid dark background (#121824) with white logo
    strokes.  We use the luminance of each pixel as its alpha value:
    bright (stroke) pixels become opaque white; dark (background) pixels
    become fully transparent.
    """
    if path.exists():
        src = Image.open(path).convert("RGBA")
        lum = src.convert("L")  # background ≈ 25 brightness, strokes ≈ 255
        result = Image.new("RGBA", src.size, (255, 255, 255, 0))
        result.putalpha(lum)
        return result.resize((64, 64), Image.Resampling.LANCZOS)
    logger.warning("Tray icon not found at %s — using blank placeholder", path)
    return Image.new("RGBA", (64, 64), (0, 0, 0, 0))


def _best_icon_class():  # type: ignore[return]
    """Return the most capable pystray Icon class for this environment.

    AppIndicator communicates via D-Bus and implements the StatusNotifier
    protocol used by KDE Plasma 6 and modern GNOME.  Fall back to GTK
    StatusIcon (deprecated but broadly supported), then to xorg XEmbed.
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
        """Load the icon and run the tray in a background thread."""
        import pystray  # noqa: PLC0415 — intentional lazy import

        img = _make_tray_image(self._icon_path)
        IconClass = _best_icon_class()

        def on_quit(icon: pystray.Icon, _item: pystray.MenuItem) -> None:
            self._loop.call_soon_threadsafe(self._shutdown_event.set)
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

        self._thread = threading.Thread(target=icon.run, name="jibe-tray", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        """Stop the tray icon if running."""
        if self._icon is not None:
            try:
                self._icon.stop()
            except Exception:
                logger.debug("tray icon stop", exc_info=True)
            self._icon = None
