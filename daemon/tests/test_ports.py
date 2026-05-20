"""Port availability preflight."""

import socket
from unittest.mock import patch

import pytest

from jibe.core.ports import assert_ports_available


def test_assert_ports_available_raises_when_ws_port_taken():
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    try:
        with pytest.raises(SystemExit):
            assert_ports_available(port, use_tls=False)
    finally:
        sock.close()
