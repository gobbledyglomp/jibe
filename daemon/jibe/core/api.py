"""Parse and validate incoming WebSocket JSON messages.

All raw payloads pass through :func:`parse_message` first, producing a typed
`JibeMessage` that the rest of the daemon can trust for routing.
"""

import json
from dataclasses import dataclass
from enum import Enum
from typing import Any, Dict


class MessageType(str, Enum):
    """All valid message types in the Jibe protocol.

    Using an Enum instead of raw strings ensures we don't have typos
    when checking message types elsewhere in the codebase. The `str`
    mixin allows the enum members to be used directly as strings
    (e.g. in JSON serialization).
    """

    AUTH_REQUEST = "auth.request"
    AUTH_RESPONSE = "auth.response"
    PING = "ping"
    PONG = "pong"
    CLIPBOARD_SYNC = "clipboard.sync"
    NOTIFICATION = "notification"
    FILE_START = "file.start"
    FILE_CHUNK = "file.chunk"
    FILE_CHUNK_ACK = "file.chunk.ack"
    FILE_CANCEL = "file.cancel"
    FILE_DONE = "file.done"
    FILE_ACK = "file.ack"
    ERROR = "error"
    DEVICE_BATTERY = "device.battery"
    DEVICE_RING = "device.ring"
    DEVICE_FEATURES = "device.features"
    REMOTE_KEY = "remote.key"


@dataclass
class JibeMessage:
    """A validated message from a WebSocket client.

    The `payload` contains the original parsed JSON dict, including
    the `type` field (though `msg.type` is preferred for routing).
    """

    type: MessageType
    payload: Dict[str, Any]


class JibeError(Exception):
    """Base exception for all Jibe-specific errors."""
    pass


class ProtocolError(JibeError):
    """A violation of the protocol rules (e.g. state machine errors)."""
    pass


class AuthError(ProtocolError):
    """Authentication failed or is required."""
    pass


class InvalidMessageError(ProtocolError):
    """A received message is malformed or unrecognised."""
    def __init__(self, code: str, message: str) -> None:
        """Initialise with an error code for the client and a description.
        
        Args:
            code: The machine-readable error code (e.g. 'malformed_json').
            message: The human-readable description.
        """
        super().__init__(message)
        self.code = code


def parse_message(raw: str) -> JibeMessage:
    """Parse a raw JSON string into a validated JibeMessage.

    This function acts as a firewall. If it returns a JibeMessage, the
    caller can safely assume `msg.type` is valid and `msg.payload` is
    a dictionary.

    Note: we do not deeply validate the payload fields here — we leave
    that to the specific message handlers so this function remains fast
    and simple.

    Args:
        raw: The raw JSON string received from the WebSocket.

    Returns:
        A validated JibeMessage instance.

    Raises:
        InvalidMessageError: If the JSON is malformed or the message
            type is missing/unknown.
    """
    if not raw:
        raise InvalidMessageError("malformed_json", "Empty message")

    try:
        data = json.loads(raw)
    except json.JSONDecodeError as e:
        raise InvalidMessageError(
            "malformed_json", f"Failed to parse JSON: {str(e)}"
        )

    if not isinstance(data, dict):
        raise InvalidMessageError("malformed_json", "Message must be a JSON object")

    if "type" not in data:
        raise InvalidMessageError("malformed_json", "Missing 'type' field")

    type_str = data["type"]
    if not isinstance(type_str, str):
        raise InvalidMessageError("malformed_json", "'type' field must be a string")

    try:
        msg_type = MessageType(type_str)
    except ValueError:
        raise InvalidMessageError("unknown_type", f"Unrecognised message type: '{type_str}'")

    return JibeMessage(type=msg_type, payload=data)


def format_error(code: str, message: str) -> str:
    """Format an error response to send back to the client.

    Args:
        code: The machine-readable error code (e.g. 'auth_required').
        message: The human-readable explanation.

    Returns:
        A JSON string ready to be sent over the WebSocket.
    """
    return json.dumps({
        "type": MessageType.ERROR.value,
        "code": code,
        "message": message
    })
