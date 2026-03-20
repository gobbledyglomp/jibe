"""Async SQLite database access for persistent storage.

This module will provide an async interface to an SQLite database for
storing:

  - Trusted devices (device name, public key, first seen, last seen)
  - Notification history (for the React web UI)
  - File transfer history (filenames, timestamps, checksums)
  - Clipboard history (optional, configurable)

Why aiosqlite?
  SQLite is the ideal database for a single-user, self-hosted daemon:
  no server process, no configuration, the database is just a file.
  `aiosqlite` wraps Python's built-in `sqlite3` module to work with
  asyncio — it runs SQLite operations in a background thread so they
  don't block the event loop (since SQLite itself is synchronous).

For this milestone, this module is a stub. Database access will be
implemented in a future milestone.
"""

# aiosqlite — async wrapper around sqlite3
# Not imported yet to avoid requiring the dependency until we use it
# import aiosqlite
