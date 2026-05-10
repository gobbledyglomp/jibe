"""In-memory event log for the localhost dashboard (browser-visible)."""

from __future__ import annotations

import time
from collections import deque
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any


@dataclass(frozen=True)
class DashboardEvent:
    """One row in the daemon event log."""

    at_unix: float
    category: str
    level: str
    message: str
    detail: dict[str, Any]

    def as_dict(self) -> dict[str, Any]:
        iso = datetime.fromtimestamp(self.at_unix, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        out: dict[str, Any] = {
            "at": iso,
            "category": self.category,
            "level": self.level,
            "message": self.message,
        }
        if self.detail:
            out["detail"] = self.detail
        return out


class DashboardEventLog:
    """Fixed-length deque of recent daemon-local events."""

    def __init__(self, maxlen: int = 256) -> None:
        self._entries: deque[DashboardEvent] = deque(maxlen=maxlen)

    def record(
        self,
        category: str,
        message: str,
        *,
        level: str = "info",
        **detail: Any,
    ) -> None:
        cleaned = {k: v for k, v in detail.items() if v is not None}
        self._entries.append(
            DashboardEvent(
                time.time(),
                category,
                level,
                message,
                cleaned,
            )
        )

    def list_dicts(self) -> list[dict[str, Any]]:
        return [e.as_dict() for e in self._entries]
