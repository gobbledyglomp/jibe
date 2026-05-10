"""Ping/pong handler for the Jibe protocol.

Responds to `ping` messages with a `pong`. This is the simplest handler
and serves as a reference implementation for how to write a handler:

    async def handle_something(conn: JibeConnection, msg: JibeMessage) -> None:
        # do work ...
        await conn.send(json.dumps({...}))

The ping/pong mechanism is distinct from the WebSocket-level heartbeat
(which is handled by aiohttp automatically). This is an application-layer
keepalive that the Android client can use to measure round-trip latency
and confirm the daemon is responsive at the protocol level.
"""

import json
import logging

from jibe.core.api import JibeMessage, MessageType
from jibe.network.connection import JibeConnection

logger = logging.getLogger(__name__)


async def handle_ping(
    conn: JibeConnection,
    msg: JibeMessage,
    *,
    activity_log=None,
) -> None:
    """Respond to a ping with a pong.

    Echoes optional ``client_ts`` / ``ts`` and ``probe`` fields so clients can
    measure RTT or correlate dashboard-initiated probes.

    Args:
        conn: The authenticated connection that sent the ping.
        msg: The ping message (optional payload fields echoed when present).
        activity_log: When set, records an incoming ping for the dashboard.
    """
    payload = msg.payload if isinstance(msg.payload, dict) else {}
    body: dict = {"type": MessageType.PONG.value}
    if "client_ts" in payload:
        body["client_ts"] = payload["client_ts"]
    elif "ts" in payload:
        body["client_ts"] = payload["ts"]
    if "probe" in payload:
        body["probe"] = payload["probe"]

    await conn.send(json.dumps(body))
    if activity_log is not None and conn.is_authenticated:
        activity_log.record_incoming(conn.device_name or "device", conn.device_id)
    logger.debug("Sent pong to %s", conn.id)
