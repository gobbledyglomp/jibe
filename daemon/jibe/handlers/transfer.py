"""File transfer: receive chunked uploads from Android over ``file.start`` / ``file.chunk`` / ``file.done``.

Assembles Base64 chunks into a temporary file, verifies SHA-256 on completion,
then moves the result into ``~/Downloads``.
"""

from __future__ import annotations

import asyncio
import base64
import binascii
import hashlib
import json
import logging
import shutil
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, BinaryIO

from jibe.core.api import JibeMessage, MessageType, format_error
from jibe.network.connection import JibeConnection

logger = logging.getLogger(__name__)

# Must match the Android sender chunk size (`FileTransferRepository`).
EXPECTED_CHUNK_RAW_BYTES = 65_536

_transfers: dict[str, "ActiveTransfer"] = {}


def _downloads_root() -> Path:
    """Resolved downloads directory (``~/Downloads``)."""
    return Path.home() / "Downloads"


def _temp_transfer_dir(transfer_id: str) -> Path:
    """Directory for in-progress chunk assembly."""
    return _downloads_root() / ".jibe-tmp" / transfer_id


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


def _abort_transfer(transfer_id: str, *, rm_temp: bool = True) -> None:
    transfer = _transfers.pop(transfer_id, None)
    if transfer is None:
        return
    try:
        transfer.file_handle.close()
    except Exception:
        logger.exception("Failed closing transfer handle for %s", transfer_id)
    if rm_temp:
        try:
            shutil.rmtree(transfer.temp_dir, ignore_errors=True)
        except Exception:
            logger.exception("Failed removing temp dir for %s", transfer_id)


@dataclass
class ActiveTransfer:
    """In-progress assembly state for one upload."""

    transfer_id: str
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


async def handle_file_start(conn: JibeConnection, msg: JibeMessage) -> None:
    """Begin a new transfer: create temp dir and empty file."""
    payload = msg.payload
    transfer_id = payload.get("id")
    filename = payload.get("filename")
    size = payload.get("size")
    total_chunks = payload.get("total_chunks")

    if not isinstance(transfer_id, str) or not transfer_id:
        await conn.send(format_error("malformed_payload", "file.start requires a non-empty string id"))
        return
    if transfer_id in _transfers:
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

    _transfers[transfer_id] = ActiveTransfer(
        transfer_id=transfer_id,
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


async def handle_file_chunk(conn: JibeConnection, msg: JibeMessage) -> None:
    """Decode one chunk and append to the temp file."""
    payload = msg.payload
    transfer_id = payload.get("id")
    index = payload.get("index")
    data_b64 = payload.get("data")

    if not isinstance(transfer_id, str) or transfer_id not in _transfers:
        await conn.send(format_error("unknown_transfer", "No active transfer for this id"))
        return
    if not isinstance(index, int) or index < 0:
        await conn.send(format_error("malformed_payload", "file.chunk requires non-negative index"))
        _abort_transfer(transfer_id)
        await _send_ack(conn, transfer_id, False, "Aborted: invalid chunk index")
        return
    if not isinstance(data_b64, str):
        await conn.send(format_error("malformed_payload", "file.chunk requires base64 data"))
        _abort_transfer(transfer_id)
        await _send_ack(conn, transfer_id, False, "Aborted: invalid chunk payload")
        return

    transfer = _transfers[transfer_id]
    if index != transfer.chunks_received:
        logger.warning(
            "Chunk index mismatch for %s: expected %s got %s",
            transfer_id,
            transfer.chunks_received,
            index,
        )
        _abort_transfer(transfer_id)
        await _send_ack(conn, transfer_id, False, "Chunk sequence mismatch")
        return

    try:
        raw = base64.b64decode(data_b64, validate=True)
    except binascii.Error:
        await conn.send(format_error("malformed_payload", "Invalid base64 in file.chunk"))
        _abort_transfer(transfer_id)
        await _send_ack(conn, transfer_id, False, "Aborted: invalid base64")
        return

    if len(raw) > EXPECTED_CHUNK_RAW_BYTES:
        _abort_transfer(transfer_id)
        await _send_ack(conn, transfer_id, False, "Chunk exceeds maximum size")
        return

    remaining = transfer.size - transfer.bytes_written
    if len(raw) > remaining:
        _abort_transfer(transfer_id)
        await _send_ack(conn, transfer_id, False, "Chunk overflows declared file size")
        return

    await asyncio.to_thread(transfer.file_handle.write, raw)
    transfer.hasher.update(raw)
    transfer.bytes_written += len(raw)
    transfer.chunks_received += 1


async def handle_file_done(conn: JibeConnection, msg: JibeMessage) -> None:
    """Verify integrity, move file into Downloads, and acknowledge."""
    payload = msg.payload
    transfer_id = payload.get("id")
    checksum = payload.get("checksum")

    if not isinstance(transfer_id, str) or transfer_id not in _transfers:
        await conn.send(format_error("unknown_transfer", "No active transfer for this id"))
        return
    if not isinstance(checksum, str) or not checksum:
        _abort_transfer(transfer_id)
        await _send_ack(conn, transfer_id, False, "Missing checksum")
        return

    transfer = _transfers[transfer_id]

    if transfer.chunks_received != transfer.total_chunks:
        _abort_transfer(transfer_id)
        await _send_ack(
            conn,
            transfer_id,
            False,
            f"Incomplete transfer: got {transfer.chunks_received} of {transfer.total_chunks} chunks",
        )
        return
    if transfer.bytes_written != transfer.size:
        _abort_transfer(transfer_id)
        await _send_ack(conn, transfer_id, False, "Size mismatch after reassembly")
        return

    digest = transfer.hasher.hexdigest()
    if digest.lower() != checksum.lower():
        logger.warning("Checksum mismatch for %s: expected %s got %s", transfer_id, checksum, digest)
        _abort_transfer(transfer_id)
        await _send_ack(conn, transfer_id, False, "Checksum mismatch")
        return

    try:
        await asyncio.to_thread(transfer.file_handle.flush)
        await asyncio.to_thread(transfer.file_handle.close)
    except Exception:
        logger.exception("Failed finishing temp file for %s", transfer_id)
        _abort_transfer(transfer_id, rm_temp=True)
        await _send_ack(conn, transfer_id, False, "Failed to finalise temp file")
        return

    transfer = _transfers.pop(transfer_id, None)
    if transfer is None:
        await _send_ack(conn, transfer_id, False, "Transfer disappeared during finalisation")
        return

    try:
        dest = _pick_destination_filename(transfer.filename)
        await asyncio.to_thread(shutil.move, str(transfer.temp_file), str(dest))
        logger.info("Saved transfer %s to %s", transfer_id, dest)
    except Exception:
        logger.exception("Failed moving completed file for %s", transfer_id)
        try:
            shutil.rmtree(transfer.temp_dir, ignore_errors=True)
        except Exception:
            logger.exception("Cleanup after failed move for %s", transfer_id)
        await _send_ack(conn, transfer_id, False, "Failed to save file to Downloads")
        return

    try:
        shutil.rmtree(transfer.temp_dir, ignore_errors=True)
    except Exception:
        logger.exception("Failed removing temp dir for completed transfer %s", transfer_id)

    await _send_ack(conn, transfer_id, True)
