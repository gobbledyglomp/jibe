"""System tray integration via pystray.

Linux desktops using StatusNotifier/AppIndicator may show the context menu on
single left-click and map primary activation to double-click or another gesture;
``Open Dashboard`` is always available from the menu as well.
"""

from __future__ import annotations

import asyncio
import logging
import subprocess
import threading
from pathlib import Path

from PIL import Image

from jibe.core.auth import AuthManager

logger = logging.getLogger(__name__)

_REPO_ROOT = Path(__file__).resolve().parent.parent.parent.parent
_DEFAULT_ICON = _REPO_ROOT / "branding" / "logos" / "jibe-icon-filled-512.png"


class JibeTray:
    """pystray icon: open dashboard, pairing controls, quit."""

    def __init__(
        self,
        *,
        port: int,
        loop: asyncio.AbstractEventLoop,
        shutdown_event: asyncio.Event,
        auth_manager: AuthManager,
        icon_path: Path | None = None,
    ) -> None:
        self._port = port
        self._loop = loop
        self._shutdown_event = shutdown_event
        self._auth = auth_manager
        self._icon_path = icon_path or _DEFAULT_ICON
        self._icon = None
        self._thread: threading.Thread | None = None

    def _open_dashboard(self) -> None:
        url = f"http://127.0.0.1:{self._port}/web/index.html"
        try:
            subprocess.Popen(
                ["xdg-open", url],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                start_new_session=True,
            )
        except Exception:
            logger.exception("xdg-open failed for dashboard URL %s", url)

    def _pairing_start(self) -> None:
        self._loop.call_soon_threadsafe(self._auth.start_pairing)

    def _pairing_stop(self) -> None:
        self._loop.call_soon_threadsafe(self._auth.stop_pairing)

    def start(self) -> None:
        """Run the tray icon in a background thread."""
        import pystray

        path = self._icon_path
        if not path.exists():
            logger.warning("Tray icon missing at %s — using placeholder", path)
            img = Image.new("RGBA", (64, 64), (0x58, 0xA6, 0xFF, 255))
        else:
            img = Image.open(path).convert("RGBA").resize((64, 64), Image.Resampling.LANCZOS)

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

        icon = pystray.Icon("jibe", img, "Jibe", menu=menu)
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
