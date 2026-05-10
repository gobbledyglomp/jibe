"""In-memory log of recent application-layer ping activity for the dashboard."""

from __future__ import annotations

import time
from collections import deque
from dataclasses import dataclass
from datetime import datetime, timezone

import secrets


@dataclass(frozen=True)
class PingActivityEntry:
    """One dashboard-visible ping event."""

    at_unix: float
    direction: str  # "in" | "out"
    device_name: str
    device_id: str | None
    rtt_ms: float | None

    def as_dict(self) -> dict:
        iso = datetime.fromtimestamp(self.at_unix, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        d: dict = {
            "at": iso,
            "direction": self.direction,
            "device_name": self.device_name,
            "device_id": self.device_id,
        }
        if self.rtt_ms is not None:
            d["rtt_ms"] = round(self.rtt_ms, 2)
        return d


class PingActivityLog:
    """Fixed-length deque of recent ping-related events (daemon-local)."""

    def __init__(self, maxlen: int = 48) -> None:
        self._entries: deque[PingActivityEntry] = deque(maxlen=maxlen)

    def record_incoming(self, device_name: str, device_id: str | None) -> None:
        self._entries.append(
            PingActivityEntry(
                time.time(),
                "in",
                device_name,
                device_id,
                None,
            )
        )

    def record_outbound(self, device_name: str, device_id: str | None, rtt_ms: float) -> None:
        self._entries.append(
            PingActivityEntry(
                time.time(),
                "out",
                device_name,
                device_id,
                rtt_ms,
            )
        )

    def list_dicts(self) -> list[dict]:
        return [e.as_dict() for e in self._entries]


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
