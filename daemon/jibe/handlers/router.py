"""Message router for the Jibe daemon.

Routes authenticated messages to their registered handler functions.
This is the extensibility mechanism — adding a new feature is one line:

    router.register(MessageType.CLIPBOARD_SYNC, clipboard.handle_sync)

Design principles:
  - Handlers are simple async functions: (conn, msg) → None
  - One handler per message type — no chaining, no middleware
  - Unhandled types get a structured error response back to the client
  - A handler that raises gets caught and logged — the connection survives

Why not a class hierarchy?
  Plain functions are simpler, easier to test, and sufficient for our
  use case. We're not building a web framework — we're routing ~10
  message types. A dict lookup is the right tool.
"""

import logging
from typing import Awaitable, Callable

from jibe.core.api import JibeMessage, MessageType, format_error
from jibe.network.connection import JibeConnection

logger = logging.getLogger(__name__)

Handler = Callable[[JibeConnection, JibeMessage], Awaitable[None]]


class MessageRouter:
    """Routes validated messages to their registered handlers.

    Usage:
        router = MessageRouter()
        router.register(MessageType.PING, handle_ping)
        router.register(MessageType.CLIPBOARD_SYNC, handle_clipboard)

        # In the server's message loop:
        await router.dispatch(conn, jibe_msg)
    """

    def __init__(self) -> None:
        self._handlers: dict[MessageType, Handler] = {}

    def register(self, msg_type: MessageType, handler: Handler) -> None:
        """Register a handler for a specific message type.

        Args:
            msg_type: The message type this handler responds to.
            handler: An async function with signature (conn, msg) → None.

        Raises:
            ValueError: If a handler is already registered for this type.
        """
        if msg_type in self._handlers:
            raise ValueError(f"Handler already registered for {msg_type.value}")
        self._handlers[msg_type] = handler
        logger.debug("Registered handler for %s", msg_type.value)

    async def dispatch(self, conn: JibeConnection, msg: JibeMessage) -> None:
        """Route a message to its registered handler.

        If no handler is registered for the message type, sends an error
        response back to the client. If the handler raises an exception,
        the error is logged and an error response is sent — the connection
        is never killed by a handler bug.

        Args:
            conn: The authenticated connection that sent the message.
            msg: The validated message to route.
        """
        handler = self._handlers.get(msg.type)

        if handler is None:
            logger.warning(
                "No handler registered for %s from %s",
                msg.type.value,
                conn.id,
            )
            await conn.send(
                format_error(
                    "not_implemented",
                    f"Message type '{msg.type.value}' is not yet supported.",
                )
            )
            return

        try:
            await handler(conn, msg)
        except Exception:
            logger.exception(
                "Handler for %s raised an exception (conn=%s)",
                msg.type.value,
                conn.id,
            )
            await conn.send(
                format_error(
                    "internal_error",
                    "The server encountered an error processing your message.",
                )
            )

    @property
    def registered_types(self) -> list[MessageType]:
        """List all message types that have a handler registered."""
        return list(self._handlers.keys())
