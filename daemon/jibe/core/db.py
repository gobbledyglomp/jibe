"""Async SQLite persistence for the Jibe daemon.

Stores trusted devices, sessions, dashboard users, and activity history.
Uses `aiosqlite` so database operations don't block the asyncio event loop.
"""

from __future__ import annotations

import logging
import math
import secrets
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import aiosqlite
import bcrypt

from jibe.core.config import DATABASE_DIR, DATABASE_NAME, SCHEMA_VERSION

logger = logging.getLogger(__name__)


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

_CREATE_USERS_TABLE = """
CREATE TABLE IF NOT EXISTS users (
    id             TEXT PRIMARY KEY,
    username       TEXT NOT NULL UNIQUE,
    password_hash  TEXT NOT NULL,
    role           TEXT NOT NULL CHECK(role IN ('admin','viewer')),
    created_at     TEXT NOT NULL
);
"""

_CREATE_TRANSFER_HISTORY_TABLE = """
CREATE TABLE IF NOT EXISTS transfer_history (
    id          TEXT PRIMARY KEY,
    device_id   TEXT,
    filename    TEXT NOT NULL,
    size        INTEGER NOT NULL,
    direction   TEXT NOT NULL CHECK(direction IN ('incoming','outgoing')),
    status      TEXT NOT NULL,
    started_at  TEXT NOT NULL,
    finished_at TEXT
);
"""

_CREATE_CLIPBOARD_HISTORY_TABLE = """
CREATE TABLE IF NOT EXISTS clipboard_history (
    id         TEXT PRIMARY KEY,
    content    TEXT NOT NULL,
    source     TEXT NOT NULL,
    direction  TEXT NOT NULL,
    created_at TEXT NOT NULL
);
"""

_CREATE_NOTIFICATION_LOG_TABLE = """
CREATE TABLE IF NOT EXISTS notification_log (
    id          TEXT PRIMARY KEY,
    device_id   TEXT,
    app         TEXT NOT NULL,
    title       TEXT NOT NULL,
    body        TEXT NOT NULL,
    timestamp   TEXT NOT NULL,
    received_at TEXT NOT NULL
);
"""

_ALL_TABLES = [
    _CREATE_META_TABLE,
    _CREATE_DEVICES_TABLE,
    _CREATE_SESSIONS_TABLE,
    _CREATE_USERS_TABLE,
    _CREATE_TRANSFER_HISTORY_TABLE,
    _CREATE_CLIPBOARD_HISTORY_TABLE,
    _CREATE_NOTIFICATION_LOG_TABLE,
]


def _now_iso() -> str:
    """Return the current UTC time as an ISO 8601 string."""
    return datetime.now(timezone.utc).isoformat()


def _paginate_meta(total: int, page: int, per_page: int) -> dict[str, int]:
    """Build pagination metadata for list endpoints."""
    pages = max(1, math.ceil(total / per_page)) if per_page > 0 else 1
    return {
        "total": total,
        "page": page,
        "per_page": per_page,
        "pages": pages,
    }


class JibeDatabase:
    """Async interface to the Jibe SQLite database."""

    def __init__(self, db_path: Path | None = None) -> None:
        self._db_path = db_path or (DATABASE_DIR / DATABASE_NAME)
        self._conn: aiosqlite.Connection | None = None

    async def open(self) -> None:
        """Open the database connection and ensure the schema exists."""
        self._db_path.parent.mkdir(parents=True, exist_ok=True)

        self._conn = await aiosqlite.connect(str(self._db_path))

        await self._conn.execute("PRAGMA journal_mode=WAL;")
        await self._conn.execute("PRAGMA foreign_keys=ON;")

        await self._create_tables()
        await self._check_schema_version()

        logger.debug("Database opened: %s", self._db_path)

    async def close(self) -> None:
        """Close the database connection cleanly."""
        if self._conn:
            await self._conn.close()
            self._conn = None
            logger.debug("Database closed")

    async def __aenter__(self) -> JibeDatabase:
        await self.open()
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb) -> None:
        await self.close()

    async def _create_tables(self) -> None:
        for sql in _ALL_TABLES:
            await self._conn.execute(sql)
        await self._conn.commit()

    async def _check_schema_version(self) -> None:
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
            return

        stored_version = int(row[0])
        while stored_version < SCHEMA_VERSION:
            next_v = stored_version + 1
            await self._migrate_schema(stored_version, next_v)
            stored_version = next_v

        if stored_version != SCHEMA_VERSION:
            logger.warning(
                "Schema version mismatch: database has v%d, daemon expects v%d",
                stored_version,
                SCHEMA_VERSION,
            )
        else:
            logger.debug("Schema version OK: v%d", stored_version)

    async def _migrate_schema(self, from_version: int, to_version: int) -> None:
        """Apply one incremental schema migration."""
        if from_version == 1 and to_version == 2:
            await self._conn.execute(
                "UPDATE meta SET value = ? WHERE key = 'schema_version';",
                (str(2),),
            )
            await self._conn.commit()
            logger.info("Migrated database schema from v1 to v2")
            return
        raise RuntimeError(f"No migration path from v{from_version} to v{to_version}")

    async def ensure_default_admin_if_empty(self) -> str | None:
        """If no dashboard users exist, create ``admin`` with a random password.

        Returns:
            Plaintext password when a user was created, otherwise ``None``.
        """
        cursor = await self._conn.execute("SELECT COUNT(*) FROM users;")
        row = await cursor.fetchone()
        assert row is not None
        if int(row[0]) > 0:
            return None

        password = secrets.token_urlsafe(9)
        hashed = bcrypt.hashpw(password.encode(), bcrypt.gensalt(rounds=12)).decode()
        user_id = secrets.token_hex(16)
        now = _now_iso()
        await self._conn.execute(
            """
            INSERT INTO users (id, username, password_hash, role, created_at)
            VALUES (?, ?, ?, 'admin', ?);
            """,
            (user_id, "admin", hashed, now),
        )
        await self._conn.commit()
        logger.warning(
            "\n%s\n  Jibe dashboard: created default user 'admin' with password:\n  %s\n  "
            "Log in at http://127.0.0.1:<port>/web/ — change this password after first login.\n%s",
            "=" * 72,
            password,
            "=" * 72,
        )
        return password

    async def count_users(self) -> int:
        """Return the number of dashboard users."""
        cursor = await self._conn.execute("SELECT COUNT(*) FROM users;")
        row = await cursor.fetchone()
        assert row is not None
        return int(row[0])

    async def add_user(self, username: str, password_plain: str, role: str) -> dict:
        """Create a dashboard user. ``role`` is ``admin`` or ``viewer``."""
        user_id = secrets.token_hex(16)
        hashed = bcrypt.hashpw(password_plain.encode(), bcrypt.gensalt(rounds=12)).decode()
        now = _now_iso()
        await self._conn.execute(
            """
            INSERT INTO users (id, username, password_hash, role, created_at)
            VALUES (?, ?, ?, ?, ?);
            """,
            (user_id, username, hashed, role, now),
        )
        await self._conn.commit()
        return {"id": user_id, "username": username, "role": role, "created_at": now}

    async def get_user_by_username(self, username: str) -> dict | None:
        """Return user row including ``password_hash``, or ``None``."""
        cursor = await self._conn.execute(
            "SELECT id, username, password_hash, role, created_at FROM users WHERE username = ?;",
            (username,),
        )
        row = await cursor.fetchone()
        if row is None:
            return None
        return {
            "id": row[0],
            "username": row[1],
            "password_hash": row[2],
            "role": row[3],
            "created_at": row[4],
        }

    async def get_user_by_id(self, user_id: str) -> dict | None:
        """Return user row without password hash."""
        cursor = await self._conn.execute(
            "SELECT id, username, role, created_at FROM users WHERE id = ?;",
            (user_id,),
        )
        row = await cursor.fetchone()
        if row is None:
            return None
        return {
            "id": row[0],
            "username": row[1],
            "role": row[2],
            "created_at": row[3],
        }

    async def list_users(self) -> list[dict]:
        """List all dashboard users (no password fields)."""
        cursor = await self._conn.execute(
            "SELECT id, username, role, created_at FROM users ORDER BY username ASC;"
        )
        rows = await cursor.fetchall()
        return [
            {"id": r[0], "username": r[1], "role": r[2], "created_at": r[3]}
            for r in rows
        ]

    async def delete_user(self, user_id: str) -> bool:
        """Delete a user by id."""
        cursor = await self._conn.execute(
            "DELETE FROM users WHERE id = ?;",
            (user_id,),
        )
        await self._conn.commit()
        return cursor.rowcount > 0

    async def update_user_role(self, user_id: str, role: str) -> bool:
        """Update a user's role."""
        cursor = await self._conn.execute(
            "UPDATE users SET role = ? WHERE id = ?;",
            (role, user_id),
        )
        await self._conn.commit()
        return cursor.rowcount > 0

    async def get_meta_value(self, key: str) -> str | None:
        """Read a value from the ``meta`` table."""
        cursor = await self._conn.execute(
            "SELECT value FROM meta WHERE key = ?;",
            (key,),
        )
        row = await cursor.fetchone()
        return None if row is None else str(row[0])

    async def set_meta_value(self, key: str, value: str) -> None:
        """Insert or replace a ``meta`` key."""
        await self._conn.execute(
            "INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?);",
            (key, value),
        )
        await self._conn.commit()

    async def add_transfer(
        self,
        transfer_id: str,
        device_id: str | None,
        filename: str,
        size: int,
        *,
        direction: str = "incoming",
        status: str = "in_progress",
    ) -> None:
        """Record the start of a file transfer."""
        now = _now_iso()
        await self._conn.execute(
            """
            INSERT INTO transfer_history
                (id, device_id, filename, size, direction, status, started_at, finished_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, NULL);
            """,
            (transfer_id, device_id, filename, size, direction, status, now),
        )
        await self._conn.commit()

    async def finish_transfer(self, transfer_id: str, status: str) -> bool:
        """Set terminal ``status`` and ``finished_at`` for a transfer."""
        now = _now_iso()
        cursor = await self._conn.execute(
            """
            UPDATE transfer_history SET status = ?, finished_at = ?
            WHERE id = ?;
            """,
            (status, now, transfer_id),
        )
        await self._conn.commit()
        return cursor.rowcount > 0

    async def get_transfer_by_id(self, transfer_id: str) -> dict | None:
        """Return one transfer row."""
        cursor = await self._conn.execute(
            """
            SELECT id, device_id, filename, size, direction, status, started_at, finished_at
            FROM transfer_history WHERE id = ?;
            """,
            (transfer_id,),
        )
        row = await cursor.fetchone()
        if row is None:
            return None
        return {
            "id": row[0],
            "device_id": row[1],
            "filename": row[2],
            "size": row[3],
            "direction": row[4],
            "status": row[5],
            "started_at": row[6],
            "finished_at": row[7],
        }

    async def list_transfers(
        self,
        *,
        device_id: str | None = None,
        status: str | None = None,
        direction: str | None = None,
        date_from: str | None = None,
        date_to: str | None = None,
        page: int = 1,
        per_page: int = 25,
    ) -> tuple[list[dict], dict[str, int]]:
        """Paginated transfer history with optional filters."""
        where: list[str] = []
        params: list[Any] = []

        if device_id:
            where.append("device_id = ?")
            params.append(device_id)
        if status:
            where.append("status = ?")
            params.append(status)
        if direction:
            where.append("direction = ?")
            params.append(direction)
        if date_from:
            where.append("started_at >= ?")
            params.append(date_from)
        if date_to:
            where.append("started_at <= ?")
            params.append(date_to)

        clause = (" WHERE " + " AND ".join(where)) if where else ""

        cursor = await self._conn.execute(
            f"SELECT COUNT(*) FROM transfer_history{clause};",
            params,
        )
        total_row = await cursor.fetchone()
        assert total_row is not None
        total = int(total_row[0])

        per_page = max(1, min(per_page, 100))
        page = max(1, page)
        offset = (page - 1) * per_page

        cursor = await self._conn.execute(
            f"""
            SELECT id, device_id, filename, size, direction, status, started_at, finished_at
            FROM transfer_history{clause}
            ORDER BY started_at DESC
            LIMIT ? OFFSET ?;
            """,
            [*params, per_page, offset],
        )
        rows = await cursor.fetchall()
        items = [
            {
                "id": r[0],
                "device_id": r[1],
                "filename": r[2],
                "size": r[3],
                "direction": r[4],
                "status": r[5],
                "started_at": r[6],
                "finished_at": r[7],
            }
            for r in rows
        ]
        return items, _paginate_meta(total, page, per_page)

    async def add_clipboard_event(
        self,
        content: str,
        source: str,
        direction: str,
        *,
        event_id: str | None = None,
    ) -> str:
        """Insert a clipboard history row; returns the event id."""
        eid = event_id or uuid.uuid4().hex
        now = _now_iso()
        await self._conn.execute(
            """
            INSERT INTO clipboard_history (id, content, source, direction, created_at)
            VALUES (?, ?, ?, ?, ?);
            """,
            (eid, content, source, direction, now),
        )
        await self._conn.commit()
        return eid

    async def get_clipboard_event(self, event_id: str) -> dict | None:
        cursor = await self._conn.execute(
            """
            SELECT id, content, source, direction, created_at
            FROM clipboard_history WHERE id = ?;
            """,
            (event_id,),
        )
        row = await cursor.fetchone()
        if row is None:
            return None
        return {
            "id": row[0],
            "content": row[1],
            "source": row[2],
            "direction": row[3],
            "created_at": row[4],
        }

    async def list_clipboard_events(
        self,
        *,
        source: str | None = None,
        direction: str | None = None,
        date_from: str | None = None,
        date_to: str | None = None,
        page: int = 1,
        per_page: int = 25,
    ) -> tuple[list[dict], dict[str, int]]:
        where: list[str] = []
        params: list[Any] = []

        if source:
            where.append("source = ?")
            params.append(source)
        if direction:
            where.append("direction = ?")
            params.append(direction)
        if date_from:
            where.append("created_at >= ?")
            params.append(date_from)
        if date_to:
            where.append("created_at <= ?")
            params.append(date_to)

        clause = (" WHERE " + " AND ".join(where)) if where else ""

        cursor = await self._conn.execute(
            f"SELECT COUNT(*) FROM clipboard_history{clause};",
            params,
        )
        total_row = await cursor.fetchone()
        assert total_row is not None
        total = int(total_row[0])

        per_page = max(1, min(per_page, 100))
        page = max(1, page)
        offset = (page - 1) * per_page

        cursor = await self._conn.execute(
            f"""
            SELECT id, content, source, direction, created_at
            FROM clipboard_history{clause}
            ORDER BY created_at DESC
            LIMIT ? OFFSET ?;
            """,
            [*params, per_page, offset],
        )
        rows = await cursor.fetchall()
        items = [
            {
                "id": r[0],
                "content": r[1],
                "source": r[2],
                "direction": r[3],
                "created_at": r[4],
            }
            for r in rows
        ]
        return items, _paginate_meta(total, page, per_page)

    async def add_notification(
        self,
        device_id: str | None,
        app: str,
        title: str,
        body: str,
        timestamp_value: str,
        *,
        event_id: str | None = None,
    ) -> str:
        """Persist a mirrored notification; returns row id."""
        eid = event_id or uuid.uuid4().hex
        now = _now_iso()
        await self._conn.execute(
            """
            INSERT INTO notification_log
                (id, device_id, app, title, body, timestamp, received_at)
            VALUES (?, ?, ?, ?, ?, ?, ?);
            """,
            (eid, device_id, app, title, body, timestamp_value, now),
        )
        await self._conn.commit()
        return eid

    async def get_notification(self, event_id: str) -> dict | None:
        cursor = await self._conn.execute(
            """
            SELECT id, device_id, app, title, body, timestamp, received_at
            FROM notification_log WHERE id = ?;
            """,
            (event_id,),
        )
        row = await cursor.fetchone()
        if row is None:
            return None
        return {
            "id": row[0],
            "device_id": row[1],
            "app": row[2],
            "title": row[3],
            "body": row[4],
            "timestamp": row[5],
            "received_at": row[6],
        }

    async def list_notifications(
        self,
        *,
        device_id: str | None = None,
        app: str | None = None,
        date_from: str | None = None,
        date_to: str | None = None,
        page: int = 1,
        per_page: int = 25,
    ) -> tuple[list[dict], dict[str, int]]:
        where: list[str] = []
        params: list[Any] = []

        if device_id:
            where.append("device_id = ?")
            params.append(device_id)
        if app:
            where.append("app LIKE ?")
            params.append(f"%{app}%")
        if date_from:
            where.append("received_at >= ?")
            params.append(date_from)
        if date_to:
            where.append("received_at <= ?")
            params.append(date_to)

        clause = (" WHERE " + " AND ".join(where)) if where else ""

        cursor = await self._conn.execute(
            f"SELECT COUNT(*) FROM notification_log{clause};",
            params,
        )
        total_row = await cursor.fetchone()
        assert total_row is not None
        total = int(total_row[0])

        per_page = max(1, min(per_page, 100))
        page = max(1, page)
        offset = (page - 1) * per_page

        cursor = await self._conn.execute(
            f"""
            SELECT id, device_id, app, title, body, timestamp, received_at
            FROM notification_log{clause}
            ORDER BY received_at DESC
            LIMIT ? OFFSET ?;
            """,
            [*params, per_page, offset],
        )
        rows = await cursor.fetchall()
        items = [
            {
                "id": r[0],
                "device_id": r[1],
                "app": r[2],
                "title": r[3],
                "body": r[4],
                "timestamp": r[5],
                "received_at": r[6],
            }
            for r in rows
        ]
        return items, _paginate_meta(total, page, per_page)

    async def rename_device(self, device_id: str, new_name: str) -> bool:
        """Rename a paired device."""
        cursor = await self._conn.execute(
            "UPDATE devices SET name = ? WHERE id = ?;",
            (new_name, device_id),
        )
        await self._conn.commit()
        return cursor.rowcount > 0

    async def add_device(self, device_id: str, name: str, fingerprint: str) -> dict:
        """Add a newly paired device to the database."""
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
        """Look up a device by its fingerprint."""
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
        """Look up a device by its ID."""
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
        """Return all paired devices, ordered by most recently seen."""
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
        """Update the last_seen timestamp for a device."""
        now = _now_iso()
        cursor = await self._conn.execute(
            "UPDATE devices SET last_seen = ? WHERE id = ?;",
            (now, device_id),
        )
        await self._conn.commit()
        return cursor.rowcount > 0

    async def remove_device(self, device_id: str) -> bool:
        """Remove a paired device from the database."""
        cursor = await self._conn.execute(
            "DELETE FROM devices WHERE id = ?;",
            (device_id,),
        )
        await self._conn.commit()

        removed = cursor.rowcount > 0
        if removed:
            logger.debug("Device unpaired: %s", device_id)
        return removed

    async def start_session(self, session_id: str, device_id: str) -> dict:
        """Record the start of a device connection session."""
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
        """Record the end of a device connection session."""
        now = _now_iso()
        cursor = await self._conn.execute(
            "UPDATE sessions SET ended_at = ? WHERE id = ?;",
            (now, session_id),
        )
        await self._conn.commit()
        return cursor.rowcount > 0

    async def get_stats(self) -> dict[str, Any]:
        """Aggregate dashboard statistics and last-7-day activity."""
        cursor = await self._conn.execute(
            """
            SELECT COUNT(*), COALESCE(SUM(size), 0)
            FROM transfer_history WHERE status = 'completed';
            """
        )
        row = await cursor.fetchone()
        assert row is not None
        transfers_completed = int(row[0])
        bytes_total = int(row[1])

        cursor = await self._conn.execute("SELECT COUNT(*) FROM clipboard_history;")
        row = await cursor.fetchone()
        assert row is not None
        clipboard_total = int(row[0])

        cursor = await self._conn.execute("SELECT COUNT(*) FROM notification_log;")
        row = await cursor.fetchone()
        assert row is not None
        notifications_total = int(row[0])

        today = datetime.now(timezone.utc).date()
        days = [(today - timedelta(days=i)).isoformat() for i in range(6, -1, -1)]

        transfers_by_day: dict[str, int] = {d: 0 for d in days}
        clipboard_by_day: dict[str, int] = {d: 0 for d in days}

        start_iso = f"{days[0]}T00:00:00+00:00"
        cursor = await self._conn.execute(
            """
            SELECT started_at FROM transfer_history
            WHERE started_at >= ?;
            """,
            (start_iso,),
        )
        for (started_at,) in await cursor.fetchall():
            if not started_at:
                continue
            try:
                day = started_at[:10]
            except Exception:
                continue
            if day in transfers_by_day:
                transfers_by_day[day] += 1

        cursor = await self._conn.execute(
            """
            SELECT created_at FROM clipboard_history
            WHERE created_at >= ?;
            """,
            (start_iso,),
        )
        for (created_at,) in await cursor.fetchall():
            if not created_at:
                continue
            day = created_at[:10]
            if day in clipboard_by_day:
                clipboard_by_day[day] += 1

        activity = [
            {
                "date": d,
                "transfers": transfers_by_day[d],
                "clipboard": clipboard_by_day[d],
            }
            for d in days
        ]

        scores: dict[str, int] = {}

        cursor = await self._conn.execute(
            """
            SELECT device_id, COUNT(*) FROM transfer_history
            WHERE device_id IS NOT NULL AND device_id != ''
            GROUP BY device_id;
            """
        )
        for did, cnt in await cursor.fetchall():
            if did:
                scores[did] = scores.get(did, 0) + int(cnt)

        cursor = await self._conn.execute(
            """
            SELECT device_id, COUNT(*) FROM notification_log
            WHERE device_id IS NOT NULL AND device_id != ''
            GROUP BY device_id;
            """
        )
        for did, cnt in await cursor.fetchall():
            if did:
                scores[did] = scores.get(did, 0) + int(cnt)

        cursor = await self._conn.execute(
            """
            SELECT source, COUNT(*) FROM clipboard_history
            WHERE direction = 'incoming' AND source IS NOT NULL AND source != '' AND source != 'local'
            GROUP BY source;
            """
        )
        for src, cnt in await cursor.fetchall():
            scores[src] = scores.get(src, 0) + int(cnt)

        top_device_id: str | None = None
        top_score = 0
        for did, sc in scores.items():
            if sc > top_score:
                top_score = sc
                top_device_id = did

        top_device: dict[str, Any] | None = None
        if top_device_id:
            dev = await self.get_device_by_id(top_device_id)
            if dev is None:
                top_device = {"id": top_device_id, "name": None, "events": top_score}
            else:
                top_device = {
                    "id": dev["id"],
                    "name": dev["name"],
                    "events": top_score,
                }

        return {
            "totals": {
                "transfers_completed": transfers_completed,
                "bytes_transferred": bytes_total,
                "clipboard_events": clipboard_total,
                "notifications": notifications_total,
            },
            "activity_last_7_days": activity,
            "top_device": top_device,
        }
