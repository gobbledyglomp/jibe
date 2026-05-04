"""Integration tests for the aiohttp WebSocket and HTTP server.

These tests use ``aiohttp_client`` (from pytest-aiohttp) to spin up a
lightweight test server for each test — no real TCP port is opened.
This lets us exercise the full WebSocket and HTTP handling code path
without touching the network.

Now that the server enforces auth-first, these tests cover both
unauthenticated and authenticated paths through the state machine.
"""

from jibe import __version__

# ── Helpers ──────────────────────────────────────────────────────────────


async def _authenticate(ws, jibe_server):
    """Pair a device over the WebSocket and return the auth response.

    Activates pairing mode, sends the correct PIN, and verifies
    acceptance. This is the standard way to get an authenticated
    connection in tests.
    """
    pin = jibe_server.auth.start_pairing()
    await ws.send_json(
        {
            "type": "auth.request",
            "pin": pin,
            "device_name": "Test Device",
        }
    )
    resp = await ws.receive_json()
    assert resp["accepted"] is True
    return resp


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


async def test_health_reports_connected_devices(aiohttp_client, jibe_app, jibe_server):
    """GET / should report the number of authenticated connections."""
    client = await aiohttp_client(jibe_app)

    data = await (await client.get("/")).json()
    assert data["connected_devices"] == 0

    ws = await client.ws_connect("/ws")
    await _authenticate(ws, jibe_server)

    data = await (await client.get("/")).json()
    assert data["connected_devices"] == 1

    await ws.close()


# ── WebSocket: Auth Gate ─────────────────────────────────────────────────


async def test_unauthenticated_non_auth_message_rejected(aiohttp_client, jibe_app):
    """Non-auth messages before authentication must get auth_required."""
    client = await aiohttp_client(jibe_app)
    ws = await client.ws_connect("/ws")

    await ws.send_json({"type": "ping"})
    resp = await ws.receive_json()

    assert resp["type"] == "error"
    assert resp["code"] == "auth_required"

    await ws.close()


async def test_malformed_json_still_rejected_before_auth(aiohttp_client, jibe_app):
    """Malformed JSON should return malformed_json even before auth."""
    client = await aiohttp_client(jibe_app)
    ws = await client.ws_connect("/ws")

    await ws.send_str("{not valid json}")
    resp = await ws.receive_json()

    assert resp["type"] == "error"
    assert resp["code"] == "malformed_json"

    await ws.close()


async def test_successful_auth_flow(aiohttp_client, jibe_app, jibe_server):
    """A correct PIN should transition the connection to AUTHENTICATED."""
    client = await aiohttp_client(jibe_app)
    ws = await client.ws_connect("/ws")

    resp = await _authenticate(ws, jibe_server)
    assert resp["type"] == "auth.response"
    assert "device_id" in resp
    assert "fingerprint" in resp

    await ws.close()


async def test_wrong_pin_rejected(aiohttp_client, jibe_app, jibe_server):
    """A wrong PIN should return accepted=False."""
    client = await aiohttp_client(jibe_app)
    ws = await client.ws_connect("/ws")

    jibe_server.auth.start_pairing()
    await ws.send_json(
        {
            "type": "auth.request",
            "pin": "000000",
            "device_name": "Attacker",
        }
    )
    resp = await ws.receive_json()

    assert resp["accepted"] is False
    assert "Invalid PIN" in resp["reason"]

    await ws.close()


async def test_auth_without_pairing_mode(aiohttp_client, jibe_app):
    """auth.request when pairing mode is inactive must be rejected."""
    client = await aiohttp_client(jibe_app)
    ws = await client.ws_connect("/ws")

    await ws.send_json(
        {
            "type": "auth.request",
            "pin": "123456",
            "device_name": "Phone",
        }
    )
    resp = await ws.receive_json()

    assert resp["accepted"] is False
    assert "not active" in resp["reason"]

    await ws.close()


# ── WebSocket: Authenticated Messages ────────────────────────────────────


async def test_authenticated_connection_accepts_messages(
    aiohttp_client, jibe_app, jibe_server
):
    """After auth, other message types should be accepted without error."""
    client = await aiohttp_client(jibe_app)
    ws = await client.ws_connect("/ws")
    await _authenticate(ws, jibe_server)

    await ws.send_json({"type": "ping"})

    await ws.close()
    assert ws.closed


async def test_multiple_messages_after_auth(aiohttp_client, jibe_app, jibe_server):
    """An authenticated connection should handle multiple messages."""
    client = await aiohttp_client(jibe_app)
    ws = await client.ws_connect("/ws")
    await _authenticate(ws, jibe_server)

    await ws.send_json({"type": "ping"})
    pong = await ws.receive_json()
    assert pong["type"] == "pong"

    await ws.send_json({"type": "pong"})
    not_impl = await ws.receive_json()
    assert not_impl["code"] == "not_implemented"

    await ws.send_str("broken")
    resp = await ws.receive_json()
    assert resp["code"] == "malformed_json"

    await ws.send_json({"type": "ping"})
    pong2 = await ws.receive_json()
    assert pong2["type"] == "pong"

    await ws.close()
    assert ws.closed


# ── WebSocket: Error Handling ────────────────────────────────────────────


async def test_unknown_type_returns_error(aiohttp_client, jibe_app):
    """Sending an unknown message type should return an error."""
    client = await aiohttp_client(jibe_app)
    ws = await client.ws_connect("/ws")

    await ws.send_json({"type": "does.not.exist"})
    resp = await ws.receive_json()

    assert resp["type"] == "error"
    assert resp["code"] == "unknown_type"

    await ws.close()


async def test_missing_type_returns_error(aiohttp_client, jibe_app):
    """Sending a JSON object without a 'type' field should return an error."""
    client = await aiohttp_client(jibe_app)
    ws = await client.ws_connect("/ws")

    await ws.send_json({"pin": "123456"})
    resp = await ws.receive_json()

    assert resp["type"] == "error"
    assert resp["code"] == "malformed_json"
    assert "Missing 'type' field" in resp["message"]

    await ws.close()


async def test_empty_message_returns_error(aiohttp_client, jibe_app):
    """Sending an empty string should return a malformed_json error."""
    client = await aiohttp_client(jibe_app)
    ws = await client.ws_connect("/ws")

    await ws.send_str("")
    resp = await ws.receive_json()

    assert resp["type"] == "error"
    assert resp["code"] == "malformed_json"

    await ws.close()


# ── WebSocket: Connection Lifecycle ──────────────────────────────────────


async def test_clean_disconnect(aiohttp_client, jibe_app):
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
