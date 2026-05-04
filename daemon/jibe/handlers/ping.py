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


async def handle_ping(conn: JibeConnection, msg: JibeMessage) -> None:
    """Respond to a ping with a pong.

    Args:
        conn: The authenticated connection that sent the ping.
        msg: The ping message (payload is ignored).
    """
    pong = json.dumps({"type": MessageType.PONG.value})
    await conn.send(pong)
    logger.debug("Sent pong to %s", conn.id)
