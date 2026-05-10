"""Tests for clipboard sync handlers and the clipboard monitor loop."""

import asyncio
import json
from unittest.mock import AsyncMock, patch

import pytest

from jibe.core.api import JibeMessage, MessageType
from jibe.handlers.clipboard import ClipboardMonitor, handle_clipboard_sync
from jibe.network.connection import ConnectionRegistry, ConnectionState, JibeConnection


def _make_clip_msg(content: str) -> JibeMessage:
    return JibeMessage(
        type=MessageType.CLIPBOARD_SYNC,
        payload={"type": MessageType.CLIPBOARD_SYNC.value, "content": content},
    )


@pytest.mark.asyncio
async def test_handle_clipboard_sync_writes_clipboard():
    """Incoming clipboard.sync should copy content via pyperclip."""
    conn = AsyncMock(spec=JibeConnection)
    conn.id = "conn-1"
    monitor = ClipboardMonitor(ConnectionRegistry())

    with patch("jibe.handlers.clipboard.pyperclip.copy") as mock_copy:
        await handle_clipboard_sync(conn, _make_clip_msg("hello-world"), monitor)

    mock_copy.assert_called_once_with("hello-world")
    assert monitor._last_clipboard == "hello-world"


@pytest.mark.asyncio
async def test_clipboard_monitor_broadcasts_when_clipboard_changes(mock_ws):
    """When paste() returns new text, monitor sends clipboard.sync to authenticated peers."""
    registry = ConnectionRegistry()
    conn = JibeConnection(mock_ws, "127.0.0.1")
    conn.state = ConnectionState.AUTHENTICATED
    registry.add(conn)

    monitor = ClipboardMonitor(registry)

    paste_calls = 0

    def fake_paste() -> str:
        nonlocal paste_calls
        paste_calls += 1
        # First poll seeds baseline; second is unchanged; third is a real Linux-side edit.
        if paste_calls <= 2:
            return "seed-text"
        return "new-text"

    sleep_calls = 0

    async def fake_sleep(_seconds: float) -> None:
        nonlocal sleep_calls
        sleep_calls += 1
        if sleep_calls >= 4:
            raise asyncio.CancelledError()

    with patch(
        "jibe.handlers.clipboard.snapshot_plain_text_clipboard",
        side_effect=fake_paste,
    ):
        with patch("jibe.handlers.clipboard.asyncio.sleep", new=fake_sleep):
            with pytest.raises(asyncio.CancelledError):
                await monitor.run()

    assert conn.ws.send_str.await_count >= 1
    last_raw = conn.ws.send_str.await_args_list[-1][0][0]
    sent = json.loads(last_raw)
    assert sent["type"] == "clipboard.sync"
    assert sent["content"] == "new-text"


@pytest.mark.asyncio
async def test_clipboard_monitor_skips_when_unchanged(mock_ws):
    """No outbound message when paste matches last-known clipboard."""
    registry = ConnectionRegistry()
    conn = JibeConnection(mock_ws, "127.0.0.1")
    conn.state = ConnectionState.AUTHENTICATED
    registry.add(conn)

    monitor = ClipboardMonitor(registry)

    def fake_paste() -> str:
        return "same"

    sleep_calls = 0

    async def fake_sleep(_seconds: float) -> None:
        nonlocal sleep_calls
        sleep_calls += 1
        if sleep_calls >= 3:
            raise asyncio.CancelledError()

    with patch(
        "jibe.handlers.clipboard.snapshot_plain_text_clipboard",
        side_effect=fake_paste,
    ):
        with patch("jibe.handlers.clipboard.asyncio.sleep", new=fake_sleep):
            with pytest.raises(asyncio.CancelledError):
                await monitor.run()

    conn.ws.send_str.assert_not_awaited()
