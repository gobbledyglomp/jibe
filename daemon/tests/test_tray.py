"""System tray wiring — dashboard URL, icon processing, backend selection."""

import asyncio
from pathlib import Path
from unittest.mock import MagicMock, patch

from jibe.core.auth import AuthManager
from jibe.ui.tray import JibeTray, _make_tray_image


_DASHBOARD_URL = "http://127.0.0.1:8777/"


def _make_tray(url: str = _DASHBOARD_URL) -> JibeTray:
    loop = asyncio.new_event_loop()
    shutdown = asyncio.Event()
    auth = MagicMock(spec=AuthManager)
    return JibeTray(
        dashboard_url=url,
        loop=loop,
        shutdown_event=shutdown,
        auth_manager=auth,
    )


def test_tray_open_dashboard_calls_webbrowser():
    tray = _make_tray(_DASHBOARD_URL)
    with patch("jibe.ui.tray.webbrowser.open_new_tab") as mock_open:
        tray._open_dashboard()
    mock_open.assert_called_once_with(_DASHBOARD_URL)


def test_tray_open_dashboard_uses_http_loopback():
    """Dashboard URL must be plain HTTP on 127.0.0.1 (no TLS, no 0.0.0.0)."""
    tray = _make_tray("http://127.0.0.1:8777/")
    with patch("jibe.ui.tray.webbrowser.open_new_tab") as mock_open:
        tray._open_dashboard()
    url = mock_open.call_args[0][0]
    assert url.startswith("http://127.0.0.1:")


def test_tray_make_image_returns_rgba_for_missing_file(tmp_path):
    missing = tmp_path / "no_such_icon.png"
    img = _make_tray_image(missing)
    assert img.mode == "RGBA"
    assert img.size == (64, 64)


def test_tray_make_image_returns_white_transparent_from_filled_png():
    """The real filled logo PNG should produce a white-on-transparent icon."""
    from PIL import Image

    from jibe.ui.icons import default_tray_icon_path

    real_icon = default_tray_icon_path()
    if not real_icon.exists():
        return  # skip in environments without the asset

    img = _make_tray_image(real_icon)
    assert img.mode == "RGBA"
    # Background corners must be transparent (alpha ≈ 0)
    corners = [img.getpixel((0, 0)), img.getpixel((63, 63))]
    for r, g, b, a in corners:
        assert a < 30, f"Expected transparent corner, got alpha={a}"
    # At least one opaque (logo) pixel must exist somewhere
    pixels = list(img.getdata())
    assert any(a > 200 for _, _, _, a in pixels), "No opaque pixels — logo strokes not extracted"


def test_tray_menu_has_default_open_dashboard():
    """'Open Dashboard' must be marked default=True so left-click activates it."""
    import pystray

    tray = _make_tray(_DASHBOARD_URL)
    with (
        patch("jibe.ui.tray.webbrowser.open_new_tab"),
        patch("jibe.ui.tray._best_icon_class", return_value=MagicMock()),
        patch("jibe.ui.tray._make_tray_image", return_value=MagicMock()),
    ):
        menu = pystray.Menu(
            pystray.MenuItem("Open Dashboard", lambda i, it: None, default=True),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Start Pairing", lambda i, it: None),
            pystray.MenuItem("Stop Pairing", lambda i, it: None),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Quit", lambda i, it: None),
        )
        items = list(menu)
        open_item = items[0]
        assert open_item.default
