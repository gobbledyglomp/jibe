"""Port availability checks and user-facing bind error messages."""

from __future__ import annotations

import errno
import logging
import socket
import subprocess

logger = logging.getLogger(__name__)


def assert_ports_available(ws_port: int, *, use_tls: bool) -> None:
    """Fail fast with actionable guidance when the daemon ports are taken."""
    _assert_port_free(ws_port, host="0.0.0.0")
    if use_tls:
        _assert_port_free(ws_port + 1, host="127.0.0.1")


def _assert_port_free(port: int, *, host: str) -> None:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        try:
            sock.bind((host, port))
        except OSError as exc:
            if exc.errno not in (errno.EADDRINUSE, errno.EACCES):
                raise
            _log_port_in_use(port)
            raise SystemExit(1) from exc


def _log_port_in_use(port: int) -> None:
    logger.error("Port %d is already in use — another Jibe instance is probably running.", port)
    if _systemd_jibe_active():
        logger.error(
            "The systemd user service 'jibe' is active. Use it instead of starting a second copy:"
        )
        logger.error("  systemctl --user status jibe")
        logger.error("  journalctl --user -u jibe -f")
        logger.error("  systemctl --user stop jibe   # only if you need to run 'jibe' manually")
    else:
        logger.error("Check what holds the port: ss -tlnp | grep ':%d'", port)
        logger.error("Stop the other process, or pick another port: jibe -p <port>")


def _systemd_jibe_active() -> bool:
    try:
        result = subprocess.run(
            ["systemctl", "--user", "is-active", "jibe"],
            capture_output=True,
            text=True,
            timeout=2,
            check=False,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired):
        return False
    return result.returncode == 0 and result.stdout.strip() == "active"
