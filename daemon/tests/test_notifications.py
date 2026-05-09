"""Tests for Android notification mirroring via notify-send."""

import asyncio
from unittest.mock import AsyncMock, MagicMock

import pytest

from jibe.core.api import JibeMessage, MessageType
from jibe.handlers.notifications import handle_notification
from jibe.network.connection import JibeConnection


def _notification_msg(**kwargs) -> JibeMessage:
    payload = {
        "type": MessageType.NOTIFICATION.value,
        "app": "com.example.app",
        "title": "Hi",
        "body": "There",
        "timestamp": 1710892800,
        **kwargs,
    }
    return JibeMessage(type=MessageType.NOTIFICATION, payload=payload)


@pytest.mark.asyncio
async def test_handle_notification_runs_notify_send(mock_ws, monkeypatch):
    """Should invoke notify-send with app name, title, and body."""
    conn = JibeConnection(mock_ws, "127.0.0.1")

    proc = MagicMock()
    proc.wait = AsyncMock(return_value=None)
    proc.returncode = 0

    captured = {}

    async def fake_exec(*args, **kwargs):
        captured["args"] = args
        captured["kwargs"] = kwargs
        return proc

    monkeypatch.setattr("jibe.handlers.notifications.asyncio.create_subprocess_exec", fake_exec)

    await handle_notification(conn, _notification_msg())

    assert captured["args"][:5] == (
        "notify-send",
        "--app-name",
        "com.example.app",
        "Hi",
        "There",
    )
    assert captured["kwargs"]["stdout"] == asyncio.subprocess.DEVNULL


@pytest.mark.asyncio
async def test_handle_notification_missing_app_errors(mock_ws, monkeypatch):
    """Invalid payloads should return a structured error and not spawn notify-send."""
    conn = JibeConnection(mock_ws, "127.0.0.1")

    exec_mock = AsyncMock()
    monkeypatch.setattr("jibe.handlers.notifications.asyncio.create_subprocess_exec", exec_mock)

    bad = JibeMessage(
        type=MessageType.NOTIFICATION,
        payload={
            "type": MessageType.NOTIFICATION.value,
            "app": "",
            "title": "x",
            "body": "y",
            "timestamp": 1,
        },
    )

    await handle_notification(conn, bad)

    exec_mock.assert_not_awaited()
    sends = [c[0][0] for c in conn.ws.send_str.await_args_list]
    assert any("malformed_payload" in s for s in sends)
