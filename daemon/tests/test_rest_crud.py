"""REST list endpoints and Viewer vs Admin guards."""

import pytest


async def _login(client, username: str, password: str) -> dict[str, str]:
    r = await client.post(
        "/api/auth/login",
        json={"username": username, "password": password},
    )
    assert r.status == 200
    data = await r.json()
    return {"Authorization": f"Bearer {data['token']}"}


@pytest.mark.asyncio
async def test_stats_available_to_viewer(aiohttp_client, jibe_app, viewer_user):
    client = await aiohttp_client(jibe_app)
    h = await _login(client, "v1", "viewerpass")
    r = await client.get("/api/stats", headers=h)
    assert r.status == 200
    body = await r.json()
    assert "totals" in body
    assert "activity_last_7_days" in body


@pytest.mark.asyncio
async def test_viewer_forbidden_delete_device(
    aiohttp_client,
    jibe_app,
    jibe_server,
    viewer_user,
):
    client = await aiohttp_client(jibe_app)
    ws = await client.ws_connect("/ws")
    pin = jibe_server.auth.start_pairing()
    await ws.send_json(
        {"type": "auth.request", "pin": pin, "device_name": "Phone"},
    )
    auth = await ws.receive_json()
    device_id = auth["device_id"]
    await ws.close()

    vh = await _login(client, "v1", "viewerpass")
    resp = await client.delete(f"/api/devices/{device_id}", headers=vh)
    assert resp.status == 403


@pytest.mark.asyncio
async def test_admin_can_rename_device(
    aiohttp_client,
    jibe_app,
    jibe_server,
    dashboard_admin,
):
    client = await aiohttp_client(jibe_app)
    ws = await client.ws_connect("/ws")
    pin = jibe_server.auth.start_pairing()
    await ws.send_json(
        {"type": "auth.request", "pin": pin, "device_name": "Old"},
    )
    auth = await ws.receive_json()
    device_id = auth["device_id"]
    await ws.close()

    ah = await _login(client, "admin", "testpass")
    resp = await client.patch(
        f"/api/devices/{device_id}",
        headers=ah,
        json={"name": "NewName"},
    )
    assert resp.status == 200
    data = await resp.json()
    assert data["name"] == "NewName"


@pytest.mark.asyncio
async def test_transfer_history_pagination(aiohttp_client, jibe_app, dashboard_admin):
    client = await aiohttp_client(jibe_app)
    h = await _login(client, "admin", "testpass")
    r = await client.get("/api/history/transfers?page=1&per_page=10", headers=h)
    assert r.status == 200
    data = await r.json()
    assert data["items"] == []
    assert data["total"] == 0
    assert data["page"] == 1


@pytest.mark.asyncio
async def test_viewer_denied_daemon_status(aiohttp_client, jibe_app, viewer_user):
    client = await aiohttp_client(jibe_app)
    h = await _login(client, "v1", "viewerpass")
    r = await client.get("/api/daemon/status", headers=h)
    assert r.status == 403
