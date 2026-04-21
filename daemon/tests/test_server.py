"""Integration tests for the aiohttp WebSocket and HTTP server.

These tests use ``aiohttp_client`` (from pytest-aiohttp) to spin up a
lightweight test server for each test — no real TCP port is opened.
This lets us exercise the full WebSocket and HTTP handling code path
without touching the network.
"""

import json

import pytest
from aiohttp import WSMsgType

from jibe import __version__
from jibe.api import MessageType


# ── HTTP Health Endpoint ─────────────────────────────────────────────────

async def test_health_returns_status_and_version(aiohttp_client, jibe_app):
    """GET / should return running status and the current version."""
    client = await aiohttp_client(jibe_app)
    resp = await client.get("/")

    assert resp.status == 200

    data = await resp.json()
    assert data["status"] == "running"
    assert data["version"] == __version__


async def test_health_content_type_is_json(aiohttp_client, jibe_app):
    """GET / should set the correct Content-Type header."""
    client = await aiohttp_client(jibe_app)
    resp = await client.get("/")

    assert "application/json" in resp.headers["Content-Type"]


# ── WebSocket: Valid Messages ────────────────────────────────────────────

async def test_ws_accepts_valid_message(aiohttp_client, jibe_app):
    """Sending a valid protocol message should not trigger an error."""
    client = await aiohttp_client(jibe_app)
    ws = await client.ws_connect("/ws")

    await ws.send_json({"type": "ping"})

    # The server currently logs but does not respond to valid messages.
    # Closing the connection cleanly proves the server didn't crash.
    await ws.close()
    assert ws.closed


async def test_ws_accepts_all_message_types(aiohttp_client, jibe_app, valid_messages):
    """Every message type defined in the protocol should be accepted."""
    client = await aiohttp_client(jibe_app)
    ws = await client.ws_connect("/ws")

    for msg_type in MessageType:
        payload = valid_messages[msg_type.value]
        await ws.send_json(payload)

    await ws.close()
    assert ws.closed


# ── WebSocket: Error Responses ───────────────────────────────────────────

async def test_ws_malformed_json_returns_error(aiohttp_client, jibe_app):
    """Sending invalid JSON should return a malformed_json error."""
    client = await aiohttp_client(jibe_app)
    ws = await client.ws_connect("/ws")

    await ws.send_str("{not valid json}")
    resp = await ws.receive_json()

    assert resp["type"] == "error"
    assert resp["code"] == "malformed_json"

    await ws.close()


async def test_ws_unknown_type_returns_error(aiohttp_client, jibe_app):
    """Sending an unknown message type should return an unknown_type error."""
    client = await aiohttp_client(jibe_app)
    ws = await client.ws_connect("/ws")

    await ws.send_json({"type": "does.not.exist"})
    resp = await ws.receive_json()

    assert resp["type"] == "error"
    assert resp["code"] == "unknown_type"

    await ws.close()


async def test_ws_missing_type_returns_error(aiohttp_client, jibe_app):
    """Sending a JSON object without a 'type' field should return an error."""
    client = await aiohttp_client(jibe_app)
    ws = await client.ws_connect("/ws")

    await ws.send_json({"pin": "123456"})
    resp = await ws.receive_json()

    assert resp["type"] == "error"
    assert resp["code"] == "malformed_json"
    assert "Missing 'type' field" in resp["message"]

    await ws.close()


async def test_ws_empty_message_returns_error(aiohttp_client, jibe_app):
    """Sending an empty string should return a malformed_json error."""
    client = await aiohttp_client(jibe_app)
    ws = await client.ws_connect("/ws")

    await ws.send_str("")
    resp = await ws.receive_json()

    assert resp["type"] == "error"
    assert resp["code"] == "malformed_json"

    await ws.close()


# ── WebSocket: Connection Lifecycle ──────────────────────────────────────

async def test_ws_multiple_messages_same_connection(aiohttp_client, jibe_app):
    """A single WebSocket connection should handle multiple messages."""
    client = await aiohttp_client(jibe_app)
    ws = await client.ws_connect("/ws")

    # First: a valid message (no response expected)
    await ws.send_json({"type": "ping"})

    # Second: an invalid message (error response expected)
    await ws.send_str("broken")
    resp = await ws.receive_json()
    assert resp["code"] == "malformed_json"

    # Third: another valid message to prove the connection survived
    await ws.send_json({"type": "pong"})

    await ws.close()
    assert ws.closed


async def test_ws_clean_disconnect(aiohttp_client, jibe_app):
    """The server should handle clean client disconnection gracefully."""
    client = await aiohttp_client(jibe_app)
    ws = await client.ws_connect("/ws")

    await ws.close()
    assert ws.closed


# ── HTTP: Non-existent Routes ────────────────────────────────────────────

async def test_unknown_route_returns_404(aiohttp_client, jibe_app):
    """Requesting a non-existent path should return 404."""
    client = await aiohttp_client(jibe_app)
    resp = await client.get("/nonexistent")

    assert resp.status == 404
