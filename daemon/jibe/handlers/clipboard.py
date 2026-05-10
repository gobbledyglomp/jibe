"""Clipboard sync between Android and Linux.

Incoming ``clipboard.sync`` messages update the local clipboard via pyperclip.
A background asyncio loop polls plain-text clipboard changes and broadcasts them.

Wayland ``wl-paste`` prints noisy diagnostics to stderr when the clipboard holds a
non-text MIME type (e.g. images); we read via subprocess with stderr suppressed
and skip broadcasts when no UTF-8/plain selection exists — avoids log spam and
phantom change loops.
"""

from __future__ import annotations

import asyncio
import json
import logging
import shutil
import subprocess
import pyperclip

from jibe.core.api import JibeMessage, MessageType
from jibe.network.connection import ConnectionRegistry, JibeConnection

logger = logging.getLogger(__name__)

CLIPBOARD_POLL_INTERVAL_SECONDS = 0.5


def _snapshot_via_wlpaste() -> str | None:
    wl = shutil.which("wl-paste")
    if wl is None:
        return None
    types = ("text/plain;charset=utf-8", "text/plain", "UTF8_STRING", "STRING")
    for mime in types:
        proc = subprocess.run(
            [wl, "-t", mime],
            capture_output=True,
            text=True,
            timeout=2,
            check=False,
        )
        if proc.returncode != 0:
            continue
        text = proc.stdout
        return text if text != "" else ""
    return None


def _snapshot_via_xclip() -> str | None:
    xc = shutil.which("xclip")
    if xc is None:
        return None
    proc = subprocess.run(
        [xc, "-selection", "clipboard", "-o"],
        capture_output=True,
        text=True,
        timeout=2,
        check=False,
    )
    if proc.returncode != 0:
        return None
    text = proc.stdout
    return text if text != "" else ""


def snapshot_plain_text_clipboard() -> str | None:
    """Best-effort read of plain-text clipboard contents.

    Returns ``None`` when no readable plain-text selection exists (including image-only
    clipboards). Empty string is a valid value meaning ``""`` was copied explicitly.
    """
    if shutil.which("wl-paste") is not None:
        # Never fall through to pyperclip on Wayland — it shells out to wl-paste too and
        # duplicates noisy stderr when the selection is non-text.
        return _snapshot_via_wlpaste()

    snap = _snapshot_via_xclip()
    if snap is not None:
        return snap

    try:
        text = pyperclip.paste()
    except Exception:
        logger.exception("pyperclip.paste failed during clipboard snapshot")
        return None

    return text


class ClipboardMonitor:
    """Polls the OS clipboard and broadcasts ``clipboard.sync`` on change."""

    def __init__(self, registry: ConnectionRegistry) -> None:
        """Initialise the monitor.

        Args:
            registry: Active connections used for outbound broadcasts.
        """
        self._registry = registry
        self._last_clipboard: str | None = None

    def sync_after_remote_push(self, content: str) -> None:
        """Record clipboard contents applied from the wire before calling ``pyperclip.copy``.

        Prevents the polling loop from treating that write as a new local edit and
        broadcasting it again.

        Args:
            content: Exact string written to the clipboard.
        """
        self._last_clipboard = content

    async def run(self) -> None:
        """Poll forever until cancelled."""
        baseline_ready = False
        try:
            while True:
                await asyncio.sleep(CLIPBOARD_POLL_INTERVAL_SECONDS)
                current = await asyncio.to_thread(snapshot_plain_text_clipboard)
                if current is None:
                    continue

                if not baseline_ready:
                    self._last_clipboard = current
                    baseline_ready = True
                    continue

                if current == self._last_clipboard:
                    continue

                self._last_clipboard = current
                payload = json.dumps(
                    {
                        "type": MessageType.CLIPBOARD_SYNC.value,
                        "content": current,
                    }
                )
                await self._registry.broadcast_json_to_authenticated(payload)
                logger.debug("Broadcast clipboard.sync (%d chars)", len(current))
        except asyncio.CancelledError:
            logger.debug("Clipboard monitor cancelled")
            raise


async def handle_clipboard_sync(
    conn: JibeConnection,
    msg: JibeMessage,
    monitor: ClipboardMonitor,
) -> None:
    """Apply ``clipboard.sync`` from a peer to the local clipboard.

    Args:
        conn: Authenticated connection that sent the message.
        msg: Parsed message; expects ``content`` string field.
        monitor: Shared monitor whose last-known value must stay aligned.
    """
    raw = msg.payload.get("content")
    if not isinstance(raw, str):
        logger.warning("clipboard.sync missing string content from %s", conn.id)
        return

    monitor.sync_after_remote_push(raw)
    try:
        pyperclip.copy(raw)
    except Exception:
        logger.exception("pyperclip.copy failed for connection %s", conn.id)
