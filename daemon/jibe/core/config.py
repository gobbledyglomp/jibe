"""Daemon-wide constants and configuration.

Centralises all "magic values" so they can be changed in one place.
This module will grow as new features are added. Current sections:
network, mDNS, database, and logging.
"""

import os
from pathlib import Path

DEFAULT_PORT = 8765

SERVICE_TYPE = "_jibe._tcp.local."
SERVICE_NAME = "Jibe"

_xdg_data_home = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share"))
DATABASE_DIR = _xdg_data_home / "jibe"
DATABASE_NAME = "jibe.db"
SCHEMA_VERSION = 1

PIN_LENGTH = 6
PIN_EXPIRY_SECONDS = 120
MAX_PIN_ATTEMPTS = 5

WS_HEARTBEAT_SECONDS = 30.0
AUTH_TIMEOUT_SECONDS = PIN_EXPIRY_SECONDS

CERTS_DIR = _xdg_data_home / "jibe" / "certs"
CERT_FILE = "jibe.crt"
KEY_FILE = "jibe.key"
CERT_VALIDITY_DAYS = 3650
CERT_KEY_SIZE = 4096

LOG_FORMAT = "%(asctime)s [%(levelname)s] %(name)s: %(message)s"
LOG_DATE_FORMAT = "%H:%M:%S"
