"""File transfer: receive chunked uploads from Android.

Chunk payloads use compact **binary WebSocket frames** (see ``FILE_CHUNK_HEADER_STRUCT``).
Control messages remain JSON: ``file.start``, ``file.done``, ``file.chunk.ack``, ``file.ack``.
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import os
import shutil
import struct
import uuid as uuid_stdlib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, BinaryIO

from jibe.core.api import JibeMessage, MessageType, format_error
from jibe.core.config import FILE_TRANSFER_CHUNK_RAW_BYTES
from jibe.core.db import JibeDatabase
from jibe.network.connection import JibeConnection

logger = logging.getLogger(__name__)

# Must match Android ``FileTransferRepository.CHUNK_SIZE_BYTES``.
EXPECTED_CHUNK_RAW_BYTES = FILE_TRANSFER_CHUNK_RAW_BYTES

# Binary chunk wire format (big-endian). Magic ASCII ``JBFC`` — Jibe Binary File Chunk.
FILE_CHUNK_BINARY_MAGIC = b"JBFC"
FILE_CHUNK_BINARY_VERSION = 1
FILE_CHUNK_HEADER_STRUCT = struct.Struct("!4sBBHII16s")
FILE_CHUNK_HEADER_SIZE = FILE_CHUNK_HEADER_STRUCT.size


def pack_binary_file_chunk(transfer_id: str, chunk_index: int, payload: bytes) -> bytes:
    """Build one binary WebSocket frame body for a file chunk (tests + documentation)."""
    uid = uuid_stdlib.UUID(transfer_id)
    header = FILE_CHUNK_HEADER_STRUCT.pack(
        FILE_CHUNK_BINARY_MAGIC,
        FILE_CHUNK_BINARY_VERSION,
        0,
        0,
        chunk_index,
        len(payload),
        uid.bytes,
    )
    return header + payload


def _downloads_root() -> Path:
    """Resolved downloads directory (``~/Downloads``)."""
    return Path.home() / "Downloads"


def _transfer_temp_root() -> Path:
    """Root for in-progress transfer assembly under the user's cache directory."""
    xdg_cache_home = Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache"))
    return xdg_cache_home / "jibe" / "transfers"


def _temp_transfer_dir(transfer_id: str) -> Path:
    """Directory for in-progress chunk assembly."""
    return _transfer_temp_root() / transfer_id


def _remove_empty_transfer_root() -> None:
    """Remove the scratch transfer root if no active workspaces remain."""
    root = _transfer_temp_root()
    try:
        root.rmdir()
    except FileNotFoundError:
        return
    except OSError:
        # Directory is not empty or cannot be removed; either is harmless.
        return


def _safe_filename(name: str) -> str:
    """Reduce ``filename`` to a single path segment under Downloads."""
    base = Path(name).name
    return base if base else "unnamed"


def _pick_destination_filename(filename: str) -> Path:
    """Return a non-colliding path under ``~/Downloads``."""
    dest_dir = _downloads_root()
    dest_dir.mkdir(parents=True, exist_ok=True)
    safe = _safe_filename(filename)
    target = dest_dir / safe
    if not target.exists():
        return target
    stem = target.stem
    suffix = target.suffix
    for i in range(1, 10_000):
        candidate = dest_dir / f"{stem}_{i}{suffix}"
        if not candidate.exists():
            return candidate
    raise RuntimeError("Unable to allocate a unique filename under Downloads")


def _format_file_ack(transfer_id: str, ok: bool, reason: str | None = None) -> str:
    payload: dict[str, str | bool] = {
        "type": MessageType.FILE_ACK.value,
        "id": transfer_id,
        "ok": ok,
    }
    if reason is not None:
        payload["reason"] = reason
    return json.dumps(payload)


async def _send_ack(conn: JibeConnection, transfer_id: str, ok: bool, reason: str | None = None) -> None:
    await conn.send(_format_file_ack(transfer_id, ok, reason))


def _format_file_chunk_ack(
    transfer_id: str,
    index: int,
    ok: bool,
    *,
    bytes_received: int = 0,
    reason: str | None = None,
) -> str:
    payload: dict[str, str | int | bool] = {
        "type": MessageType.FILE_CHUNK_ACK.value,
        "id": transfer_id,
        "index": index,
        "ok": ok,
        "bytes_received": bytes_received,
    }
    if reason is not None:
        payload["reason"] = reason
    return json.dumps(payload)


async def _send_chunk_ack(
    conn: JibeConnection,
    transfer_id: str,
    index: int,
    ok: bool,
    *,
    bytes_received: int = 0,
    reason: str | None = None,
) -> None:
    await conn.send(
        _format_file_chunk_ack(
            transfer_id,
            index,
            ok,
            bytes_received=bytes_received,
            reason=reason,
        )
    )


async def abort_transfers_for_connection(
    connection_id: str,
    db: JibeDatabase | None = None,
) -> None:
    """Abort any in-progress uploads owned by a disconnected WebSocket.

    Removes partial data from the transfer cache so disconnects do not leave garbage.

    Args:
        connection_id: ``JibeConnection.id`` for the socket that closed.
        db: Optional database — terminal rows are marked ``cancelled``.
    """
    to_remove = [
        tid
        for tid, t in default_transfer_store.active.items()
        if t.owner_connection_id == connection_id
    ]
    for tid in to_remove:
        logger.info(
            "Aborting transfer %s after disconnect of connection %s",
            tid,
            connection_id,
        )
        await _try_finish_transfer_history(db, tid, "cancelled")
        _abort_transfer(tid)


async def _try_finish_transfer_history(
    db: JibeDatabase | None,
    transfer_id: str,
    status: str,
) -> None:
    """Best-effort ``transfer_history`` terminal status."""
    if db is None:
        return
    try:
        await db.finish_transfer(transfer_id, status)
    except Exception:
        logger.exception("transfer_history finish failed for %s", transfer_id)


def _abort_transfer(transfer_id: str, *, rm_temp: bool = True) -> None:
    transfer = default_transfer_store.active.pop(transfer_id, None)
    if transfer is None:
        return
    try:
        transfer.file_handle.close()
    except Exception:
        logger.exception("Failed closing transfer handle for %s", transfer_id)
    if rm_temp:
        try:
            shutil.rmtree(transfer.temp_dir, ignore_errors=True)
            _remove_empty_transfer_root()
        except Exception:
            logger.exception("Failed removing temp dir for %s", transfer_id)


@dataclass
class ActiveTransfer:
    """In-progress assembly state for one upload."""

    transfer_id: str
    owner_connection_id: str
    filename: str
    size: int
    total_chunks: int
    chunks_received: int
    bytes_written: int
    temp_dir: Path
    temp_file: Path
    file_handle: BinaryIO
    hasher: Any = field(init=False)

    def __post_init__(self) -> None:
        self.hasher = hashlib.sha256()


class TransferStore:
    """Holds active inbound uploads; tests clear ``default_transfer_store.active`` for isolation."""

    __slots__ = ("active",)

    def __init__(self) -> None:
        self.active: dict[str, ActiveTransfer] = {}


default_transfer_store = TransferStore()


async def handle_file_start(
    conn: JibeConnection,
    msg: JibeMessage,
    db: JibeDatabase | None = None,
) -> None:
    """Begin a new transfer: create temp dir and empty file."""
    payload = msg.payload
    transfer_id = payload.get("id")
    filename = payload.get("filename")
    size = payload.get("size")
    total_chunks = payload.get("total_chunks")

    if not isinstance(transfer_id, str) or not transfer_id:
        await conn.send(format_error("malformed_payload", "file.start requires a non-empty string id"))
        return
    if transfer_id in default_transfer_store.active:
        await conn.send(format_error("transfer_conflict", f"Transfer id '{transfer_id}' is already active"))
        return
    if not isinstance(filename, str) or not filename.strip():
        await conn.send(format_error("malformed_payload", "file.start requires filename"))
        return
    if not isinstance(size, int) or size < 0:
        await conn.send(format_error("malformed_payload", "file.start requires non-negative integer size"))
        return
    if not isinstance(total_chunks, int) or total_chunks < 0:
        await conn.send(format_error("malformed_payload", "file.start requires non-negative total_chunks"))
        return

    temp_dir = _temp_transfer_dir(transfer_id)
    try:
        if temp_dir.exists():
            shutil.rmtree(temp_dir)
        temp_dir.mkdir(parents=True, exist_ok=True)
        temp_file = temp_dir / "payload.bin"
        handle = temp_file.open("wb")
    except Exception:
        logger.exception("Could not create temp transfer workspace for %s", transfer_id)
        await conn.send(format_error("internal_error", "Could not prepare transfer workspace"))
        return

    default_transfer_store.active[transfer_id] = ActiveTransfer(
        transfer_id=transfer_id,
        owner_connection_id=conn.id,
        filename=filename,
        size=size,
        total_chunks=total_chunks,
        chunks_received=0,
        bytes_written=0,
        temp_dir=temp_dir,
        temp_file=temp_file,
        file_handle=handle,
    )
    logger.info("file.start id=%s name=%s size=%s chunks=%s", transfer_id, filename, size, total_chunks)

    if db is not None and conn.device_id:
        try:
            await db.add_transfer(
                transfer_id,
                conn.device_id,
                filename,
                size,
                direction="incoming",
                status="in_progress",
            )
        except Exception:
            logger.exception("transfer_history insert failed for %s", transfer_id)


async def _abort_transfer_after_bad_chunk(
    conn: JibeConnection,
    transfer_id: str,
    index: int,
    *,
    chunk_reason: str,
    done_reason: str,
    db: JibeDatabase | None = None,
) -> None:
    """Tear down transfer after a bad chunk and notify the client."""
    await _try_finish_transfer_history(db, transfer_id, "failed")
    _abort_transfer(transfer_id)
    await _send_chunk_ack(conn, transfer_id, index, False, reason=chunk_reason)
    await _send_ack(conn, transfer_id, False, done_reason)


async def handle_file_chunk_binary(
    conn: JibeConnection,
    frame: bytes,
    db: JibeDatabase | None = None,
) -> None:
    """Decode one binary chunk frame and append raw payload to the temp file."""
    if len(frame) < FILE_CHUNK_HEADER_SIZE:
        await conn.send(format_error("malformed_payload", "Binary chunk frame too short"))
        return

    try:
        magic, ver, _flags, _rsv, index, payload_len, uuid_bytes = FILE_CHUNK_HEADER_STRUCT.unpack_from(
            frame
        )
    except struct.error:
        await conn.send(format_error("malformed_payload", "Invalid binary chunk header"))
        return

    if magic != FILE_CHUNK_BINARY_MAGIC:
        await conn.send(format_error("malformed_payload", "Unknown binary chunk magic"))
        return
    if ver != FILE_CHUNK_BINARY_VERSION:
        await conn.send(format_error("malformed_payload", "Unsupported binary chunk version"))
        return
    if len(frame) != FILE_CHUNK_HEADER_SIZE + payload_len:
        await conn.send(format_error("malformed_payload", "Binary chunk length mismatch"))
        return

    try:
        transfer_id = str(uuid_stdlib.UUID(bytes=uuid_bytes))
    except ValueError:
        await conn.send(format_error("malformed_payload", "Invalid transfer id in binary chunk"))
        return

    if transfer_id not in default_transfer_store.active:
        await conn.send(format_error("unknown_transfer", "No active transfer for this id"))
        return

    transfer = default_transfer_store.active[transfer_id]

    if transfer.owner_connection_id != conn.id:
        logger.warning(
            "binary chunk id=%s sent from wrong connection (owner=%s, got=%s)",
            transfer_id,
            transfer.owner_connection_id,
            conn.id,
        )
        await conn.send(format_error("forbidden", "Transfer belongs to another connection"))
        return

    if index != transfer.chunks_received:
        logger.warning(
            "Chunk index mismatch for %s: expected %s got %s",
            transfer_id,
            transfer.chunks_received,
            index,
        )
        await _abort_transfer_after_bad_chunk(
            conn,
            transfer_id,
            index,
            chunk_reason="Chunk sequence mismatch",
            done_reason="Chunk sequence mismatch",
            db=db,
        )
        return

    if payload_len > EXPECTED_CHUNK_RAW_BYTES:
        await _abort_transfer_after_bad_chunk(
            conn,
            transfer_id,
            index,
            chunk_reason="Chunk exceeds maximum size",
            done_reason="Chunk exceeds maximum size",
            db=db,
        )
        return

    remaining = transfer.size - transfer.bytes_written
    if payload_len > remaining:
        await _abort_transfer_after_bad_chunk(
            conn,
            transfer_id,
            index,
            chunk_reason="Chunk overflows declared file size",
            done_reason="Chunk overflows declared file size",
            db=db,
        )
        return

    payload_view = memoryview(frame)[FILE_CHUNK_HEADER_SIZE:]
    transfer.hasher.update(payload_view)
    raw_for_disk = payload_view.tobytes()
    await asyncio.to_thread(transfer.file_handle.write, raw_for_disk)

    transfer.bytes_written += payload_len
    transfer.chunks_received += 1

    await _send_chunk_ack(
        conn,
        transfer_id,
        index,
        True,
        bytes_received=transfer.bytes_written,
    )


async def handle_file_cancel(
    conn: JibeConnection,
    msg: JibeMessage,
    db: JibeDatabase | None = None,
) -> None:
    """Abort an active transfer without closing the connection."""
    payload = msg.payload
    transfer_id = payload.get("id")

    if not isinstance(transfer_id, str) or not transfer_id:
        await conn.send(format_error("malformed_payload", "file.cancel requires a non-empty string id"))
        return
    if transfer_id not in default_transfer_store.active:
        await conn.send(format_error("unknown_transfer", "No active transfer for this id"))
        return

    transfer = default_transfer_store.active[transfer_id]
    if transfer.owner_connection_id != conn.id:
        logger.warning(
            "file.cancel id=%s sent from wrong connection (owner=%s, got=%s)",
            transfer_id,
            transfer.owner_connection_id,
            conn.id,
        )
        await conn.send(format_error("forbidden", "Transfer belongs to another connection"))
        return

    await _try_finish_transfer_history(db, transfer_id, "cancelled")
    _abort_transfer(transfer_id)
    await _send_ack(conn, transfer_id, False, "Cancelled")


async def handle_file_done(
    conn: JibeConnection,
    msg: JibeMessage,
    db: JibeDatabase | None = None,
    event_log: Any | None = None,
) -> None:
    """Verify integrity, move file into Downloads, and acknowledge."""
    payload = msg.payload
    transfer_id = payload.get("id")
    checksum = payload.get("checksum")

    if not isinstance(transfer_id, str) or transfer_id not in default_transfer_store.active:
        await conn.send(format_error("unknown_transfer", "No active transfer for this id"))
        return
    if not isinstance(checksum, str) or not checksum:
        await _try_finish_transfer_history(db, transfer_id, "failed")
        _abort_transfer(transfer_id)
        await _send_ack(conn, transfer_id, False, "Missing checksum")
        return

    transfer = default_transfer_store.active[transfer_id]

    if transfer.owner_connection_id != conn.id:
        logger.warning(
            "file.done id=%s sent from wrong connection (owner=%s, got=%s)",
            transfer_id,
            transfer.owner_connection_id,
            conn.id,
        )
        await conn.send(format_error("forbidden", "Transfer belongs to another connection"))
        return

    if transfer.chunks_received != transfer.total_chunks:
        await _try_finish_transfer_history(db, transfer_id, "failed")
        _abort_transfer(transfer_id)
        await _send_ack(
            conn,
            transfer_id,
            False,
            f"Incomplete transfer: got {transfer.chunks_received} of {transfer.total_chunks} chunks",
        )
        return
    if transfer.bytes_written != transfer.size:
        await _try_finish_transfer_history(db, transfer_id, "failed")
        _abort_transfer(transfer_id)
        await _send_ack(conn, transfer_id, False, "Size mismatch after reassembly")
        return

    digest = transfer.hasher.hexdigest()
    if digest.lower() != checksum.lower():
        logger.warning("Checksum mismatch for %s: expected %s got %s", transfer_id, checksum, digest)
        await _try_finish_transfer_history(db, transfer_id, "failed")
        _abort_transfer(transfer_id)
        await _send_ack(conn, transfer_id, False, "Checksum mismatch")
        return

    try:
        await asyncio.to_thread(transfer.file_handle.flush)
        await asyncio.to_thread(transfer.file_handle.close)
    except Exception:
        logger.exception("Failed finishing temp file for %s", transfer_id)
        await _try_finish_transfer_history(db, transfer_id, "failed")
        _abort_transfer(transfer_id, rm_temp=True)
        await _send_ack(conn, transfer_id, False, "Failed to finalise temp file")
        return

    transfer = default_transfer_store.active.pop(transfer_id, None)
    if transfer is None:
        await _send_ack(conn, transfer_id, False, "Transfer disappeared during finalisation")
        return

    try:
        dest = _pick_destination_filename(transfer.filename)
        await asyncio.to_thread(shutil.move, str(transfer.temp_file), str(dest))
        logger.info("Saved transfer %s to %s", transfer_id, dest)
    except Exception:
        logger.exception("Failed moving completed file for %s", transfer_id)
        await _try_finish_transfer_history(db, transfer_id, "failed")
        try:
            shutil.rmtree(transfer.temp_dir, ignore_errors=True)
            _remove_empty_transfer_root()
        except Exception:
            logger.exception("Cleanup after failed move for %s", transfer_id)
        await _send_ack(conn, transfer_id, False, "Failed to save file to Downloads")
        return

    try:
        shutil.rmtree(transfer.temp_dir, ignore_errors=True)
        _remove_empty_transfer_root()
    except Exception:
        logger.exception("Failed removing temp dir for completed transfer %s", transfer_id)

    await _try_finish_transfer_history(db, transfer_id, "completed")
    if event_log is not None:
        event_log.record(
            "transfer",
            f"File received: {transfer.filename}",
            filename=transfer.filename,
            size_bytes=transfer.size,
            device_name=conn.device_name,
            device_id=conn.device_id,
        )
    await _send_ack(conn, transfer_id, True)
