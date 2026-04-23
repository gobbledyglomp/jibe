"""Tests for the connection state machine and registry."""

from jibe.connection import ConnectionState, JibeConnection

# ── JibeConnection ────────────────────────────────────────────────────────


def test_connection_initial_state(conn, mock_ws):
    """A new connection must start in AWAITING_AUTH."""
    assert conn.state == ConnectionState.AWAITING_AUTH
    assert not conn.is_authenticated
    assert conn.ws is mock_ws
    assert conn.client_ip == "127.0.0.1"
    assert conn.id is not None
    assert conn.device_id is None
    assert conn.device_name is None


async def test_connection_send(conn, mock_ws):
    """send() must write to the underlying websocket."""
    await conn.send('{"type": "ping"}')
    mock_ws.send_str.assert_awaited_once_with('{"type": "ping"}')


async def test_connection_send_skips_if_closed(conn, mock_ws):
    """send() must not write if the websocket is already closed."""
    mock_ws.closed = True
    await conn.send('{"type": "ping"}')
    mock_ws.send_str.assert_not_awaited()


async def test_connection_close(conn, mock_ws):
    """close() must update state and close the websocket."""
    await conn.close()
    assert conn.state == ConnectionState.DISCONNECTED
    mock_ws.close.assert_awaited_once()


async def test_connection_close_skips_if_already_closed(conn, mock_ws):
    """close() must only update state if websocket is already closed."""
    mock_ws.closed = True
    await conn.close()
    assert conn.state == ConnectionState.DISCONNECTED
    mock_ws.close.assert_not_awaited()


def test_connection_repr(conn):
    """__repr__ must show id, state, and device name."""
    rep = repr(conn)
    assert conn.id in rep
    assert "awaiting_auth" in rep
    assert "unknown" in rep

    conn.device_name = "Pixel"
    assert "Pixel" in repr(conn)


# ── ConnectionRegistry ────────────────────────────────────────────────────


def test_registry_add_remove(registry, conn):
    """Connections can be added and removed."""
    assert registry.count == 0

    registry.add(conn)
    assert registry.count == 1
    assert registry._connections[conn.id] == conn

    registry.remove(conn)
    assert registry.count == 0


def test_registry_get_by_device_id(registry, conn):
    """Connections can be looked up by device_id."""
    conn.device_id = "device-123"
    registry.add(conn)

    assert registry.get_by_device_id("device-123") == conn
    assert registry.get_by_device_id("nonexistent") is None


def test_registry_get_authenticated(registry, mock_ws):
    """Can retrieve only authenticated connections."""
    conn1 = JibeConnection(ws=mock_ws, client_ip="1.1.1.1")
    conn2 = JibeConnection(ws=mock_ws, client_ip="2.2.2.2")
    conn3 = JibeConnection(ws=mock_ws, client_ip="3.3.3.3")

    # Only conn2 is authenticated
    conn2.state = ConnectionState.AUTHENTICATED

    registry.add(conn1)
    registry.add(conn2)
    registry.add(conn3)

    assert registry.count == 3
    assert registry.authenticated_count == 1

    auth_conns = registry.get_authenticated()
    assert len(auth_conns) == 1
    assert auth_conns[0] == conn2
