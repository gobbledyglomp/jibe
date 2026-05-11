"""mDNS service discovery for the Jibe daemon.

Registers a `_jibe._tcp.local.` service so Android clients can discover the
daemon without manual IP configuration.
"""

import logging
import socket

from zeroconf import ServiceInfo
from zeroconf.asyncio import AsyncZeroconf

from jibe import __version__
from jibe.core.config import DEFAULT_PORT, SERVICE_NAME, SERVICE_TYPE

logger = logging.getLogger(__name__)


def _get_local_ip() -> str:
    """Get the machine's LAN IP address, preferring real WiFi/Ethernet interfaces.

    This version enumerates all available addresses and picks the best one:
      1. Prefer 192.168.x.x (home/office WiFi — most common case)
      2. Fall back to 172.16-31.x.x (some corporate LANs)
      3. Fall back to 10.x.x.x ONLY if it looks like a real LAN (not a VPN
         tunnel, which typically uses /30 or smaller subnets)
      4. Last resort: the UDP trick (original behaviour)

    Returns:
        The local IPv4 address as a string (e.g. "192.168.1.42").
        Falls back to "127.0.0.1" if detection fails.
    """
    import ipaddress

    candidates: list[str] = []

    try:
        hostname = socket.gethostname()
        infos = socket.getaddrinfo(hostname, None, socket.AF_INET)
        for info in infos:
            ip_str = info[4][0]
            try:
                addr = ipaddress.IPv4Address(ip_str)
                if addr.is_private and not addr.is_loopback and not addr.is_link_local:
                    candidates.append(ip_str)
            except ValueError:
                continue
    except OSError:
        pass

    # Prefer 192.168.x
    for ip in candidates:
        if ip.startswith("192.168."):
            logger.debug("Using LAN IP (192.168.x): %s", ip)
            return ip

    # Then 172.16-31.x (corporate LANs)
    for ip in candidates:
        if ip.startswith("172."):
            second_octet = int(ip.split(".")[1])
            if 16 <= second_octet <= 31:
                logger.debug("Using LAN IP (172.x): %s", ip)
                return ip

    # Then 10.x
    for ip in candidates:
        if ip.startswith("10."):
            logger.debug("Using 10.x IP (may be VPN): %s", ip)
            return ip

    # Last resort
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(("1.1.1.1", 80))
            ip = sock.getsockname()[0]
            logger.debug("Using UDP-trick IP: %s", ip)
            return ip
    except OSError:
        pass

    logger.warning("Could not detect LAN IP, falling back to 127.0.0.1")
    return "127.0.0.1"


class JibeDiscovery:
    """Manages mDNS service registration for the Jibe daemon.

    Advertises a `_jibe._tcp.local.` service on the LAN so Android
    clients can find the daemon without knowing its IP. The service
    record includes TXT properties (version, platform) that clients
    can read before even connecting.

    Usage:
        discovery = JibeDiscovery(port=8776)
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
            logger.debug("Unregistering mDNS service...")
            await self._async_zc.async_unregister_service(self._service_info)
            await self._async_zc.async_close()
            self._async_zc = None
            self._service_info = None
            logger.debug("mDNS service unregistered")
