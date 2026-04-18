import json
import pytest
from pathlib import Path

from jibe.api import (
    parse_message,
    format_error,
    MessageType,
    InvalidMessageError,
)

FIXTURES_DIR = Path(__file__).parent / "fixtures"

@pytest.fixture
def valid_messages():
    """Load valid message examples from the JSON fixture file."""
    with open(FIXTURES_DIR / "messages.json", "r") as f:
        return json.load(f)

# ── Valid Message Tests ──────────────────────────────────────────────────

@pytest.mark.parametrize(
    "msg_type",
    [t.value for t in MessageType]
)
def test_parse_valid_messages(valid_messages, msg_type):
    """Test that every valid message type is parsed correctly."""
    payload = valid_messages[msg_type]
    raw_json = json.dumps(payload)

    msg = parse_message(raw_json)

    assert msg.type == MessageType(msg_type)
    assert msg.payload == payload


# ── Error Case Tests ─────────────────────────────────────────────────────

def test_parse_empty_string():
    """Test that an empty string raises InvalidMessageError."""
    with pytest.raises(InvalidMessageError) as exc_info:
        parse_message("")
    
    assert exc_info.value.code == "malformed_json"
    assert "Empty message" in str(exc_info.value)


def test_parse_malformed_json():
    """Test that invalid JSON syntax raises InvalidMessageError."""
    with pytest.raises(InvalidMessageError) as exc_info:
        parse_message("{not valid json}")
    
    assert exc_info.value.code == "malformed_json"
    assert "Failed to parse JSON" in str(exc_info.value)


def test_parse_non_object_json():
    """Test that valid JSON that isn't an object/dict raises InvalidMessageError."""
    with pytest.raises(InvalidMessageError) as exc_info:
        parse_message('["an", "array"]')
    
    assert exc_info.value.code == "malformed_json"
    assert "must be a JSON object" in str(exc_info.value)


def test_parse_missing_type():
    """Test that a JSON object missing the 'type' field raises InvalidMessageError."""
    with pytest.raises(InvalidMessageError) as exc_info:
        parse_message('{"pin": "123456"}')
    
    assert exc_info.value.code == "malformed_json"
    assert "Missing 'type' field" in str(exc_info.value)


def test_parse_non_string_type():
    """Test that a 'type' field that isn't a string raises InvalidMessageError."""
    with pytest.raises(InvalidMessageError) as exc_info:
        parse_message('{"type": 123}')
    
    assert exc_info.value.code == "malformed_json"
    assert "'type' field must be a string" in str(exc_info.value)


def test_parse_unknown_type():
    """Test that an unknown 'type' string raises InvalidMessageError."""
    with pytest.raises(InvalidMessageError) as exc_info:
        parse_message('{"type": "does.not.exist"}')
    
    assert exc_info.value.code == "unknown_type"
    assert "Unrecognised message type: 'does.not.exist'" in str(exc_info.value)


def test_parse_wrong_payload_shape():
    """Test that extra or missing fields in payload are allowed at the boundary.
    
    Decision: `parse_message` only validates the `type` field. Full payload
    validation is deferred to the specific message handlers. This ensures
    the boundary validation is fast and robust, allowing partial/future
    compatibility.
    """
    raw_json = json.dumps({
        "type": "ping",
        "extra_field": "should be ignored by parser"
    })
    
    msg = parse_message(raw_json)
    assert msg.type == MessageType.PING
    assert msg.payload["extra_field"] == "should be ignored by parser"


# ── Formatting Tests ─────────────────────────────────────────────────────

def test_format_error():
    """Test the format_error helper."""
    raw = format_error("test_code", "Test message")
    parsed = json.loads(raw)
    
    assert parsed["type"] == "error"
    assert parsed["code"] == "test_code"
    assert parsed["message"] == "Test message"
