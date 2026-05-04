"""Tests for the REST API endpoints.

Covers GET /api/status, GET /api/devices, DELETE /api/devices/{id},
and the 404 case for deleting a non-existent device.
"""

from jibe import __version__


async def _pair_device(ws, jibe_server, device_name="Test Device"):
    """Pair a device and return the auth response."""
    pin = jibe_server.auth.start_pairing()
    await ws.send_json(
        {
            "type": "auth.request",
            "pin": pin,
            "device_name": device_name,
        }
    )
    return await ws.receive_json()


async def test_api_status(aiohttp_client, jibe_app):
    """GET /api/status should return daemon status."""
    client = await aiohttp_client(jibe_app)
    resp = await client.get("/api/status")

    assert resp.status == 200
    data = await resp.json()
    assert data["status"] == "running"
    assert data["version"] == __version__
    assert data["connected_devices"] == 0
    assert data["total_connections"] == 0


async def test_api_devices_empty(aiohttp_client, jibe_app):
    """GET /api/devices should return empty list initially."""
    client = await aiohttp_client(jibe_app)
    resp = await client.get("/api/devices")

    assert resp.status == 200
    data = await resp.json()
    assert data["devices"] == []


async def test_api_devices_after_pairing(aiohttp_client, jibe_app, jibe_server):
    """GET /api/devices should list paired devices."""
    client = await aiohttp_client(jibe_app)

    ws = await client.ws_connect("/ws")
    await _pair_device(ws, jibe_server, "Pixel 8")
    await ws.close()

    resp = await client.get("/api/devices")
    data = await resp.json()

    assert len(data["devices"]) == 1
    assert data["devices"][0]["name"] == "Pixel 8"


async def test_api_delete_device(aiohttp_client, jibe_app, jibe_server):
    """DELETE /api/devices/{id} should remove a paired device."""
    client = await aiohttp_client(jibe_app)

    ws = await client.ws_connect("/ws")
    auth = await _pair_device(ws, jibe_server)
    device_id = auth["device_id"]
    await ws.close()

    resp = await client.delete(f"/api/devices/{device_id}")
    assert resp.status == 200
    data = await resp.json()
    assert data["removed"] == device_id

    resp = await client.get("/api/devices")
    data = await resp.json()
    assert data["devices"] == []


async def test_api_delete_nonexistent_device(aiohttp_client, jibe_app):
    """DELETE /api/devices/{id} for unknown ID should return 404."""
    client = await aiohttp_client(jibe_app)
    resp = await client.delete("/api/devices/nonexistent-id")

    assert resp.status == 404
