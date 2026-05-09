"""Clipboard sync between Android and Linux.

Incoming ``clipboard.sync`` messages update the local clipboard via pyperclip.
A background asyncio loop polls the clipboard and broadcasts changes to all
authenticated WebSocket peers so other devices stay in sync.
"""

from __future__ import annotations

import asyncio
import json
import logging
import pyperclip

from jibe.core.api import JibeMessage, MessageType
from jibe.network.connection import ConnectionRegistry, JibeConnection

logger = logging.getLogger(__name__)

CLIPBOARD_POLL_INTERVAL_SECONDS = 0.5


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
        try:
            while True:
                await asyncio.sleep(CLIPBOARD_POLL_INTERVAL_SECONDS)
                try:
                    current = pyperclip.paste()
                except Exception:
                    logger.exception("pyperclip.paste failed during clipboard monitor loop")
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
