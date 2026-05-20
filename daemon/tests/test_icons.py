"""Tray icon path resolution for pipx and dev layouts."""

from pathlib import Path

from jibe.ui.icons import default_tray_icon_path


def test_default_tray_icon_prefers_bundled_asset():
    path = default_tray_icon_path()
    assert path.is_file(), f"expected bundled tray icon at {path}"
    assert path.name == "jibe-tray-icon.png"
    assert "assets" in path.parts


def test_bundled_tray_icon_lives_under_package():
    pkg = Path(__file__).resolve().parent.parent / "jibe"
    bundled = pkg / "assets" / "jibe-tray-icon.png"
    assert bundled.is_file()
