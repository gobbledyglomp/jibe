"""Unit tests for the mDNS discovery module.

The discovery module depends on the network (UDP multicast) and the
zeroconf library. We mock AsyncZeroconf so these tests run instantly
and don't touch the network — they verify our code's logic, not
zeroconf's internals.

The ``_get_local_ip`` function uses a real UDP socket trick, so we
test it both with and without a mock to verify the fallback path.
"""

import socket
from unittest.mock import AsyncMock, MagicMock, patch

from jibe.core.config import DEFAULT_PORT, SERVICE_NAME, SERVICE_TYPE
from jibe.network.discovery import JibeDiscovery, _get_local_ip

def test_get_local_ip_returns_string():
    """The function should return a dotted-quad IPv4 string."""
    ip = _get_local_ip()
    parts = ip.split(".")
    assert len(parts) == 4
    for part in parts:
        assert part.isdigit()
        assert 0 <= int(part) <= 255


def test_get_local_ip_fallback_on_oserror():
    """When all detection methods fail, fall back to 127.0.0.1."""
    with patch(
        "jibe.network.discovery.socket.getaddrinfo", side_effect=OSError("no network")
    ):
        with patch("jibe.network.discovery.socket.socket") as mock_socket:
            mock_socket.return_value.__enter__ = MagicMock(
                side_effect=OSError("no network")
            )
            ip = _get_local_ip()
            assert ip == "127.0.0.1"


async def test_discovery_starts_with_correct_service_info():
    """start() should register a service with the expected name and type."""
    discovery = JibeDiscovery(port=DEFAULT_PORT)

    mock_zc_instance = MagicMock()
    mock_zc_instance.async_register_service = AsyncMock()
    mock_zc_instance.async_unregister_service = AsyncMock()
    mock_zc_instance.async_close = AsyncMock()

    with patch("jibe.network.discovery.AsyncZeroconf", return_value=mock_zc_instance):
        with patch("jibe.network.discovery._get_local_ip", return_value="192.168.1.42"):
            await discovery.start()

    # Verify register was called exactly once
    mock_zc_instance.async_register_service.assert_called_once()

    # Inspect the ServiceInfo that was passed
    service_info = mock_zc_instance.async_register_service.call_args[0][0]
    assert service_info.type == SERVICE_TYPE
    assert service_info.name == f"{SERVICE_NAME}.{SERVICE_TYPE}"
    assert service_info.port == DEFAULT_PORT
    assert socket.inet_ntoa(service_info.addresses[0]) == "192.168.1.42"

    # Clean up
    await discovery.stop()


async def test_discovery_stop_unregisters_and_closes():
    """stop() should unregister the service and close the zeroconf instance."""
    discovery = JibeDiscovery(port=DEFAULT_PORT)

    mock_zc_instance = MagicMock()
    mock_zc_instance.async_register_service = AsyncMock()
    mock_zc_instance.async_unregister_service = AsyncMock()
    mock_zc_instance.async_close = AsyncMock()

    with patch("jibe.network.discovery.AsyncZeroconf", return_value=mock_zc_instance):
        with patch("jibe.network.discovery._get_local_ip", return_value="192.168.1.42"):
            await discovery.start()

    await discovery.stop()

    mock_zc_instance.async_unregister_service.assert_called_once()
    mock_zc_instance.async_close.assert_called_once()


async def test_discovery_stop_is_idempotent():
    """Calling stop() when not started should not raise."""
    discovery = JibeDiscovery()
    # Should not raise — nothing to unregister
    await discovery.stop()


async def test_discovery_includes_version_in_txt_records():
    """The mDNS TXT record should include the daemon version."""
    from jibe import __version__

    discovery = JibeDiscovery(port=DEFAULT_PORT)

    mock_zc_instance = MagicMock()
    mock_zc_instance.async_register_service = AsyncMock()
    mock_zc_instance.async_unregister_service = AsyncMock()
    mock_zc_instance.async_close = AsyncMock()

    with patch("jibe.network.discovery.AsyncZeroconf", return_value=mock_zc_instance):
        with patch("jibe.network.discovery._get_local_ip", return_value="10.0.0.1"):
            await discovery.start()

    service_info = mock_zc_instance.async_register_service.call_args[0][0]
    assert service_info.properties[b"version"] == __version__.encode()
    assert service_info.properties[b"platform"] == b"linux"

    await discovery.stop()


async def test_discovery_custom_port():
    """JibeDiscovery should respect a custom port."""
    custom_port = 9999
    discovery = JibeDiscovery(port=custom_port)

    mock_zc_instance = MagicMock()
    mock_zc_instance.async_register_service = AsyncMock()
    mock_zc_instance.async_unregister_service = AsyncMock()
    mock_zc_instance.async_close = AsyncMock()

    with patch("jibe.network.discovery.AsyncZeroconf", return_value=mock_zc_instance):
        with patch("jibe.network.discovery._get_local_ip", return_value="10.0.0.1"):
            await discovery.start()

    service_info = mock_zc_instance.async_register_service.call_args[0][0]
    assert service_info.port == custom_port

    await discovery.stop()
