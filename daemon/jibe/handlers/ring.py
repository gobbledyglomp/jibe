"""Ring / Find-my-phone handler.

Outbound-only: the daemon sends ``device.ring`` to a connected Android device.
There is no inbound handler because this message type flows Linux → Android.
"""

from __future__ import annotations

import json
import logging
from typing import Any

from jibe.core.api import MessageType
from jibe.network.connection import JibeConnection

logger = logging.getLogger(__name__)


async def send_ring(
    conn: JibeConnection,
    *,
    event_log: Any | None = None,
) -> None:
    """Send a ``device.ring`` command to the given connection.

    Args:
        conn: Authenticated connection representing the target Android device.
        event_log: Optional event log for dashboard visibility.
    """
    payload = json.dumps({"type": MessageType.DEVICE_RING.value})
    await conn.send(payload)
    logger.info("Sent device.ring to %s (%s)", conn.device_name or "device", conn.id)
    if event_log is not None:
        event_log.record(
            "ring",
            f"Ring sent to {conn.device_name or 'device'}",
            device_name=conn.device_name,
            device_id=conn.device_id,
        )
