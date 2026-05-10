"""REST: recovery, password change, data wipes, ping endpoints."""

import pytest


async def _admin_headers(client, *, password: str = "testpass"):
    r = await client.post(
        "/api/auth/login",
        json={"username": "admin", "password": password},
    )
    assert r.status == 200
    tok = (await r.json())["token"]
    return {"Authorization": f"Bearer {tok}"}


@pytest.mark.asyncio
async def test_recovery_status_reflects_key_file(aiohttp_client, jibe_app, db):
    await db.add_user("admin", "testpass", "admin")
    client = await aiohttp_client(jibe_app)
    r = await client.get("/api/auth/recovery-status")
    assert r.status == 200
    assert (await r.json())["recovery_enabled"] is False

    db.write_recovery_key_secret("unit-test-recovery-secret-token-x")

    r2 = await client.get("/api/auth/recovery-status")
    assert r2.status == 200
    assert (await r2.json())["recovery_enabled"] is True


@pytest.mark.asyncio
async def test_recovery_reset_updates_admin_password(aiohttp_client, jibe_app, db):
    await db.add_user("admin", "oldpass012", "admin")
    db.write_recovery_key_secret("good-token-unit-test")

    client = await aiohttp_client(jibe_app)

    bad = await client.post(
        "/api/auth/recovery-reset",
        json={"recovery_token": "wrong", "new_password": "newpassword012"},
    )
    assert bad.status == 401

    ok = await client.post(
        "/api/auth/recovery-reset",
        json={"recovery_token": "good-token-unit-test", "new_password": "newpassword012"},
    )
    assert ok.status == 200

    fail_login = await client.post(
        "/api/auth/login",
        json={"username": "admin", "password": "oldpass012"},
    )
    assert fail_login.status == 401

    win = await client.post(
        "/api/auth/login",
        json={"username": "admin", "password": "newpassword012"},
    )
    assert win.status == 200


@pytest.mark.asyncio
async def test_change_password(aiohttp_client, jibe_app, dashboard_admin):
    client = await aiohttp_client(jibe_app)
    h = await _admin_headers(client)

    bad = await client.post(
        "/api/auth/change-password",
        headers=h,
        json={"current_password": "wrong", "new_password": "freshpass012"},
    )
    assert bad.status == 401

    ok = await client.post(
        "/api/auth/change-password",
        headers=h,
        json={"current_password": "testpass", "new_password": "freshpass012"},
    )
    assert ok.status == 200

    login_new = await client.post(
        "/api/auth/login",
        json={"username": "admin", "password": "freshpass012"},
    )
    assert login_new.status == 200


@pytest.mark.asyncio
async def test_clear_history_admin_only(aiohttp_client, jibe_app, viewer_user):
    client = await aiohttp_client(jibe_app)
    vr = await client.post("/api/auth/login", json={"username": "v1", "password": "viewerpass"})
    assert vr.status == 200
    vh = {"Authorization": f"Bearer {(await vr.json())['token']}"}

    r = await client.post("/api/settings/data/clear-history", headers=vh, json={})
    assert r.status == 403


@pytest.mark.asyncio
async def test_clear_history_ok_admin(aiohttp_client, jibe_app, dashboard_admin):
    client = await aiohttp_client(jibe_app)
    h = await _admin_headers(client)
    r = await client.post("/api/settings/data/clear-history", headers=h, json={})
    assert r.status == 200
    assert (await r.json()).get("ok") is True


@pytest.mark.asyncio
async def test_clear_statistics_sessions_admin(aiohttp_client, jibe_app, dashboard_admin):
    client = await aiohttp_client(jibe_app)
    h = await _admin_headers(client)
    r = await client.post("/api/settings/data/clear-statistics", headers=h, json={})
    assert r.status == 200
    body = await r.json()
    assert body.get("ok") is True
    assert "sessions_removed" in body


@pytest.mark.asyncio
async def test_ping_activity_and_send_requires_admin(aiohttp_client, jibe_app, viewer_user):
    client = await aiohttp_client(jibe_app)
    vr = await client.post("/api/auth/login", json={"username": "v1", "password": "viewerpass"})
    vh = {"Authorization": f"Bearer {(await vr.json())['token']}"}

    r = await client.get("/api/daemon/ping-activity", headers=vh)
    assert r.status == 403

    r2 = await client.post("/api/daemon/ping-send", headers=vh, json={})
    assert r2.status == 403


@pytest.mark.asyncio
async def test_ping_activity_empty_admin(aiohttp_client, jibe_app, dashboard_admin):
    client = await aiohttp_client(jibe_app)
    h = await _admin_headers(client)
    r = await client.get("/api/daemon/ping-activity", headers=h)
    assert r.status == 200
    assert (await r.json())["items"] == []
