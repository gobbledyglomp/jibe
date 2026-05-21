"""Clipboard sync between Android and Linux.

Incoming ``clipboard.sync`` messages update the local clipboard via pyperclip.
A background asyncio loop polls plain-text clipboard changes and broadcasts them.

Wayland ``wl-paste`` prints noisy diagnostics to stderr when the clipboard holds a
non-text MIME type (e.g. images); we read via subprocess with stderr suppressed
and skip broadcasts when no UTF-8/plain selection exists — avoids log spam and
phantom change loops.

In headless environments (Docker, SSH without X forwarding) no clipboard mechanism
is present.  The monitor detects this once on startup and exits cleanly so the
daemon doesn't spam error logs.
"""

from __future__ import annotations

import asyncio
import json
import logging
import shutil
import subprocess
import time
import pyperclip
from pyperclip import PyperclipException

from jibe.core.api import JibeMessage, MessageType
from jibe.core.db import JibeDatabase
from jibe.network.connection import ConnectionRegistry, JibeConnection

logger = logging.getLogger(__name__)

CLIPBOARD_POLL_INTERVAL_SECONDS = 0.5
REMOTE_APPLY_SUPPRESS_SECONDS = 2.0
REMOTE_APPLY_SETTLE_ATTEMPTS = 20
REMOTE_APPLY_SETTLE_DELAY_SECONDS = 0.05


def _clipboard_mechanism_available() -> bool:
    """Return True if any read/write clipboard tool is present on this system.

    Checked once at monitor startup to avoid log spam in headless environments.
    pyperclip raises ``PyperclipException`` immediately (no subprocess, no blocking)
    when no mechanism is found, so this probe is cheap.
    """
    if shutil.which("wl-paste") is not None:
        return True
    if shutil.which("xclip") is not None:
        return True
    try:
        pyperclip.paste()
        return True
    except PyperclipException:
        return False
    except Exception:
        # Unknown error — assume a mechanism exists and let the monitor handle it.
        return True


def _snapshot_via_wlpaste() -> str | None:
    wl = shutil.which("wl-paste")
    if wl is None:
        return None
    types = ("text/plain;charset=utf-8", "text/plain", "UTF8_STRING", "STRING")
    for mime in types:
        proc = subprocess.run(
            [wl, "--no-newline", "-t", mime],
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


def write_plain_text_clipboard(content: str) -> None:
    """Write plain text to the clipboard using the same stack as snapshot reads."""
    wl = shutil.which("wl-copy")
    if wl is not None:
        proc = subprocess.run(
            [wl],
            input=content,
            text=True,
            capture_output=True,
            timeout=2,
            check=False,
        )
        if proc.returncode == 0:
            return
        logger.warning(
            "wl-copy failed (exit %s): %s",
            proc.returncode,
            (proc.stderr or "").strip(),
        )

    xc = shutil.which("xclip")
    if xc is not None:
        proc = subprocess.run(
            [xc, "-selection", "clipboard"],
            input=content,
            text=True,
            capture_output=True,
            timeout=2,
            check=False,
        )
        if proc.returncode == 0:
            return
        logger.warning(
            "xclip copy failed (exit %s): %s",
            proc.returncode,
            (proc.stderr or "").strip(),
        )

    pyperclip.copy(content)


class ClipboardMonitor:
    """Polls the OS clipboard and broadcasts ``clipboard.sync`` on change."""

    def __init__(
        self,
        registry: ConnectionRegistry,
        db: JibeDatabase | None = None,
    ) -> None:
        """Initialise the monitor.

        Args:
            registry: Active connections used for outbound broadcasts.
            db: Optional persistence for outbound clipboard events.
        """
        self._registry = registry
        self._db = db
        self._last_clipboard: str | None = None
        self._suppress_broadcast_until: float = 0.0
        self._pending_remote_content: str | None = None

    def begin_remote_apply(self, content: str) -> None:
        """Mark an inbound clipboard.sync so the monitor does not echo stale text."""
        self._last_clipboard = content
        self._pending_remote_content = content
        self._suppress_broadcast_until = (
            time.monotonic() + REMOTE_APPLY_SUPPRESS_SECONDS
        )

    def sync_after_remote_push(self, content: str) -> None:
        """Align last-known clipboard after a verified remote write."""
        self._last_clipboard = content
        self._pending_remote_content = None
        self._suppress_broadcast_until = 0.0

    def _broadcast_suppressed(self) -> bool:
        return time.monotonic() < self._suppress_broadcast_until

    async def run(self) -> None:
        """Poll forever until cancelled."""
        if not await asyncio.to_thread(_clipboard_mechanism_available):
            logger.warning(
                "No clipboard mechanism found (wl-paste, xclip). "
                "Clipboard sync disabled — install xclip or wl-clipboard to enable it."
            )
            return

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

                if self._broadcast_suppressed():
                    if (
                        self._pending_remote_content is not None
                        and current == self._pending_remote_content
                    ):
                        self.sync_after_remote_push(current)
                    continue

                if self._pending_remote_content is not None:
                    self._pending_remote_content = None
                    self._last_clipboard = current
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
                if self._db is not None:
                    try:
                        await self._db.add_clipboard_event(
                            current,
                            "local",
                            "outgoing",
                        )
                    except Exception:
                        logger.exception("clipboard_history insert failed for local broadcast")
                logger.debug("Broadcast clipboard.sync (%d chars)", len(current))
        except asyncio.CancelledError:
            logger.debug("Clipboard monitor cancelled")
            raise


async def handle_clipboard_sync(
    conn: JibeConnection,
    msg: JibeMessage,
    monitor: ClipboardMonitor,
    db: JibeDatabase | None = None,
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

    monitor.begin_remote_apply(raw)
    try:
        await asyncio.to_thread(write_plain_text_clipboard, raw)
    except PyperclipException:
        logger.warning("clipboard write unavailable (headless environment) for %s", conn.id)
        return
    except Exception:
        logger.exception("clipboard write failed for connection %s", conn.id)
        return

    for _ in range(REMOTE_APPLY_SETTLE_ATTEMPTS):
        snap = await asyncio.to_thread(snapshot_plain_text_clipboard)
        if snap == raw:
            monitor.sync_after_remote_push(raw)
            break
        await asyncio.sleep(REMOTE_APPLY_SETTLE_DELAY_SECONDS)
    else:
        snap = await asyncio.to_thread(snapshot_plain_text_clipboard)
        if snap is not None:
            monitor.sync_after_remote_push(snap)
        logger.debug(
            "clipboard.sync from %s: local clipboard did not match payload after write",
            conn.id,
        )

    if db is not None and conn.device_id:
        try:
            await db.add_clipboard_event(
                raw,
                conn.device_id,
                "incoming",
            )
        except Exception:
            logger.exception("clipboard_history insert failed for %s", conn.id)
