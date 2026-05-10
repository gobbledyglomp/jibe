"""Inbound ``device.features`` — client updates feature toggles after authentication."""

from __future__ import annotations

import logging

from jibe.core.api import JibeMessage
from jibe.network.connection import JibeConnection

logger = logging.getLogger(__name__)


async def handle_device_features(conn: JibeConnection, msg: JibeMessage) -> None:
    """Apply ``feat_find_phone`` from the payload when present."""
    payload = msg.payload
    if "feat_find_phone" in payload:
        conn.feat_find_phone = bool(payload["feat_find_phone"])
        logger.debug(
            "device.features: feat_find_phone=%s (%s)",
            conn.feat_find_phone,
            conn.device_name or conn.id,
        )
