"""Outbound application-layer ping probes (RTT via echoed ``probe`` in pong)."""

from __future__ import annotations

import secrets
import time


class PingProbeTracker:
    """Maps outbound ``probe`` ids to monotonic start times for RTT measurement."""

    def __init__(self, max_pending: int = 64) -> None:
        self._pending: dict[str, float] = {}
        self._max_pending = max_pending

    def start_probe(self) -> str:
        if len(self._pending) >= self._max_pending:
            self._pending.clear()
        probe_id = secrets.token_hex(8)
        self._pending[probe_id] = time.monotonic()
        return probe_id

    def finish_probe(self, probe_id: str) -> float | None:
        t0 = self._pending.pop(probe_id, None)
        if t0 is None:
            return None
        return (time.monotonic() - t0) * 1000.0
