"""User data locations and factory-reset helpers."""

from __future__ import annotations

import logging
import shutil
import sys
from pathlib import Path

from jibe.core.config import DATABASE_DIR, JIBE_CACHE_DIR

logger = logging.getLogger(__name__)


def user_data_paths() -> tuple[Path, ...]:
    """Directories removed by a full reset (database, certs, transfer cache)."""
    return (DATABASE_DIR, JIBE_CACHE_DIR)


def describe_user_data() -> str:
    """Human-readable summary of what a reset deletes."""
    lines = [
        "  • Database, paired devices, dashboard users",
        "  • TLS certificates (Android must re-trust after reset)",
        "  • Dashboard recovery key",
        "  • Incomplete file transfers in cache",
        "",
        "Paths:",
    ]
    for path in user_data_paths():
        lines.append(f"  {path}")
    return "\n".join(lines)


def reset_user_data(*, assume_yes: bool = False) -> None:
    """Delete all local Jibe state. Daemon must be stopped first."""
    paths = user_data_paths()
    existing = [p for p in paths if p.exists()]

    if not existing:
        logger.info("No Jibe user data found — nothing to reset.")
        return

    if not assume_yes:
        if not sys.stdin.isatty():
            logger.error(
                "Refusing to reset in non-interactive mode without --yes. "
                "Stop Jibe first, then run: jibe --reset-data --yes"
            )
            raise SystemExit(1)
        print("This permanently deletes all local Jibe data:\n")
        print(describe_user_data())
        print()
        try:
            answer = input("Continue? [y/N] ").strip().lower()
        except (EOFError, KeyboardInterrupt):
            print()
            raise SystemExit(1) from None
        if answer not in ("y", "yes"):
            logger.info("Reset cancelled.")
            raise SystemExit(0)

    for path in paths:
        if path.exists():
            shutil.rmtree(path)
            logger.info("Removed %s", path)

    logger.info(
        "Reset complete. Start Jibe again — a new admin password will be generated."
    )
