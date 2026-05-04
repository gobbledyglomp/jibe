"""Tests for the message router and ping handler.

Covers handler registration, dispatch to registered handlers,
unhandled message types, handler exceptions, and the ping→pong flow.
"""

import json
from unittest.mock import AsyncMock

import pytest
from jibe.core.api import JibeMessage, MessageType
from jibe.handlers.ping import handle_ping
from jibe.handlers.router import MessageRouter


def _make_msg(msg_type: MessageType, **payload) -> JibeMessage:
    """Create a JibeMessage for testing."""
    return JibeMessage(type=msg_type, payload={"type": msg_type.value, **payload})


@pytest.fixture
def mock_conn():
    """A mock JibeConnection with send as an AsyncMock."""
    conn = AsyncMock()
    conn.id = "test-conn-id"
    conn.device_name = "Test Device"
    return conn


class TestMessageRouter:
    """Tests for MessageRouter registration and dispatch."""

    def test_register_handler(self):
        """Registering a handler should add it to registered_types."""
        router = MessageRouter()
        handler = AsyncMock()
        router.register(MessageType.PING, handler)
        assert MessageType.PING in router.registered_types

    def test_register_duplicate_raises(self):
        """Registering two handlers for the same type must raise."""
        router = MessageRouter()
        router.register(MessageType.PING, AsyncMock())
        with pytest.raises(ValueError, match="already registered"):
            router.register(MessageType.PING, AsyncMock())

    def test_registered_types_empty_by_default(self):
        """A fresh router should have no registered types."""
        router = MessageRouter()
        assert router.registered_types == []

    async def test_dispatch_calls_handler(self, mock_conn):
        """Dispatch should call the registered handler with conn and msg."""
        router = MessageRouter()
        handler = AsyncMock()
        router.register(MessageType.PING, handler)

        msg = _make_msg(MessageType.PING)
        await router.dispatch(mock_conn, msg)

        handler.assert_awaited_once_with(mock_conn, msg)

    async def test_dispatch_unhandled_sends_error(self, mock_conn):
        """Dispatching a message with no handler should send not_implemented."""
        router = MessageRouter()
        msg = _make_msg(MessageType.PONG)

        await router.dispatch(mock_conn, msg)

        mock_conn.send.assert_awaited_once()
        sent = json.loads(mock_conn.send.call_args[0][0])
        assert sent["code"] == "not_implemented"

    async def test_dispatch_handler_exception_sends_error(self, mock_conn):
        """If a handler raises, the router should send an internal_error."""
        router = MessageRouter()

        async def broken_handler(c, m):
            raise RuntimeError("oops")

        router.register(MessageType.PING, broken_handler)
        msg = _make_msg(MessageType.PING)

        await router.dispatch(mock_conn, msg)

        mock_conn.send.assert_awaited_once()
        sent = json.loads(mock_conn.send.call_args[0][0])
        assert sent["code"] == "internal_error"

    async def test_dispatch_handler_exception_does_not_crash(self, mock_conn):
        """A handler exception must not propagate to the caller."""
        router = MessageRouter()

        async def broken_handler(c, m):
            raise ValueError("handler bug")

        router.register(MessageType.PING, broken_handler)
        msg = _make_msg(MessageType.PING)

        # Should not raise
        await router.dispatch(mock_conn, msg)


class TestPingHandler:
    """Tests for the ping→pong handler."""

    async def test_ping_sends_pong(self, mock_conn):
        """handle_ping should send a pong message."""
        msg = _make_msg(MessageType.PING)
        await handle_ping(mock_conn, msg)

        mock_conn.send.assert_awaited_once()
        sent = json.loads(mock_conn.send.call_args[0][0])
        assert sent["type"] == "pong"


class TestServerPingIntegration:
    """Integration test: ping through the full WebSocket stack."""

    async def test_ping_pong_over_websocket(
        self, aiohttp_client, jibe_app, jibe_server
    ):
        """Sending ping to an authenticated connection should return pong."""
        client = await aiohttp_client(jibe_app)
        ws = await client.ws_connect("/ws")

        pin = jibe_server.auth.start_pairing()
        await ws.send_json(
            {
                "type": "auth.request",
                "pin": pin,
                "device_name": "Test Device",
            }
        )
        auth_resp = await ws.receive_json()
        assert auth_resp["accepted"] is True

        await ws.send_json({"type": "ping"})
        pong = await ws.receive_json()
        assert pong["type"] == "pong"

        await ws.close()
