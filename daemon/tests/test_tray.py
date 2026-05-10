"""System tray URL wiring (xdg-open)."""

from unittest.mock import MagicMock, patch

import asyncio

from jibe.core.auth import AuthManager
from jibe.ui.tray import JibeTray


def test_tray_open_dashboard_uses_xdg_open_and_loopback():
    loop = asyncio.new_event_loop()
    shutdown = asyncio.Event()
    auth = MagicMock(spec=AuthManager)

    tray = JibeTray(
        port=8765,
        loop=loop,
        shutdown_event=shutdown,
        auth_manager=auth,
    )

    with patch("jibe.ui.tray.subprocess.Popen") as popen:
        tray._open_dashboard()
        popen.assert_called_once()
        args, kwargs = popen.call_args
        assert args[0][0] == "xdg-open"
        assert args[0][1] == "http://127.0.0.1:8765/web/index.html"
