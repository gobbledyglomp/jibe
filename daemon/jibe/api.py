import json
from dataclasses import dataclass
from enum import Enum
from typing import Any


def parse_message(raw: str) -> None:
    """Parse a raw JSON string into a validated JibeMessage.

    Args:
        raw: The raw JSON string received from the WebSocket.

    Returns:
        A validated JibeMessage instance.

    Raises:
        InvalidMessageError: If the JSON is malformed or the message
            type is missing/unknown.
    """
    # TODO: Implement with feat/message-parser
    pass
