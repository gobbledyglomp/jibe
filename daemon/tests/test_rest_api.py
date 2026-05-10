"""Tests for JWT-protected REST API endpoints."""

from jibe import __version__


async def _auth_header(client, *, username="admin", password="testpass"):
    resp = await client.post(
        "/api/auth/login",
        json={"username": username, "password": password},
    )
    assert resp.status == 200
    data = await resp.json()
    return {"Authorization": f"Bearer {data['token']}"}


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


async def test_api_login_success(aiohttp_client, jibe_app, dashboard_admin):
    """POST /api/auth/login returns a JWT."""
    client = await aiohttp_client(jibe_app)
    resp = await client.post(
        "/api/auth/login",
        json={"username": "admin", "password": "testpass"},
    )
    assert resp.status == 200
    data = await resp.json()
    assert "token" in data
    assert data["role"] == "admin"
    assert data["username"] == "admin"


async def test_api_login_failure(aiohttp_client, jibe_app, dashboard_admin):
    """Wrong password yields 401."""
    client = await aiohttp_client(jibe_app)
    resp = await client.post(
        "/api/auth/login",
        json={"username": "admin", "password": "wrong"},
    )
    assert resp.status == 401


async def test_api_status_requires_auth(aiohttp_client, jibe_app):
    """GET /api/status without Bearer returns 401."""
    client = await aiohttp_client(jibe_app)
    resp = await client.get("/api/status")
    assert resp.status == 401


async def test_api_status(aiohttp_client, jibe_app, dashboard_admin):
    """GET /api/status with JWT."""
    client = await aiohttp_client(jibe_app)
    h = await _auth_header(client)
    resp = await client.get("/api/status", headers=h)
    assert resp.status == 200
    data = await resp.json()
    assert data["status"] == "running"
    assert data["version"] == __version__
    assert data["connected_devices"] == 0


async def test_api_devices_empty(aiohttp_client, jibe_app, dashboard_admin):
    """GET /api/devices should return empty list initially."""
    client = await aiohttp_client(jibe_app)
    h = await _auth_header(client)
    resp = await client.get("/api/devices", headers=h)
    assert resp.status == 200
    data = await resp.json()
    assert data["devices"] == []


async def test_api_devices_after_pairing(aiohttp_client, jibe_app, jibe_server, dashboard_admin):
    """GET /api/devices lists paired devices with online flag."""
    client = await aiohttp_client(jibe_app)
    h = await _auth_header(client)

    ws = await client.ws_connect("/ws")
    await _pair_device(ws, jibe_server, "Pixel 8")

    resp = await client.get("/api/devices", headers=h)
    data = await resp.json()
    assert len(data["devices"]) == 1
    assert data["devices"][0]["name"] == "Pixel 8"
    assert data["devices"][0]["online"] is True

    await ws.close()


async def test_api_delete_device(aiohttp_client, jibe_app, jibe_server, dashboard_admin):
    """DELETE /api/devices/{id} removes a paired device."""
    client = await aiohttp_client(jibe_app)
    h = await _auth_header(client)

    ws = await client.ws_connect("/ws")
    auth = await _pair_device(ws, jibe_server)
    device_id = auth["device_id"]
    await ws.close()

    resp = await client.delete(f"/api/devices/{device_id}", headers=h)
    assert resp.status == 200
    data = await resp.json()
    assert data["removed"] == device_id

    resp = await client.get("/api/devices", headers=h)
    data = await resp.json()
    assert data["devices"] == []


async def test_api_delete_nonexistent_device(aiohttp_client, jibe_app, dashboard_admin):
    """DELETE unknown device returns 404."""
    client = await aiohttp_client(jibe_app)
    h = await _auth_header(client)
    resp = await client.delete("/api/devices/nonexistent-id", headers=h)
    assert resp.status == 404
