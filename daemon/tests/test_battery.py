"""Tests for ``device.battery`` handler and REST snapshot."""

import pytest

from jibe.core.api import JibeMessage, MessageType
from jibe.handlers.battery import forget_battery, get_all_batteries, handle_battery
from jibe.network.connection import ConnectionState


def _msg(**payload) -> JibeMessage:
    data = {"type": MessageType.DEVICE_BATTERY.value, **payload}
    return JibeMessage(type=MessageType.DEVICE_BATTERY, payload=data)


@pytest.mark.asyncio
async def test_handle_battery_updates_cache(conn):
    conn.state = ConnectionState.AUTHENTICATED
    conn.device_id = "dev_bt_1"
    conn.device_name = "Pixel"
    forget_battery("dev_bt_1")

    await handle_battery(conn, _msg(level=73, charging=True))

    snap = get_all_batteries()["dev_bt_1"]
    assert snap["level"] == 73
    assert snap["charging"] is True


@pytest.mark.asyncio
async def test_handle_battery_rejects_invalid_level(conn):
    conn.state = ConnectionState.AUTHENTICATED
    conn.device_id = "dev_bt_2"
    await handle_battery(conn, _msg(level=101, charging=False))
    sends = [c[0][0] for c in conn.ws.send_str.await_args_list]
    assert any("malformed_payload" in s for s in sends)
    assert "dev_bt_2" not in get_all_batteries()


@pytest.mark.asyncio
async def test_api_battery_requires_auth(aiohttp_client, jibe_app):
    client = await aiohttp_client(jibe_app)
    r = await client.get("/api/battery")
    assert r.status == 401


@pytest.mark.asyncio
async def test_api_battery_json_shape(aiohttp_client, jibe_app, dashboard_admin):
    client = await aiohttp_client(jibe_app)
    login = await client.post("/api/auth/login", json={"username": "admin", "password": "testpass"})
    assert login.status == 200
    token = (await login.json())["token"]
    headers = {"Authorization": f"Bearer {token}"}

    r = await client.get("/api/battery", headers=headers)
    assert r.status == 200
    body = await r.json()
    assert "battery" in body
    assert isinstance(body["battery"], dict)
