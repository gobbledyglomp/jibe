"""Battery level handler.

Receives ``device.battery`` messages from Android and caches the last-known
level per device.  The cache is intentionally ephemeral (in-memory only) —
battery level is transient telemetry, not data that needs to survive a daemon
restart.
"""

from __future__ import annotations

import logging
from typing import Any

from jibe.core.api import JibeMessage, format_error
from jibe.network.connection import JibeConnection

logger = logging.getLogger(__name__)

# device_id → {"level": int, "charging": bool}
_battery_cache: dict[str, dict[str, Any]] = {}

BATTERY_LEVEL_MIN = 0
BATTERY_LEVEL_MAX = 100


def get_all_batteries() -> dict[str, dict[str, Any]]:
    """Return a snapshot of the current battery cache.

    Returns:
        Dict mapping device_id to ``{"level": int, "charging": bool}``.
    """
    return dict(_battery_cache)


def forget_battery(device_id: str | None) -> None:
    """Remove cached battery row when the device disconnects."""
    if device_id:
        _battery_cache.pop(device_id, None)


def get_battery(device_id: str) -> dict[str, Any] | None:
    """Return cached battery info for a single device, or ``None`` if unknown.

    Args:
        device_id: The device whose battery state to look up.
    """
    return _battery_cache.get(device_id)


async def handle_battery(
    conn: JibeConnection,
    msg: JibeMessage,
) -> None:
    """Cache the battery level reported by the connected Android device.

    Args:
        conn: Authenticated connection that sent the message.
        msg: Parsed message; expects ``level`` (int 0–100) and ``charging`` (bool).
    """
    payload = msg.payload
    level = payload.get("level")
    charging = payload.get("charging")

    if not isinstance(level, int) or not (BATTERY_LEVEL_MIN <= level <= BATTERY_LEVEL_MAX):
        await conn.send(format_error("malformed_payload", "device.battery requires integer level 0–100"))
        return
    if not isinstance(charging, bool):
        await conn.send(format_error("malformed_payload", "device.battery requires boolean charging"))
        return

    if conn.device_id is None:
        logger.warning("Received device.battery from unauthenticated connection %s", conn.id)
        return

    _battery_cache[conn.device_id] = {"level": level, "charging": charging}
    logger.debug(
        "Battery update from %s (%s): %d%% charging=%s",
        conn.device_name or conn.device_id,
        conn.device_id,
        level,
        charging,
    )
