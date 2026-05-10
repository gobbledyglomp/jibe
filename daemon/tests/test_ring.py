"""Tests for ``device.ring`` outbound helper and REST guards."""

import pytest

from jibe.handlers.ring import send_ring
from jibe.network.connection import ConnectionState, JibeConnection


@pytest.mark.asyncio
async def test_send_ring_json_payload(mock_ws):
    conn = JibeConnection(mock_ws, "127.0.0.1")
    conn.state = ConnectionState.AUTHENTICATED
    await send_ring(conn)
    sends = [c[0][0] for c in mock_ws.send_str.await_args_list]
    assert sends
    assert '"type": "device.ring"' in sends[-1] or '"device.ring"' in sends[-1]


@pytest.mark.asyncio
async def test_ring_rest_requires_admin(aiohttp_client, jibe_app, viewer_user):
    client = await aiohttp_client(jibe_app)
    login = await client.post("/api/auth/login", json={"username": "v1", "password": "viewerpass"})
    assert login.status == 200
    token = (await login.json())["token"]
    headers = {"Authorization": f"Bearer {token}"}

    r = await client.post("/api/ring/some-device-id", headers=headers)
    assert r.status == 403


@pytest.mark.asyncio
async def test_ring_rest_404_when_offline(aiohttp_client, jibe_app, dashboard_admin):
    client = await aiohttp_client(jibe_app)
    login = await client.post("/api/auth/login", json={"username": "admin", "password": "testpass"})
    token = (await login.json())["token"]
    headers = {"Authorization": f"Bearer {token}"}

    r = await client.post("/api/ring/nonexistent-device", headers=headers)
    assert r.status == 404
