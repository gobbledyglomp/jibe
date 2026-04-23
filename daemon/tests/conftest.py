"""Shared pytest fixtures for the Jibe daemon test suite.

Centralises fixture creation so individual test modules stay lean.
As the project grows, new shared fixtures (e.g. database connections,
mock event buses) should be added here rather than duplicated across
test files.
"""

import json
from pathlib import Path

import pytest
from jibe.auth import AuthManager
from jibe.db import JibeDatabase
from jibe.server import JibeServer

FIXTURES_DIR = Path(__file__).parent / "fixtures"


@pytest.fixture
def valid_messages():
    """Load valid message examples from the JSON fixture file."""
    with open(FIXTURES_DIR / "messages.json", "r") as f:
        return json.load(f)


@pytest.fixture
def jibe_app():
    """Create a bare JibeServer and return its aiohttp application.

    This is the standard way to test aiohttp apps: create the
    application object without starting the TCP listener, then pass it
    to ``aiohttp_client`` which spins up a lightweight test server.
    """
    server = JibeServer()
    return server._app


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
