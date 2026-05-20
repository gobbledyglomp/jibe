"""Presentation remote helpers."""

from jibe.handlers.remote import _ydotool_socket_path


def test_ydotool_socket_path_uses_xdg_runtime_dir(monkeypatch, tmp_path):
    monkeypatch.setenv("XDG_RUNTIME_DIR", str(tmp_path))
    assert _ydotool_socket_path() == tmp_path / ".ydotool_socket"
