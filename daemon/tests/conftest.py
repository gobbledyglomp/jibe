"""Shared pytest fixtures for the Jibe daemon test suite.

Centralises fixture creation so individual test modules stay lean.
As the project grows, new shared fixtures (e.g. database connections,
mock event buses) should be added here rather than duplicated across
test files.
"""

import json
from pathlib import Path

import pytest

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
