"""Daemon-wide constants and configuration.

Centralises all "magic values" so they can be changed in one place.
This module will grow as new features are added — for now it holds
the network and mDNS settings needed by the discovery and server
modules, plus logging format defaults.
"""

# ── Network ──────────────────────────────────────────────────────────────

DEFAULT_PORT = 8765

# ── mDNS / Zeroconf ─────────────────────────────────────────────────────

SERVICE_TYPE = "_jibe._tcp.local."
SERVICE_NAME = "Jibe"

# ── Logging ──────────────────────────────────────────────────────────────

LOG_FORMAT = "%(asctime)s [%(levelname)s] %(name)s: %(message)s"
LOG_DATE_FORMAT = "%H:%M:%S"