"""Async SQLite database access for persistent storage.

This module provides the persistence layer for the Jibe daemon. Everything
the daemon needs to remember across restarts lives here: trusted devices,
session history, and (in future milestones) transfer logs, clipboard
history, and notification records.

Why SQLite?
  SQLite is the ideal database for a single-user, self-hosted daemon:
  no server process, no configuration, the database is just a file.
  It supports concurrent reads, handles WAL mode gracefully, and is
  battle-tested in embedded systems far more demanding than ours.

Why aiosqlite?
  Python's built-in `sqlite3` module is synchronous — every query blocks
  the thread. Since our daemon runs on a single asyncio event loop,
  blocking it would freeze WebSocket handling, mDNS, and everything else.
  `aiosqlite` wraps `sqlite3` in a background thread and exposes an
  async interface, so database operations don't block the event loop.

Storage location:
  The database file lives at `~/.local/share/jibe/jibe.db`, following
  the XDG Base Directory Specification.
"""

import logging
from datetime import datetime, timezone
from pathlib import Path

import aiosqlite

from jibe.core.config import DATABASE_DIR, DATABASE_NAME, SCHEMA_VERSION

logger = logging.getLogger(__name__)


# ── SQL Schema ───────────────────────────────────────────────────────────

_CREATE_META_TABLE = """
CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
"""

_CREATE_DEVICES_TABLE = """
CREATE TABLE IF NOT EXISTS devices (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    fingerprint TEXT NOT NULL UNIQUE,
    paired_at   TEXT NOT NULL,
    last_seen   TEXT NOT NULL
);
"""

_CREATE_SESSIONS_TABLE = """
CREATE TABLE IF NOT EXISTS sessions (
    id         TEXT PRIMARY KEY,
    device_id  TEXT NOT NULL,
    started_at TEXT NOT NULL,
    ended_at   TEXT,
    FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE CASCADE
);
"""

_ALL_TABLES = [
    _CREATE_META_TABLE,
    _CREATE_DEVICES_TABLE,
    _CREATE_SESSIONS_TABLE,
]


def _now_iso() -> str:
    """Return the current UTC time as an ISO 8601 string."""
    return datetime.now(timezone.utc).isoformat()


class JibeDatabase:
    """Async interface to the Jibe SQLite database.

    Manages the database lifecycle (open, migrate, close) and provides
    typed CRUD methods for each table. All methods are async because
    the underlying aiosqlite connection runs queries in a background
    thread.

    Usage:
        db = JibeDatabase()
        await db.open()
        devices = await db.list_devices()
        await db.close()

    Or as an async context manager:
        async with JibeDatabase() as db:
            devices = await db.list_devices()
    """

    def __init__(self, db_path: Path | None = None) -> None:
        """Initialise the database.

        Args:
            db_path: Full path to the SQLite database file. If None,
                     uses the default XDG-compliant path. Passing a
                     custom path is mainly useful for testing (e.g.
                     using an in-memory database or a temp directory).
        """
        self._db_path = db_path or (DATABASE_DIR / DATABASE_NAME)
        self._conn: aiosqlite.Connection | None = None

    async def open(self) -> None:
        """Open the database connection and ensure the schema exists.

        Creates the parent directory if it doesn't exist (first run).
        Enables WAL mode for better concurrent read performance and
        foreign key enforcement (SQLite disables FK checks by default).
        """
        self._db_path.parent.mkdir(parents=True, exist_ok=True)

        self._conn = await aiosqlite.connect(str(self._db_path))

        await self._conn.execute("PRAGMA journal_mode=WAL;")
        await self._conn.execute("PRAGMA foreign_keys=ON;")

        await self._create_tables()
        await self._check_schema_version()

        logger.debug("Database opened: %s", self._db_path)

    async def close(self) -> None:
        """Close the database connection cleanly.

        Safe to call even if the database was never opened or was
        already closed — this makes cleanup in shutdown handlers simpler.
        """
        if self._conn:
            await self._conn.close()
            self._conn = None
            logger.debug("Database closed")

    # ── Async context manager ────────────────────────────────────────

    async def __aenter__(self) -> "JibeDatabase":
        """Support `async with JibeDatabase() as db:` syntax."""
        await self.open()
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb) -> None:
        """Ensure the database is closed when exiting the context."""
        await self.close()

    # ── Schema management ────────────────────────────────────────────

    async def _create_tables(self) -> None:
        """Create all tables if they don't exist.

        This is idempotent: running it on an existing database with all
        tables already created is a no-op. This lets us call it on every
        startup without worrying about whether this is a first run or not.
        """
        for sql in _ALL_TABLES:
            await self._conn.execute(sql)
        await self._conn.commit()

    async def _check_schema_version(self) -> None:
        """Verify or initialise the schema version.

        On first run, inserts the current schema version into the meta
        table. On subsequent runs, reads it and logs a warning if it
        doesn't match. Full migration logic will be added when we
        actually need to change the schema — for now, the version number
        is a safety net that tells us "this database was created by
        version X of the daemon."
        """
        cursor = await self._conn.execute(
            "SELECT value FROM meta WHERE key = 'schema_version';"
        )
        row = await cursor.fetchone()

        if row is None:
            await self._conn.execute(
                "INSERT INTO meta (key, value) VALUES ('schema_version', ?);",
                (str(SCHEMA_VERSION),),
            )
            await self._conn.commit()
            logger.debug("Initialised schema version %d", SCHEMA_VERSION)
        else:
            stored_version = int(row[0])
            if stored_version != SCHEMA_VERSION:
                # In the future, this is where migration logic would run.
                # For now, we just log a warning so the developer knows
                # the database was created by a different version.
                logger.warning(
                    "Schema version mismatch: database has v%d, daemon expects v%d",
                    stored_version,
                    SCHEMA_VERSION,
                )
            else:
                logger.debug("Schema version OK: v%d", stored_version)

    # ── Device CRUD ──────────────────────────────────────────────────

    async def add_device(self, device_id: str, name: str, fingerprint: str) -> dict:
        """Add a newly paired device to the database.

        Args:
            device_id: Unique identifier for the device (UUID).
            name: Human-readable device name (e.g. "Pixel 8 Pro").
            fingerprint: SHA-256 fingerprint that uniquely identifies
                         this device for future reconnections.

        Returns:
            A dict representing the newly created device row.

        Raises:
            aiosqlite.IntegrityError: If a device with the same
                fingerprint already exists.
        """
        now = _now_iso()
        await self._conn.execute(
            """
            INSERT INTO devices (id, name, fingerprint, paired_at, last_seen)
            VALUES (?, ?, ?, ?, ?);
            """,
            (device_id, name, fingerprint, now, now),
        )
        await self._conn.commit()
        logger.debug("Device paired: %s (%s)", name, device_id)

        return {
            "id": device_id,
            "name": name,
            "fingerprint": fingerprint,
            "paired_at": now,
            "last_seen": now,
        }

    async def get_device_by_fingerprint(self, fingerprint: str) -> dict | None:
        """Look up a device by its fingerprint.

        This is the primary lookup method during reconnection: the
        connecting device presents its fingerprint, and we check
        whether we've seen it before.

        Args:
            fingerprint: The SHA-256 fingerprint to search for.

        Returns:
            A dict with the device's fields, or None if not found.
        """
        cursor = await self._conn.execute(
            "SELECT id, name, fingerprint, paired_at, last_seen FROM devices WHERE fingerprint = ?;",
            (fingerprint,),
        )
        row = await cursor.fetchone()

        if row is None:
            return None

        return {
            "id": row[0],
            "name": row[1],
            "fingerprint": row[2],
            "paired_at": row[3],
            "last_seen": row[4],
        }

    async def get_device_by_id(self, device_id: str) -> dict | None:
        """Look up a device by its ID.

        Args:
            device_id: The UUID of the device.

        Returns:
            A dict with the device's fields, or None if not found.
        """
        cursor = await self._conn.execute(
            "SELECT id, name, fingerprint, paired_at, last_seen FROM devices WHERE id = ?;",
            (device_id,),
        )
        row = await cursor.fetchone()

        if row is None:
            return None

        return {
            "id": row[0],
            "name": row[1],
            "fingerprint": row[2],
            "paired_at": row[3],
            "last_seen": row[4],
        }

    async def list_devices(self) -> list[dict]:
        """Return all paired devices, ordered by most recently seen.

        Returns:
            A list of dicts, each representing a device. Empty list
            if no devices are paired.
        """
        cursor = await self._conn.execute(
            "SELECT id, name, fingerprint, paired_at, last_seen FROM devices ORDER BY last_seen DESC;"
        )
        rows = await cursor.fetchall()

        return [
            {
                "id": row[0],
                "name": row[1],
                "fingerprint": row[2],
                "paired_at": row[3],
                "last_seen": row[4],
            }
            for row in rows
        ]

    async def update_last_seen(self, device_id: str) -> bool:
        """Update the last_seen timestamp for a device.

        Called every time an authenticated device sends a message or
        reconnects, so the web dashboard can show "last active" times.

        Args:
            device_id: The UUID of the device to update.

        Returns:
            True if the device was found and updated, False if the
            device_id doesn't exist in the database.
        """
        now = _now_iso()
        cursor = await self._conn.execute(
            "UPDATE devices SET last_seen = ? WHERE id = ?;",
            (now, device_id),
        )
        await self._conn.commit()
        return cursor.rowcount > 0

    async def remove_device(self, device_id: str) -> bool:
        """Remove a paired device from the database.

        This also cascades to delete all sessions associated with
        the device (via the FOREIGN KEY ON DELETE CASCADE constraint).

        Args:
            device_id: The UUID of the device to remove.

        Returns:
            True if the device was found and removed, False if it
            didn't exist (idempotent — removing a non-existent device
            is not an error).
        """
        cursor = await self._conn.execute(
            "DELETE FROM devices WHERE id = ?;",
            (device_id,),
        )
        await self._conn.commit()

        removed = cursor.rowcount > 0
        if removed:
            logger.debug("Device unpaired: %s", device_id)
        return removed

    # ── Session tracking ─────────────────────────────────────────────

    async def start_session(self, session_id: str, device_id: str) -> dict:
        """Record the start of a device connection session.

        Args:
            session_id: Unique identifier for this session (UUID).
            device_id: The device that connected.

        Returns:
            A dict representing the new session row.
        """
        now = _now_iso()
        await self._conn.execute(
            """
            INSERT INTO sessions (id, device_id, started_at)
            VALUES (?, ?, ?);
            """,
            (session_id, device_id, now),
        )
        await self._conn.commit()

        return {
            "id": session_id,
            "device_id": device_id,
            "started_at": now,
            "ended_at": None,
        }

    async def end_session(self, session_id: str) -> bool:
        """Record the end of a device connection session.

        Args:
            session_id: The session to close.

        Returns:
            True if the session was found and updated, False otherwise.
        """
        now = _now_iso()
        cursor = await self._conn.execute(
            "UPDATE sessions SET ended_at = ? WHERE id = ?;",
            (now, session_id),
        )
        await self._conn.commit()
        return cursor.rowcount > 0
