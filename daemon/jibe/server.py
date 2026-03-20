from aiohttp import web


class JibeServer:
    """Manages the aiohttp application lifecycle.

    Provides `start()` and `stop()` async methods so the server can be
    run alongside other async services (like mDNS discovery) using
    `asyncio.gather()`.
    """

    # TODO: Implement with feat/server-skeleton
    pass
