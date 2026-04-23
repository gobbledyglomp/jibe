"""Jibe daemon entrypoint.

Run this file to start the Jibe daemon:

    python main.py

This wires together all daemon components:
  - JibeDiscovery: broadcasts the daemon on the local network via mDNS
  - JibeServer: accepts WebSocket connections and serves the HTTP API

Both services run concurrently in a single asyncio event loop.
"""

import asyncio
import logging

from jibe.config import LOG_DATE_FORMAT, LOG_FORMAT
from jibe.db import JibeDatabase
from jibe.discovery import JibeDiscovery
from jibe.server import JibeServer

logging.basicConfig(
    level=logging.INFO,
    format=LOG_FORMAT,
    datefmt=LOG_DATE_FORMAT,
)

logger = logging.getLogger("jibe.main")


async def run_daemon() -> None:
    """Run the main daemon loop.

    Starts the database, server, and discovery services, then waits
    forever until cancelled.
    """
    logger.info("Jibe daemon starting...")

    db = JibeDatabase()
    await db.open()

    server = JibeServer(db=db)
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
    """Entry point. Sets up the event loop and handles Ctrl+C."""
    try:
        asyncio.run(run_daemon())
    except KeyboardInterrupt:
        logger.info("Jibe daemon stopped cleanly.")


if __name__ == "__main__":
    main()
