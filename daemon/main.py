"""Jibe daemon entrypoint.

Run this file to start the Jibe daemon:

    python main.py                 # TLS enabled (default)
    python main.py --no-tls        # plaintext for development
    python main.py --port 9000     # custom port
    python main.py --regen-certs   # force regenerate TLS certificate
    python main.py --verbose       # debug logging

This wires together all daemon components:
  - JibeDatabase: persistent storage (SQLite)
  - JibeServer: WebSocket connections + HTTP API
  - JibeDiscovery: mDNS broadcast on the local network
  - TLS: self-signed certificate for encrypted connections

All services run concurrently in a single asyncio event loop.
"""

import argparse
import asyncio
import logging
import shutil
import sys

from jibe.core.config import CERTS_DIR, DEFAULT_PORT, LOG_DATE_FORMAT, LOG_FORMAT
from jibe.core.db import JibeDatabase
from jibe.core.tls import create_ssl_context, generate_self_signed_cert
from jibe.network.discovery import JibeDiscovery
from jibe.network.server import JibeServer

_QUIET_LOGGERS = ("aiosqlite", "asyncio", "zeroconf")

logger = logging.getLogger("jibe.main")


async def run_daemon(
    *,
    use_tls: bool = True,
    port: int = DEFAULT_PORT,
) -> None:
    """Run the main daemon loop.

    Starts the database, server, and discovery services, then waits
    forever until cancelled.

    Args:
        use_tls: If True, generate/load a certificate and serve wss://.
        port: The TCP port to listen on.
    """
    logger.info("Jibe daemon starting...")

    db = JibeDatabase()
    await db.open()

    ssl_context = None
    if use_tls:
        cert_path, key_path = generate_self_signed_cert()
        ssl_context = create_ssl_context(cert_path, key_path)
    else:
        logger.warning("TLS disabled — connections are unencrypted")

    server = JibeServer(db=db, port=port, ssl_context=ssl_context)
    discovery = JibeDiscovery(port=port)

    try:
        await asyncio.gather(
            server.start(),
            discovery.start(),
        )
        logger.info("Ready. Press Ctrl+C to stop.")
        await asyncio.Event().wait()

    except asyncio.CancelledError:
        logger.info("Shutting down...")

    finally:
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
        "--port",
        type=int,
        default=DEFAULT_PORT,
        help=f"TCP port to listen on (default: {DEFAULT_PORT})",
    )
    parser.add_argument(
        "--regen-certs",
        action="store_true",
        help="Delete existing TLS certificates and regenerate",
    )
    parser.add_argument(
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
    """Configure logging level, format, and terminal colors.

    Uses logging.addLevelName() to inject ANSI codes into the global level name
    table — %(levelname)s in LOG_FORMAT then picks up the colors automatically,
    with no Formatter subclass needed. Colors are suppressed when stderr is not
    a TTY so piped or redirected output stays clean.
    """
    logging.basicConfig(
        level=logging.DEBUG if verbose else logging.INFO,
        format=LOG_FORMAT,
        datefmt=LOG_DATE_FORMAT,
    )

    if sys.stderr.isatty():
        _RESET = "\033[0m"
        _COLORS = {
            logging.DEBUG: "\033[36m",  # cyan
            logging.INFO: "\033[32m",  # green
            logging.WARNING: "\033[33m",  # yellow
            logging.ERROR: "\033[31m",  # red
            logging.CRITICAL: "\033[35m",  # magenta
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
    _configure_logging(verbose=args.verbose)

    if args.regen_certs:
        _handle_regen_certs()

    try:
        asyncio.run(run_daemon(use_tls=not args.no_tls, port=args.port))
    except KeyboardInterrupt:
        logger.info("Jibe daemon stopped cleanly.")


if __name__ == "__main__":
    main()
