"""Desktop session environment discovery for tray under systemd."""

import os
import socket
from pathlib import Path

from jibe.core import desktop_env


def test_bootstrap_sets_wayland_from_runtime_socket(tmp_path, monkeypatch):
    monkeypatch.delenv("WAYLAND_DISPLAY", raising=False)
    monkeypatch.delenv("DISPLAY", raising=False)
    monkeypatch.setenv("XDG_RUNTIME_DIR", str(tmp_path))

    sock = tmp_path / "wayland-0"
    sock.touch()
    # touch() is not a socket; create a real unix socket
    sock.unlink()
    server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    server.bind(str(sock))
    server.close()

    desktop_env.bootstrap_desktop_environment()
    assert os.environ.get("WAYLAND_DISPLAY") == "wayland-0"


def test_bootstrap_sets_dbus_from_runtime_bus(tmp_path, monkeypatch):
    monkeypatch.delenv("DBUS_SESSION_BUS_ADDRESS", raising=False)
    monkeypatch.setenv("XDG_RUNTIME_DIR", str(tmp_path))
    (tmp_path / "bus").touch()

    desktop_env.bootstrap_desktop_environment()
    assert os.environ.get("DBUS_SESSION_BUS_ADDRESS") == f"unix:path={tmp_path / 'bus'}"


def test_have_desktop_session_true_with_wayland_only(monkeypatch):
    monkeypatch.setenv("WAYLAND_DISPLAY", "wayland-0")
    monkeypatch.delenv("DISPLAY", raising=False)
    assert desktop_env.have_desktop_session() is True


def test_have_desktop_session_false_without_display(monkeypatch, tmp_path):
    for key in ("WAYLAND_DISPLAY", "DISPLAY", "XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS"):
        monkeypatch.delenv(key, raising=False)
    # Empty runtime dir — no wayland sockets
    monkeypatch.setenv("XDG_RUNTIME_DIR", str(tmp_path))
    assert desktop_env.have_desktop_session() is False
