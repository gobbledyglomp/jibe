"""Tests for the async SQLite database layer.

Covers the full CRUD lifecycle for devices and sessions, schema
versioning, idempotency guarantees, and edge cases like duplicate
fingerprints and cascading deletes.

Each test gets a fresh in-memory database via the `db` fixture, so
tests are completely isolated from each other and from the real
database on disk.
"""

import aiosqlite
import pytest
from jibe.core.db import JibeDatabase

async def test_schema_created_on_first_open(db):
    """All tables should exist after opening the database."""
    cursor = await db._conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name;"
    )
    tables = [row[0] for row in await cursor.fetchall()]

    assert "meta" in tables
    assert "devices" in tables
    assert "sessions" in tables


async def test_schema_version_stored(db):
    """The meta table should contain the current schema version."""
    from jibe.core.config import SCHEMA_VERSION

    cursor = await db._conn.execute(
        "SELECT value FROM meta WHERE key = 'schema_version';"
    )
    row = await cursor.fetchone()

    assert row is not None
    assert int(row[0]) == SCHEMA_VERSION


async def test_schema_creation_is_idempotent(tmp_path):
    """Opening the same database twice should not fail or duplicate data."""
    db_path = tmp_path / "idempotent_test.db"

    db1 = JibeDatabase(db_path=db_path)
    await db1.open()
    await db1.close()

    db2 = JibeDatabase(db_path=db_path)
    await db2.open()

    cursor = await db2._conn.execute(
        "SELECT COUNT(*) FROM meta WHERE key = 'schema_version';"
    )
    row = await cursor.fetchone()

    assert row[0] == 1

    await db2.close()


async def test_database_creates_parent_directory(tmp_path):
    """The database should create its parent directory if it doesn't exist."""
    db_path = tmp_path / "nested" / "dirs" / "jibe.db"
    db = JibeDatabase(db_path=db_path)
    await db.open()

    assert db_path.exists()
    assert db_path.parent.is_dir()

    await db.close()


async def test_database_context_manager(tmp_path):
    """The async context manager should open and close the database."""
    db_path = tmp_path / "ctx_test.db"

    async with JibeDatabase(db_path=db_path) as db:
        devices = await db.list_devices()
        assert devices == []

    assert db._conn is None


async def test_add_device(db):
    """Adding a device should return its full record."""
    result = await db.add_device(
        device_id="dev-001",
        name="Pixel 8 Pro",
        fingerprint="abc123fingerprint",
    )

    assert result["id"] == "dev-001"
    assert result["name"] == "Pixel 8 Pro"
    assert result["fingerprint"] == "abc123fingerprint"
    assert result["paired_at"] is not None
    assert result["last_seen"] is not None


async def test_get_device_by_fingerprint_found(db):
    """Looking up an existing device by fingerprint should return it."""
    await db.add_device("dev-001", "Pixel 8 Pro", "fp-unique-123")

    result = await db.get_device_by_fingerprint("fp-unique-123")

    assert result is not None
    assert result["id"] == "dev-001"
    assert result["name"] == "Pixel 8 Pro"


async def test_get_device_by_fingerprint_not_found(db):
    """Looking up a non-existent fingerprint should return None."""
    result = await db.get_device_by_fingerprint("does-not-exist")
    assert result is None


async def test_get_device_by_id_found(db):
    """Looking up an existing device by ID should return it."""
    await db.add_device("dev-001", "Pixel 8 Pro", "fp-123")

    result = await db.get_device_by_id("dev-001")

    assert result is not None
    assert result["name"] == "Pixel 8 Pro"


async def test_get_device_by_id_not_found(db):
    """Looking up a non-existent ID should return None."""
    result = await db.get_device_by_id("does-not-exist")
    assert result is None


async def test_add_device_duplicate_fingerprint_fails(db):
    """Two devices with the same fingerprint should be rejected.

    The fingerprint uniquely identifies a device — if two devices
    claim the same fingerprint, something is wrong (replay attack
    or data corruption).
    """
    await db.add_device("dev-001", "Pixel 8", "same-fingerprint")

    with pytest.raises(aiosqlite.IntegrityError):
        await db.add_device("dev-002", "Galaxy S24", "same-fingerprint")


async def test_list_devices_empty(db):
    """Listing devices on a fresh database should return an empty list."""
    devices = await db.list_devices()
    assert devices == []


async def test_list_devices_returns_all(db):
    """All paired devices should appear in the list."""
    await db.add_device("dev-001", "Pixel 8", "fp-001")
    await db.add_device("dev-002", "Galaxy S24", "fp-002")
    await db.add_device("dev-003", "OnePlus 12", "fp-003")

    devices = await db.list_devices()

    assert len(devices) == 3
    names = {d["name"] for d in devices}
    assert names == {"Pixel 8", "Galaxy S24", "OnePlus 12"}


async def test_list_devices_ordered_by_last_seen(db):
    """Devices should be ordered by most recently seen first."""
    await db.add_device("dev-001", "Oldest", "fp-001")
    await db.add_device("dev-002", "Middle", "fp-002")
    await db.add_device("dev-003", "Newest", "fp-003")

    await db.update_last_seen("dev-001")

    devices = await db.list_devices()
    assert devices[0]["id"] == "dev-001"


async def test_update_last_seen_success(db):
    """Updating last_seen should change the timestamp."""
    result = await db.add_device("dev-001", "Pixel 8", "fp-001")
    original_last_seen = result["last_seen"]

    updated = await db.update_last_seen("dev-001")
    assert updated is True

    device = await db.get_device_by_id("dev-001")
    assert device["last_seen"] >= original_last_seen


async def test_update_last_seen_nonexistent_device(db):
    """Updating last_seen for a non-existent device should return False."""
    result = await db.update_last_seen("does-not-exist")
    assert result is False


async def test_remove_device_success(db):
    """Removing an existing device should delete it from the database."""
    await db.add_device("dev-001", "Pixel 8", "fp-001")

    removed = await db.remove_device("dev-001")
    assert removed is True

    device = await db.get_device_by_id("dev-001")
    assert device is None


async def test_remove_device_nonexistent(db):
    """Removing a non-existent device should return False, not crash.

    Idempotency: calling remove on something that doesn't exist is
    a no-op, not an error. This simplifies cleanup code.
    """
    removed = await db.remove_device("does-not-exist")
    assert removed is False


async def test_remove_device_twice(db):
    """Removing the same device twice should succeed then return False."""
    await db.add_device("dev-001", "Pixel 8", "fp-001")

    first = await db.remove_device("dev-001")
    second = await db.remove_device("dev-001")

    assert first is True
    assert second is False


async def test_start_session(db):
    """Starting a session should create a record with no end time."""
    await db.add_device("dev-001", "Pixel 8", "fp-001")

    session = await db.start_session("sess-001", "dev-001")

    assert session["id"] == "sess-001"
    assert session["device_id"] == "dev-001"
    assert session["started_at"] is not None
    assert session["ended_at"] is None


async def test_end_session(db):
    """Ending a session should set the ended_at timestamp."""
    await db.add_device("dev-001", "Pixel 8", "fp-001")
    await db.start_session("sess-001", "dev-001")

    ended = await db.end_session("sess-001")
    assert ended is True


async def test_end_session_nonexistent(db):
    """Ending a non-existent session should return False."""
    ended = await db.end_session("does-not-exist")
    assert ended is False


async def test_cascade_delete_removes_sessions(db):
    """Deleting a device should cascade-delete all its sessions.

    This is enforced by the FOREIGN KEY ON DELETE CASCADE constraint.
    Without it, we'd have orphaned session rows pointing to a device
    that no longer exists.
    """
    await db.add_device("dev-001", "Pixel 8", "fp-001")
    await db.start_session("sess-001", "dev-001")
    await db.start_session("sess-002", "dev-001")

    await db.remove_device("dev-001")

    cursor = await db._conn.execute(
        "SELECT COUNT(*) FROM sessions WHERE device_id = 'dev-001';"
    )
    row = await cursor.fetchone()
    assert row[0] == 0


async def test_close_is_idempotent(tmp_path):
    """Calling close() multiple times should not raise."""
    db = JibeDatabase(db_path=tmp_path / "close_test.db")
    await db.open()

    await db.close()
    await db.close()


async def test_close_without_open():
    """Calling close() without ever opening should not raise."""
    db = JibeDatabase(db_path=None)
    await db.close()
