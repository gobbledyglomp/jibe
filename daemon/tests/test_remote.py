"""Tests for presentation ``remote.key`` handler."""

import asyncio

import pytest

from jibe.core.api import JibeMessage, MessageType
from jibe.handlers import remote as remote_mod
from jibe.handlers.remote import _ydotool_socket_path, handle_remote_key
from jibe.network.connection import ConnectionState


def _msg(key: str) -> JibeMessage:
    data = {"type": MessageType.REMOTE_KEY.value, "key": key}
    return JibeMessage(type=MessageType.REMOTE_KEY, payload=data)


@pytest.fixture(autouse=True)
def _reset_remote_tool_cache():
    remote_mod._detection_done = False
    remote_mod._detected_tool = None
    yield
    remote_mod._detection_done = False
    remote_mod._detected_tool = None


def test_ydotool_socket_path_uses_xdg_runtime_dir(monkeypatch, tmp_path):
    monkeypatch.setenv("XDG_RUNTIME_DIR", str(tmp_path))
    assert _ydotool_socket_path() == tmp_path / ".ydotool_socket"


@pytest.mark.asyncio
async def test_remote_unknown_key_errors(conn):
    conn.state = ConnectionState.AUTHENTICATED
    await handle_remote_key(conn, _msg("zoom"))
    sends = [c[0][0] for c in conn.ws.send_str.await_args_list]
    assert any("invalid_key" in s for s in sends)


@pytest.mark.asyncio
async def test_remote_dispatches_xdotool(monkeypatch, conn):
    conn.state = ConnectionState.AUTHENTICATED

    monkeypatch.delenv("WAYLAND_DISPLAY", raising=False)

    def fake_which(name: str) -> str | None:
        if name == "xdotool":
            return "/usr/bin/xdotool"
        return None

    monkeypatch.setattr(remote_mod.shutil, "which", fake_which)

    proc_calls: list[list[str]] = []

    class _Proc:
        returncode = 0

        async def wait(self) -> None:
            return None

    async def fake_exec(*args: object, **_kw: object) -> _Proc:
        proc_calls.append(list(map(str, args)))
        return _Proc()

    monkeypatch.setattr(asyncio, "create_subprocess_exec", fake_exec)

    await handle_remote_key(conn, _msg("next"))

    assert proc_calls
    assert proc_calls[0][:3] == ["xdotool", "key", "Right"]
