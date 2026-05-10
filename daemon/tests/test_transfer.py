"""Tests for chunked file transfer handlers."""

import hashlib
import json

import pytest

from jibe.core.api import JibeMessage, MessageType
from jibe.handlers import transfer as transfer_mod
from jibe.handlers.transfer import (
    abort_transfers_for_connection,
    handle_file_cancel,
    handle_file_chunk_binary,
    handle_file_done,
    handle_file_start,
    pack_binary_file_chunk,
)
from jibe.network.connection import JibeConnection


@pytest.fixture(autouse=True)
def _clear_transfers():
    transfer_mod._transfers.clear()
    yield
    transfer_mod._transfers.clear()


def _msg(msg_type: MessageType, **payload) -> JibeMessage:
    data = {"type": msg_type.value, **payload}
    return JibeMessage(type=msg_type, payload=data)


@pytest.mark.asyncio
async def test_transfer_happy_path(mock_ws, monkeypatch, tmp_path):
    """Assemble chunks, verify checksum, move to Downloads."""
    monkeypatch.setenv("HOME", str(tmp_path))

    conn = JibeConnection(mock_ws, "127.0.0.1")
    tid = "550e8400-e29b-41d4-a716-446655440000"
    raw = b"hello"
    digest = hashlib.sha256(raw).hexdigest()

    await handle_file_start(
        conn,
        _msg(
            MessageType.FILE_START,
            id=tid,
            filename="note.txt",
            size=len(raw),
            total_chunks=1,
        ),
    )

    await handle_file_chunk_binary(conn, pack_binary_file_chunk(tid, 0, raw))

    await handle_file_done(
        conn,
        _msg(MessageType.FILE_DONE, id=tid, checksum=digest),
    )

    downloads = tmp_path / "Downloads"
    saved = downloads / "note.txt"
    assert saved.read_bytes() == raw
    assert not (tmp_path / ".cache" / "jibe" / "transfers").exists()

    sends = [c[0][0] for c in conn.ws.send_str.await_args_list]
    chunk_acks = [json.loads(s) for s in sends if '"type": "file.chunk.ack"' in s]
    assert chunk_acks
    assert chunk_acks[-1]["ok"] is True
    assert chunk_acks[-1]["index"] == 0
    assert chunk_acks[-1]["bytes_received"] == len(raw)

    acks = [s for s in sends if '"type": "file.ack"' in s or '"file.ack"' in s]
    assert acks, "expected a file.ack frame"
    assert '"ok": true' in acks[-1]


@pytest.mark.asyncio
async def test_transfer_checksum_mismatch(mock_ws, monkeypatch, tmp_path):
    """Bad checksum should nack and not leave a completed file in Downloads."""
    monkeypatch.setenv("HOME", str(tmp_path))

    conn = JibeConnection(mock_ws, "127.0.0.1")
    tid = "550e8400-e29b-41d4-a716-446655440001"
    raw = b"hello"

    await handle_file_start(
        conn,
        _msg(
            MessageType.FILE_START,
            id=tid,
            filename="bad.txt",
            size=len(raw),
            total_chunks=1,
        ),
    )

    await handle_file_chunk_binary(conn, pack_binary_file_chunk(tid, 0, raw))

    await handle_file_done(
        conn,
        _msg(MessageType.FILE_DONE, id=tid, checksum="0" * 64),
    )

    downloads = tmp_path / "Downloads"
    assert not list(downloads.glob("bad*.txt"))

    sends = [c[0][0] for c in conn.ws.send_str.await_args_list]
    assert any('"ok": false' in s for s in sends)


@pytest.mark.asyncio
async def test_transfer_duplicate_start_rejected(mock_ws, monkeypatch, tmp_path):
    """Second file.start with same id should error without clobbering state."""
    monkeypatch.setenv("HOME", str(tmp_path))

    conn = JibeConnection(mock_ws, "127.0.0.1")
    tid = "550e8400-e29b-41d4-a716-446655440002"

    await handle_file_start(
        conn,
        _msg(
            MessageType.FILE_START,
            id=tid,
            filename="a.bin",
            size=1,
            total_chunks=1,
        ),
    )

    await handle_file_start(
        conn,
        _msg(
            MessageType.FILE_START,
            id=tid,
            filename="b.bin",
            size=1,
            total_chunks=1,
        ),
    )

    sends = [c[0][0] for c in conn.ws.send_str.await_args_list]
    assert any("transfer_conflict" in s for s in sends)


@pytest.mark.asyncio
async def test_abort_transfers_for_connection_removes_workspace(mock_ws, monkeypatch, tmp_path):
    """Disconnect cleanup should delete partial temp dirs owned by that socket."""
    monkeypatch.setenv("HOME", str(tmp_path))

    conn = JibeConnection(mock_ws, "127.0.0.1")
    tid = "550e8400-e29b-41d4-a716-446655440099"

    await handle_file_start(
        conn,
        _msg(
            MessageType.FILE_START,
            id=tid,
            filename="partial.bin",
            size=100,
            total_chunks=1,
        ),
    )

    workspace = tmp_path / ".cache" / "jibe" / "transfers" / tid
    temp_root = workspace.parent
    assert workspace.is_dir()

    abort_transfers_for_connection(conn.id)

    assert tid not in transfer_mod._transfers
    assert not workspace.exists()
    assert not temp_root.exists()
    assert not (tmp_path / "Downloads" / ".jibe-tmp").exists()


@pytest.mark.asyncio
async def test_file_cancel_sends_ack_without_closing(mock_ws, monkeypatch, tmp_path):
    """Client abort via file.cancel should ack with Cancelled and keep the socket open."""
    monkeypatch.setenv("HOME", str(tmp_path))

    conn = JibeConnection(mock_ws, "127.0.0.1")
    tid = "550e8400-e29b-41d4-a716-4466554400aa"

    await handle_file_start(
        conn,
        _msg(
            MessageType.FILE_START,
            id=tid,
            filename="cancel-me.bin",
            size=100,
            total_chunks=1,
        ),
    )

    await handle_file_cancel(conn, _msg(MessageType.FILE_CANCEL, id=tid))

    sends = [c[0][0] for c in conn.ws.send_str.await_args_list]
    acks = [json.loads(s) for s in sends if '"type": "file.ack"' in s or '"file.ack"' in s]
    assert acks, "expected a file.ack frame"
    assert acks[-1]["ok"] is False
    assert acks[-1]["reason"] == "Cancelled"
    assert tid not in transfer_mod._transfers

    mock_ws.close.assert_not_called()
