"""Integration tests for TLS-secured WebSocket connections.

These tests exercise the full stack over TLS: a real aiohttp test
client connects via wss://, authenticates via PIN, and exchanges
messages — proving the entire chain works end-to-end:

    WebSocket (TLS) → parse_message → state machine → AuthManager → DB
"""

from jibe import __version__

async def _authenticate_tls(ws, jibe_server_tls):
    """Pair a device over the TLS WebSocket and return the auth response."""
    pin = jibe_server_tls.auth.start_pairing()
    await ws.send_json(
        {
            "type": "auth.request",
            "pin": pin,
            "device_name": "TLS Test Device",
        }
    )
    resp = await ws.receive_json()
    assert resp["accepted"] is True
    return resp


async def test_health_over_tls(aiohttp_client, jibe_app_tls):
    """GET /health over TLS should return status and version."""
    client = await aiohttp_client(jibe_app_tls)
    resp = await client.get("/health")

    assert resp.status == 200
    data = await resp.json()
    assert data["status"] == "running"
    assert data["version"] == __version__


async def test_tls_unauthenticated_message_rejected(aiohttp_client, jibe_app_tls):
    """Messages before auth must be rejected over TLS too."""
    client = await aiohttp_client(jibe_app_tls)
    ws = await client.ws_connect("/ws")

    await ws.send_json({"type": "ping"})
    resp = await ws.receive_json()

    assert resp["code"] == "auth_required"
    await ws.close()


async def test_tls_successful_auth(aiohttp_client, jibe_app_tls, jibe_server_tls):
    """A full auth handshake should succeed over TLS."""
    client = await aiohttp_client(jibe_app_tls)
    ws = await client.ws_connect("/ws")

    resp = await _authenticate_tls(ws, jibe_server_tls)
    assert resp["type"] == "auth.response"
    assert "device_id" in resp
    assert "fingerprint" in resp

    await ws.close()


async def test_tls_authenticated_message_flow(
    aiohttp_client, jibe_app_tls, jibe_server_tls
):
    """After TLS auth, messages should be routed without errors."""
    client = await aiohttp_client(jibe_app_tls)
    ws = await client.ws_connect("/ws")
    await _authenticate_tls(ws, jibe_server_tls)

    await ws.send_json({"type": "ping"})
    pong = await ws.receive_json()
    assert pong["type"] == "pong"

    await ws.send_json({"type": "pong"})
    # Client pong without ``probe`` is handled silently (no reply).

    await ws.send_json({"type": "does.not.exist"})
    unknown = await ws.receive_json()
    assert unknown["code"] == "unknown_type"

    await ws.send_str("not json")
    resp = await ws.receive_json()
    assert resp["code"] == "malformed_json"

    await ws.close()
    assert ws.closed


async def test_tls_device_persisted_in_db(
    aiohttp_client, jibe_app_tls, jibe_server_tls, db
):
    """A device authenticated over TLS should be persisted in the database."""
    client = await aiohttp_client(jibe_app_tls)
    ws = await client.ws_connect("/ws")
    resp = await _authenticate_tls(ws, jibe_server_tls)

    device = await db.get_device_by_id(resp["device_id"])
    assert device is not None
    assert device["name"] == "TLS Test Device"

    await ws.close()
