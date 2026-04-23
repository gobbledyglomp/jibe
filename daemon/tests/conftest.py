"""Shared pytest fixtures for the Jibe daemon test suite.

Centralises fixture creation so individual test modules stay lean.
As the project grows, new shared fixtures (e.g. database connections,
mock event buses) should be added here rather than duplicated across
test files.
"""

import json
from pathlib import Path
from unittest.mock import AsyncMock

import pytest
from aiohttp import web
from jibe.auth import AuthManager
from jibe.connection import ConnectionRegistry, JibeConnection
from jibe.db import JibeDatabase
from jibe.server import JibeServer

FIXTURES_DIR = Path(__file__).parent / "fixtures"


@pytest.fixture
def valid_messages():
    """Load valid message examples from the JSON fixture file."""
    with open(FIXTURES_DIR / "messages.json", "r") as f:
        return json.load(f)


@pytest.fixture
def jibe_server(db):
    """A JibeServer wired to the test database."""
    return JibeServer(db=db)


@pytest.fixture
def jibe_app(jibe_server):
    """The aiohttp application from a test JibeServer.

    This is the standard way to test aiohttp apps: create the
    application object without starting the TCP listener, then pass it
    to ``aiohttp_client`` which spins up a lightweight test server.
    """
    return jibe_server._app


@pytest.fixture
async def db(tmp_path):
    """Create a JibeDatabase backed by a temp file.

    Using tmp_path (a pytest built-in fixture) instead of :memory:
    because it lets us test real file creation, directory handling,
    and persistence across open/close cycles — things an in-memory
    DB would silently skip.
    """
    db_path = tmp_path / "test_jibe.db"
    database = JibeDatabase(db_path=db_path)
    await database.open()
    yield database
    await database.close()


@pytest.fixture
async def auth(db):
    """AuthManager wired to the shared test database."""
    return AuthManager(db)


@pytest.fixture
async def auth_pairing(auth):
    """AuthManager with pairing mode already active."""
    auth.start_pairing()
    return auth


@pytest.fixture
def mock_ws():
    """A mock aiohttp WebSocketResponse."""
    ws = AsyncMock(spec=web.WebSocketResponse)
    ws.closed = False
    return ws


@pytest.fixture
def conn(mock_ws):
    """A fresh JibeConnection in AWAITING_AUTH state."""
    return JibeConnection(ws=mock_ws, client_ip="127.0.0.1")


@pytest.fixture
def registry():
    """An empty ConnectionRegistry."""
    return ConnectionRegistry()
