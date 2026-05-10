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
DASHBOARD_RECOVERY_KEY_FILE = "dashboard_recovery.key"
SCHEMA_VERSION = 2

JWT_SECRET_META_KEY = "jwt_secret"
JWT_EXPIRY_SECONDS = 86400

PIN_LENGTH = 6
PIN_EXPIRY_SECONDS = 120
MAX_PIN_ATTEMPTS = 5

WS_HEARTBEAT_SECONDS = 30.0
AUTH_TIMEOUT_SECONDS = PIN_EXPIRY_SECONDS

# Largest inbound WebSocket text frame (JSON file.chunk payloads are Base64-expanded).
WS_MAX_MESSAGE_BYTES = 16 * 1024 * 1024

# Raw bytes per file chunk on the wire (must match Android CHUNK_SIZE_BYTES).
FILE_TRANSFER_CHUNK_RAW_BYTES = 4 * 1024 * 1024

CERTS_DIR = _xdg_data_home / "jibe" / "certs"
CERT_FILE = "jibe.crt"
KEY_FILE = "jibe.key"
CERT_VALIDITY_DAYS = 3650
CERT_KEY_SIZE = 4096

LOG_FORMAT = "%(asctime)s [%(levelname)s] %(name)s: %(message)s"
LOG_DATE_FORMAT = "%H:%M:%S"
