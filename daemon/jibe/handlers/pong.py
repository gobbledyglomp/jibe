"""Application-layer pong handler (round-trip probes initiated by the daemon)."""

from __future__ import annotations

import logging

from jibe.core.api import JibeMessage
from jibe.network.connection import JibeConnection

logger = logging.getLogger(__name__)


async def handle_pong(
    conn: JibeConnection,
    msg: JibeMessage,
    *,
    probe_tracker=None,
    activity_log=None,
) -> None:
    """Complete an outbound ping probe when the client echoes ``probe``."""
    payload = msg.payload if isinstance(msg.payload, dict) else {}
    probe = payload.get("probe")
    if (
        not probe
        or not isinstance(probe, str)
        or probe_tracker is None
        or activity_log is None
    ):
        return
    rtt_ms = probe_tracker.finish_probe(probe)
    if rtt_ms is None:
        return
    activity_log.record(
        "ping",
        f"Pong RTT {rtt_ms:.1f} ms ({conn.device_name or 'device'})",
        direction="out",
        device_name=conn.device_name,
        device_id=conn.device_id,
        rtt_ms=round(rtt_ms, 2),
    )
    logger.debug("pong probe %s rtt=%.2fms (%s)", probe[:8], rtt_ms, conn.device_name)
