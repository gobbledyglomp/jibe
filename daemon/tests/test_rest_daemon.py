"""Admin-only daemon control REST endpoints."""


async def _admin_header(client):
    r = await client.post(
        "/api/auth/login",
        json={"username": "admin", "password": "testpass"},
    )
    assert r.status == 200
    data = await r.json()
    return {"Authorization": f"Bearer {data['token']}"}


async def test_daemon_pairing_status_and_snapshot(aiohttp_client, jibe_app, dashboard_admin):
    client = await aiohttp_client(jibe_app)
    r = await client.post(
        "/api/auth/login",
        json={"username": "admin", "password": "testpass"},
    )
    assert r.status == 200
    tok = (await r.json())["token"]
    h = {"Authorization": f"Bearer {tok}"}

    r2 = await client.get("/api/daemon/pairing/status", headers=h)
    assert r2.status == 200
    body = await r2.json()
    assert "active" in body


async def test_daemon_status(aiohttp_client, jibe_app, dashboard_admin):
    client = await aiohttp_client(jibe_app)
    h = await _admin_header(client)
    r = await client.get("/api/daemon/status", headers=h)
    assert r.status == 200
    data = await r.json()
    assert "version" in data
    assert "uptime_seconds" in data


async def test_pairing_start_stop(aiohttp_client, jibe_app, dashboard_admin):
    client = await aiohttp_client(jibe_app)
    h = await _admin_header(client)
    r = await client.post("/api/daemon/pairing/start", headers=h, json={})
    assert r.status == 200
    body = await r.json()
    assert len(body["pin"]) == 6

    r2 = await client.post("/api/daemon/pairing/stop", headers=h, json={})
    assert r2.status == 200
