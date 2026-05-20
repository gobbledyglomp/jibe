"""Resolve tray icon paths for dev checkouts and pipx/wheel installs."""

from __future__ import annotations

import logging
from pathlib import Path

logger = logging.getLogger(__name__)

_PKG_DIR = Path(__file__).resolve().parent.parent
_BUNDLED_TRAY_ICON = _PKG_DIR / "assets" / "jibe-tray-icon.png"
_DEV_TRAY_ICON = _PKG_DIR.parent.parent / "branding" / "logos" / "jibe-icon-filled-512.png"


def default_tray_icon_path() -> Path:
    """Return the best available tray icon PNG for the current install layout."""
    if _BUNDLED_TRAY_ICON.is_file():
        return _BUNDLED_TRAY_ICON
    if _DEV_TRAY_ICON.is_file():
        return _DEV_TRAY_ICON
    logger.debug("No tray icon found; tray will use a blank placeholder")
    return _BUNDLED_TRAY_ICON
