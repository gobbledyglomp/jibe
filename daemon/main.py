"""Jibe daemon entrypoint.

Run this file to start the Jibe daemon:

    python main.py             # TLS enabled (default)
    python main.py --no-tls    # plaintext for development

This wires together all daemon components:
  - JibeDatabase: persistent storage (SQLite)
  - JibeServer: WebSocket connections + HTTP API
  - JibeDiscovery: mDNS broadcast on the local network

All services run concurrently in a single asyncio event loop.
"""

import argparse
import asyncio
import logging

from jibe.config import LOG_DATE_FORMAT, LOG_FORMAT
from jibe.db import JibeDatabase
from jibe.discovery import JibeDiscovery
from jibe.server import JibeServer
from jibe.tls import create_ssl_context, generate_self_signed_cert

logging.basicConfig(
    level=logging.INFO,
    format=LOG_FORMAT,
    datefmt=LOG_DATE_FORMAT,
)

logger = logging.getLogger("jibe.main")


async def run_daemon(*, use_tls: bool = True) -> None:
    """Run the main daemon loop.

    Starts the database, server, and discovery services, then waits
    forever until cancelled.

    Args:
        use_tls: If True, generate/load a certificate and serve wss://.
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

    server = JibeServer(db=db, ssl_context=ssl_context)
    discovery = JibeDiscovery()

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


def main() -> None:
    """Entry point. Parses args, sets up the event loop, handles Ctrl+C."""
    parser = argparse.ArgumentParser(description="Jibe daemon")
    parser.add_argument(
        "--no-tls",
        action="store_true",
        help="Disable TLS (plaintext WebSocket for development)",
    )
    args = parser.parse_args()

    try:
        asyncio.run(run_daemon(use_tls=not args.no_tls))
    except KeyboardInterrupt:
        logger.info("Jibe daemon stopped cleanly.")


if __name__ == "__main__":
    main()
