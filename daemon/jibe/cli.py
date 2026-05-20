"""Jibe daemon CLI entry point.

Wires all daemon components and exposes the ``jibe`` console script:

    jibe                  # TLS enabled (default)
    jibe --no-tls         # plaintext for development
    jibe -p 9000          # custom port
    jibe --pair           # start pairing immediately, prints PIN
    jibe --regen-certs    # force regenerate TLS certificate
    jibe -v               # debug logging
    jibe --no-tray        # headless (no system tray / Docker)

All services run concurrently in a single asyncio event loop.
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import os
import shutil
import signal
import sys

from jibe.core.config import CERTS_DIR, DEFAULT_PORT, LOG_DATE_FORMAT, LOG_FORMAT
from jibe.core.ports import assert_ports_available
from jibe.core.db import JibeDatabase
from jibe.core.tls import create_ssl_context, generate_self_signed_cert
from jibe.handlers.battery import get_all_batteries
from jibe.handlers.ring import send_ring
from jibe.network.discovery import JibeDiscovery
from jibe.network.server import JibeServer
from jibe.ui.tray import JibeTray

_QUIET_LOGGERS = ("aiosqlite", "asyncio", "zeroconf", "aiohttp.access")

logger = logging.getLogger("jibe.main")


def _have_desktop() -> bool:
    return bool(os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY"))


async def run_daemon(
    *,
    use_tls: bool = True,
    port: int = DEFAULT_PORT,
    start_pairing: bool = False,
    enable_tray: bool = True,
) -> None:
    """Run the main daemon loop."""
    logger.info("Jibe daemon starting... (PID %d)", os.getpid())
    logger.info("  To pair a new device at any time: kill -USR1 %d", os.getpid())

    db = JibeDatabase()
    await db.open()
    await db.ensure_default_admin_if_empty()

    cert_path = None
    ssl_context = None
    if use_tls:
        cert_path, key_path = generate_self_signed_cert()
        ssl_context = create_ssl_context(cert_path, key_path)
    else:
        logger.warning("TLS disabled — connections are unencrypted")

    server = JibeServer(db=db, port=port, ssl_context=ssl_context, cert_path=cert_path)
    discovery = JibeDiscovery(port=port)

    loop = asyncio.get_running_loop()
    shutdown_event = asyncio.Event()

    def _request_shutdown() -> None:
        shutdown_event.set()

    loop.add_signal_handler(signal.SIGUSR1, lambda: server.auth.start_pairing())
    loop.add_signal_handler(signal.SIGTERM, _request_shutdown)

    tray: JibeTray | None = None
    if enable_tray and _have_desktop():
        def _ring_any_device() -> None:
            """Ring the first authenticated device, scheduling on the asyncio loop."""
            conns = server.registry.get_authenticated()
            if not conns:
                logger.warning("Ring requested from tray but no device connected")
                return
            asyncio.run_coroutine_threadsafe(
                send_ring(conns[0], event_log=server.event_log),
                loop,
            )

        tray = JibeTray(
            dashboard_url=f"http://127.0.0.1:{server.dashboard_port}/",
            loop=loop,
            shutdown_event=shutdown_event,
            auth_manager=server.auth,
            get_battery_fn=get_all_batteries,
            ring_fn=_ring_any_device,
        )

    try:
        await asyncio.gather(
            server.start(),
            discovery.start(),
        )

        if start_pairing:
            server.auth.start_pairing()

        if tray is not None:
            tray.start()

        logger.info(
            "Ready — dashboard at http://127.0.0.1:%d/ · Ctrl+C to stop.",
            server.dashboard_port,
        )
        await shutdown_event.wait()

    except asyncio.CancelledError:
        logger.info("Shutting down...")
        raise

    finally:
        if tray is not None:
            tray.stop()
        await asyncio.gather(
            discovery.stop(),
            server.stop(),
            return_exceptions=True,
        )
        await db.close()


def _build_parser() -> argparse.ArgumentParser:
    """Build the CLI argument parser."""
    parser = argparse.ArgumentParser(description="Jibe daemon")
    parser.add_argument(
        "--no-tls",
        action="store_true",
        help="Disable TLS (plaintext WebSocket for development)",
    )
    parser.add_argument(
        "-p",
        "--port",
        type=int,
        default=DEFAULT_PORT,
        help=f"TCP port to listen on (default: {DEFAULT_PORT})",
    )
    parser.add_argument(
        "--pair",
        action="store_true",
        help="Start pairing mode immediately — prints a 6-digit PIN to pair a new Android device",
    )
    parser.add_argument(
        "--regen-certs",
        action="store_true",
        help="Delete existing TLS certificates and regenerate",
    )
    parser.add_argument(
        "--no-tray",
        action="store_true",
        help="Do not show the system tray icon (for headless / Docker)",
    )
    parser.add_argument(
        "-v",
        "--verbose",
        action="store_true",
        help="Enable debug logging",
    )
    return parser


def _handle_regen_certs() -> None:
    """Delete existing certificates and regenerate."""
    if CERTS_DIR.exists():
        shutil.rmtree(CERTS_DIR)
        logger.info("Deleted existing certificates in %s", CERTS_DIR)
    generate_self_signed_cert()
    logger.info("New certificates generated. Starting daemon...")


def _configure_logging(*, verbose: bool) -> None:
    """Configure logging level, format, and terminal colors."""
    logging.basicConfig(
        level=logging.DEBUG if verbose else logging.INFO,
        format=LOG_FORMAT,
        datefmt=LOG_DATE_FORMAT,
    )

    if sys.stderr.isatty():
        _RESET = "\033[0m"
        _COLORS = {
            logging.DEBUG: "\033[36m",
            logging.INFO: "\033[32m",
            logging.WARNING: "\033[33m",
            logging.ERROR: "\033[31m",
            logging.CRITICAL: "\033[35m",
        }
        for level, color in _COLORS.items():
            name = logging.getLevelName(level)
            logging.addLevelName(level, f"{color}{name}{_RESET}")

    third_party_level = logging.INFO if verbose else logging.WARNING
    for name in _QUIET_LOGGERS:
        logging.getLogger(name).setLevel(third_party_level)


def main() -> None:
    """Entry point. Parses args, sets up the event loop, handles Ctrl+C."""
    args = _build_parser().parse_args()

    # Environment variable overrides for Docker/headless deployments.
    # These are checked after arg parsing so CLI flags still take precedence.
    if "JIBE_PORT" in os.environ:
        try:
            args.port = int(os.environ["JIBE_PORT"])
        except ValueError:
            pass
    if os.environ.get("JIBE_NO_TLS", "").lower() in ("1", "true", "yes"):
        args.no_tls = True
    if os.environ.get("JIBE_NO_TRAY", "").lower() in ("1", "true", "yes"):
        args.no_tray = True

    _configure_logging(verbose=args.verbose)

    if args.regen_certs:
        _handle_regen_certs()

    enable_tray = not args.no_tray

    assert_ports_available(args.port, use_tls=not args.no_tls)

    try:
        asyncio.run(
            run_daemon(
                use_tls=not args.no_tls,
                port=args.port,
                start_pairing=args.pair,
                enable_tray=enable_tray,
            )
        )
    except (KeyboardInterrupt, asyncio.CancelledError):
        logger.info("Jibe daemon stopped cleanly.")
