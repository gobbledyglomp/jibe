"""mDNS service discovery for the Jibe daemon.

This module broadcasts the daemon's presence on the local network using
mDNS (multicast DNS) via the zeroconf library. When the daemon starts,
it registers a service of type `_jibe._tcp.local.` so that Android
clients can discover it automatically without any manual IP configuration.

This is the same mechanism used by Chromecast, AirPlay, and similar
zero-configuration protocols. The Android app scans for `_jibe._tcp`
services and connects to whichever daemon it finds.

Why zeroconf (Python library)?
  The `zeroconf` library is a pure-Python, cross-platform mDNS/DNS-SD
  implementation. Unlike `python-avahi` (which requires the Avahi D-Bus
  daemon and only works on Linux), `zeroconf` works anywhere Python runs
  and has no system dependencies. This keeps the daemon portable and the
  dependency tree simple.
"""

import logging
import socket

from zeroconf import ServiceInfo
from zeroconf.asyncio import AsyncZeroconf

from jibe import __version__
from jibe.config import DEFAULT_PORT, SERVICE_NAME, SERVICE_TYPE

logger = logging.getLogger(__name__)


def _get_local_ip() -> str:
    """Get the machine's LAN IP address.

    This uses a common trick: we open a UDP socket "connected" to an
    external IP (we never actually send anything) and check which local
    interface the OS would route through. This avoids hardcoding an
    interface name and works on any network configuration.

    Returns:
        The local IPv4 address as a string (e.g. "192.168.1.42").
        Falls back to "127.0.0.1" if detection fails.
    """
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(("1.1.1.1", 80))
            return sock.getsockname()[0]
    except OSError:
        logger.warning("Could not detect LAN IP, falling back to 127.0.0.1")
        return "127.0.0.1"


class JibeDiscovery:
    """Manages mDNS service registration for the Jibe daemon.

    Advertises a `_jibe._tcp.local.` service on the LAN so Android
    clients can find the daemon without knowing its IP. The service
    record includes TXT properties (version, platform) that clients
    can read before even connecting.

    Usage:
        discovery = JibeDiscovery(port=8765)
        await discovery.start()
        # ... daemon is now discoverable ...
        await discovery.stop()
    """

    def __init__(self, port: int = DEFAULT_PORT) -> None:
        """Initialise discovery with the port the server will listen on.

        Args:
            port: The TCP port that the WebSocket server is bound to.
                  This is embedded in the mDNS record so clients know
                  where to connect.
        """
        self._port = port

        self._async_zc: AsyncZeroconf | None = None
        self._service_info: ServiceInfo | None = None

    async def start(self) -> None:
        """Register the Jibe service on the local network.

        Creates an AsyncZeroconf instance and registers a ServiceInfo
        record. After this call, `avahi-browse -t _jibe._tcp` (or any
        mDNS browser) will show the Jibe daemon.
        """
        local_ip = _get_local_ip()

        self._service_info = ServiceInfo(
            type_=SERVICE_TYPE,
            name=f"{SERVICE_NAME}.{SERVICE_TYPE}",
            port=self._port,
            properties={
                "version": __version__,
                "platform": "linux",
            },
            addresses=[socket.inet_aton(local_ip)],
        )

        self._async_zc = AsyncZeroconf()
        await self._async_zc.async_register_service(self._service_info)

        logger.info(
            "mDNS service registered: %s on %s:%d",
            self._service_info.name,
            local_ip,
            self._port,
        )

    async def stop(self) -> None:
        """Unregister the service and shut down the Zeroconf engine.

        Sends a "goodbye" multicast packet so other devices on the
        network know this service is no longer available — they won't
        have to wait for the record to expire.
        """
        if self._async_zc and self._service_info:
            logger.info("Unregistering mDNS service...")
            await self._async_zc.async_unregister_service(self._service_info)
            await self._async_zc.async_close()
            self._async_zc = None
            self._service_info = None
            logger.info("mDNS service unregistered")
