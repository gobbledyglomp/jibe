# Jibe WebSocket Protocol Specification

> Version: 0.6.0-beta · Status: Draft

## Design Philosophy

Jibe communicates over a single **WebSocket** connection between the Android app and the Linux daemon. Control messages are **JSON objects** sent as WebSocket **text** frames. **File payloads** are sent as WebSocket **binary** frames with a small fixed header (see [`file.chunk` (binary)](#filechunk-binary)). The protocol is:

- **Human-readable control plane** - JSON makes debugging easy (inspect with browser devtools, `websocat`, or `jq`)
- **Efficient bulk transfer** - raw bytes over binary frames avoid Base64 and oversized JSON on large files
- **Easy to extend** - adding a new message type never breaks existing ones; unknown types are rejected with a clean error, not silently ignored
- **Stateless per-message** - each message is self-contained; the server does not track request/response pairs (the connection itself is stateful, but individual messages are not)

## Connection Lifecycle

```mermaid
sequenceDiagram
    participant Android
    participant Daemon

    Note over Android,Daemon: 1. Discover via mDNS (_jibe._tcp.local.)

    Android->>Daemon: 2. Open WebSocket (ws://host:8765/ws)
    Android->>Daemon: 3. auth.request { type, device_name, pin }
    Daemon-->>Android: 4. auth.response { type, accepted, reason }

    Note over Android,Daemon: 5. Session established
    Android->>Daemon: ping
    Daemon-->>Android: pong
    Android->>Daemon: clipboard.sync / notification / file.start / file.cancel…
    Daemon-->>Android: clipboard.sync

    Android->>Daemon: 6. close frame
```

### Step-by-step

1. **Discovery** - The daemon registers itself on the local network using mDNS (service type `_jibe._tcp.local.`). The Android app scans for this service and learns the daemon's IP and port.

2. **WebSocket handshake** - The app opens a WebSocket connection to `ws://<host>:<port>/ws`. This is a standard HTTP upgrade - no custom headers required.

3. **Authentication** - The app sends an `auth.request`. **Pairing mode must already be active on the daemon** (`--pair` flag, `SIGUSR1`, or your launcher UI) so a PIN is shown before the client connects. The request includes that PIN and the device name for first-time pairing. Trusted devices reconnect with their stored fingerprint only (no PIN). If pairing is not active, the daemon rejects the probe until the user starts pairing.

4. **Auth response** - The daemon responds with `auth.response`. If `accepted` is `true`, the session is live. If `false`, the WebSocket usually stays open so the client can adjust (unless the daemon closes it for rate limiting).

5. **Active session** - Both sides exchange messages freely. Keepalive is maintained via `ping`/`pong`. Any side can send `clipboard.sync` or `notification` messages at any time.

6. **Disconnection** - Either side can close the WebSocket normally. The daemon logs the disconnection and cleans up resources.

### Rules

- The **first message** from a client **must** be `auth.request`. Any other message before authentication will receive an `error` response with code `auth_required`.
- After authentication, either side can send any message type at any time.
- Unknown message types receive an `error` response with code `unknown_type`.
- Malformed JSON receives an `error` response with code `malformed_json`.

---

## Message Format

Except for [binary file chunks](#filechunk-binary), every message is a JSON object with at minimum a `type` field:

```json
{
  "type": "<message_type>",
  ...additional fields depending on type
}
```

The `type` field determines the structure of the rest of the message. Additional fields are type-specific and documented below.

---

## Message Types

### `auth.request`

| Field         | Value                                                             |
| ------------- | ----------------------------------------------------------------- |
| **Direction** | Android → Linux                                                   |
| **Purpose**   | Authenticate a device with the daemon using a PIN and device name |

```json
{
  "type": "auth.request",
  "pin": "482916",
  "device_name": "Pixel 8 Pro"
}
```

| Field         | Type     | Description                                      |
| ------------- | -------- | ------------------------------------------------ |
| `type`        | `string` | Always `"auth.request"`                          |
| `pin`         | `string` | 6-digit PIN displayed on the daemon's UI or tray |
| `device_name` | `string` | Human-readable name of the connecting device     |

---

### `auth.response`

| Field         | Value                                                             |
| ------------- | ----------------------------------------------------------------- |
| **Direction** | Linux → Android                                                   |
| **Purpose**   | Inform the device whether authentication was accepted or rejected |

```json
{
  "type": "auth.response",
  "accepted": true,
  "reason": ""
}
```

```json
{
  "type": "auth.response",
  "accepted": false,
  "reason": "Invalid PIN"
}
```

| Field      | Type      | Description                                             |
| ---------- | --------- | ------------------------------------------------------- |
| `type`     | `string`  | Always `"auth.response"`                                |
| `accepted` | `boolean` | `true` if the device is now authenticated               |
| `reason`   | `string`  | Empty on success; human-readable explanation on failure |

---

### `ping`

| Field         | Value                                                   |
| ------------- | ------------------------------------------------------- |
| **Direction** | Bidirectional                                           |
| **Purpose**   | Keepalive probe — ensures the connection is still alive |

```json
{
  "type": "ping"
}
```

| Field  | Type     | Description     |
| ------ | -------- | --------------- |
| `type` | `string` | Always `"ping"` |

No additional fields. The receiver should respond with a `pong`.

---

### `pong`

| Field         | Value                                                 |
| ------------- | ----------------------------------------------------- |
| **Direction** | Bidirectional                                         |
| **Purpose**   | Keepalive response — confirms the connection is alive |

```json
{
  "type": "pong"
}
```

| Field  | Type     | Description     |
| ------ | -------- | --------------- |
| `type` | `string` | Always `"pong"` |

No additional fields.

---

### `clipboard.sync`

| Field         | Value                                         |
| ------------- | --------------------------------------------- |
| **Direction** | Bidirectional                                 |
| **Purpose**   | Synchronise clipboard content between devices |

```json
{
  "type": "clipboard.sync",
  "content": "https://example.com/interesting-article"
}
```

| Field     | Type     | Description                        |
| --------- | -------- | ---------------------------------- |
| `type`    | `string` | Always `"clipboard.sync"`          |
| `content` | `string` | The current clipboard text content |

---

### `notification`

| Field         | Value                                               |
| ------------- | --------------------------------------------------- |
| **Direction** | Android → Linux                                     |
| **Purpose**   | Mirror an Android notification to the Linux desktop |

```json
{
  "type": "notification",
  "app": "com.whatsapp",
  "app_name": "WhatsApp",
  "title": "Alice",
  "body": "Hey, are you coming tonight?",
  "timestamp": 1710892800,
  "icon": "<optional base64 PNG>"
}
```

| Field       | Type      | Description                                                           |
| ----------- | --------- | --------------------------------------------------------------------- |
| `type`      | `string`  | Always `"notification"`                                               |
| `app`       | `string`  | Android package name of the source app                                |
| `app_name`  | `string`  | Human-readable app label (shown as the desktop notification application name) |
| `title`     | `string`  | Notification title                                                    |
| `body`      | `string`  | Notification body text                                                |
| `timestamp` | `integer` | Unix timestamp (seconds) when the notification was created on Android |
| `icon`      | `string`  | Optional PNG as Base64 — small app icon (`notify-send --icon`)        |

---

### `file.start`

| Field         | Value                                                      |
| ------------- | ---------------------------------------------------------- |
| **Direction** | Android → Linux                                            |
| **Purpose**   | Announce the start of a file transfer and provide metadata |

```json
{
  "type": "file.start",
  "id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "filename": "photo_2024.jpg",
  "size": 2048576,
  "total_chunks": 32
}
```

| Field          | Type      | Description                                                              |
| -------------- | --------- | ------------------------------------------------------------------------ |
| `type`         | `string`  | Always `"file.start"`                                                    |
| `id`           | `string`  | UUID identifying this transfer — all subsequent chunks reference this ID |
| `filename`     | `string`  | Original filename including extension                                    |
| `size`         | `integer` | Total file size in bytes                                                 |
| `total_chunks` | `integer` | Number of binary [`file.chunk`](#filechunk-binary) frames that will follow |

---

### `file.chunk` (binary)

| Field         | Value                                                    |
| ------------- | -------------------------------------------------------- |
| **Direction** | Android → Linux                                          |
| **Purpose**   | Deliver one raw chunk of a file being transferred        |
| **Transport** | WebSocket **binary** frame (not JSON; no `type` field)    |

After `file.start`, the sender sends exactly `total_chunks` binary frames in order.
Each frame body is:

| Offset | Size | Type / endianness | Description |
| ------ | ---- | ----------------- | ----------- |
| `0`    | `4`  | ASCII             | Magic bytes `"JBFC"` (`0x4A 0x42 0x46 0x43`) |
| `4`    | `1`  | unsigned byte     | Header format version (currently `1`) |
| `5`    | `1`  | unsigned byte     | Flags (reserved; send `0`) |
| `6`    | `2`  | big-endian `u16`  | Reserved (send `0`) |
| `8`    | `4`  | big-endian `u32`  | Chunk index (zero-based, matches `file.chunk.ack`) |
| `12`   | `4`  | big-endian `u32`  | Payload length in bytes (`N`) |
| `16`   | `16` | raw bytes         | Transfer UUID (same as `file.start` `id`, RFC 4122 byte layout) |
| `32`   | `N`  | raw bytes         | Chunk payload (plaintext file bytes) |

Total frame size is **`32 + N`** bytes. The final chunk may be smaller than the
implementation’s preferred chunk size so that the sum of all payloads equals `size`
from `file.start`.

Flow control matches JSON-era behaviour: the sender must wait for the corresponding
`file.chunk.ack` before sending the next binary chunk. This keeps WebSocket send
queues bounded.

On LAN, implementations should use a large raw chunk size (for example **4 MiB**) so
round-trip latency per ack does not dominate throughput.

Legacy JSON `file.chunk` messages with Base64 `data` are **not** accepted by current
daemons; clients must use binary frames.

---

### `file.chunk.ack`

| Field         | Value                                                                  |
| ------------- | ---------------------------------------------------------------------- |
| **Direction** | Linux → Android                                                        |
| **Purpose**   | Acknowledge that one file chunk has been validated and written to disk |

```json
{
  "type": "file.chunk.ack",
  "id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "index": 0,
  "ok": true,
  "bytes_received": 2097152
}
```

```json
{
  "type": "file.chunk.ack",
  "id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "index": 0,
  "ok": false,
  "bytes_received": 0,
  "reason": "Invalid chunk header"
}
```

| Field            | Type      | Description                                                        |
| ---------------- | --------- | ------------------------------------------------------------------ |
| `type`           | `string`  | Always `"file.chunk.ack"`                                          |
| `id`             | `string`  | Transfer ID matching the binary chunk’s UUID in the header           |
| `index`          | `integer` | Zero-based chunk index being acknowledged                          |
| `ok`             | `boolean` | `true` if the chunk was written and the next chunk may be sent      |
| `bytes_received` | `integer` | Total raw bytes written for this transfer after the acknowledged chunk |
| `reason`         | `string`  | Present when `ok` is `false` — human-readable failure explanation |

---

### `file.cancel`

| Field         | Value                                                                  |
| ------------- | ---------------------------------------------------------------------- |
| **Direction** | Android → Linux                                                        |
| **Purpose**   | Abort the current in-flight upload for a transfer id without closing the session |

```json
{
  "type": "file.cancel",
  "id": "f47ac10b-58cc-4372-a567-0e02b2c3d479"
}
```

| Field  | Type     | Description                                                 |
| ------ | -------- | ----------------------------------------------------------- |
| `type` | `string` | Always `"file.cancel"`                                      |
| `id`   | `string` | Transfer ID from the corresponding `file.start` message     |

The daemon removes partial data for that transfer and responds with [`file.ack`](#fileack) where `ok` is `false` and `reason` is `"Cancelled"`. Other connections cannot cancel a transfer they did not start.

---

### `file.done`

| Field         | Value                                                                    |
| ------------- | ------------------------------------------------------------------------ |
| **Direction** | Android → Linux                                                          |
| **Purpose**   | Signal that a file transfer is complete with a checksum for verification |

```json
{
  "type": "file.done",
  "id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "checksum": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
}
```

| Field      | Type     | Description                                                  |
| ---------- | -------- | ------------------------------------------------------------ |
| `type`     | `string` | Always `"file.done"`                                         |
| `id`       | `string` | Transfer ID matching the `file.start` message                |
| `checksum` | `string` | SHA-256 hash of the complete file for integrity verification |

---

### `file.ack`

| Field         | Value                                                                      |
| ------------- | -------------------------------------------------------------------------- |
| **Direction** | Linux → Android                                                            |
| **Purpose**   | Final outcome for a transfer: success after `file.done`, failure after `file.done`, or cancellation after `file.cancel` |

```json
{
  "type": "file.ack",
  "id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "ok": true
}
```

```json
{
  "type": "file.ack",
  "id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "ok": false,
  "reason": "Checksum mismatch"
}
```

```json
{
  "type": "file.ack",
  "id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "ok": false,
  "reason": "Cancelled"
}
```

| Field    | Type      | Description                                                        |
| -------- | --------- | ------------------------------------------------------------------ |
| `type`   | `string`  | Always `"file.ack"`                                                |
| `id`     | `string`  | Transfer ID matching the `file.start` / `file.done` / `file.cancel` messages |
| `ok`     | `boolean` | `true` if the file was verified and saved under `~/Downloads`       |
| `reason` | `string`  | Present when `ok` is `false` — human-readable failure explanation |

---

### `device.battery`

| Field         | Value                                                       |
| ------------- | ----------------------------------------------------------- |
| **Direction** | Android → Linux                                             |
| **Purpose**   | Report the current battery state of the Android device      |

Sent once after authentication and again whenever the battery level or charging state changes.

```json
{
  "type": "device.battery",
  "level": 85,
  "charging": true
}
```

| Field      | Type      | Description                                                         |
| ---------- | --------- | ------------------------------------------------------------------- |
| `type`     | `string`  | Always `"device.battery"`                                           |
| `level`    | `integer` | Battery percentage (0–100)                                          |
| `charging` | `boolean` | `true` if the device is connected to a charger                      |

---

### `device.ring`

| Field         | Value                                                      |
| ------------- | ---------------------------------------------------------- |
| **Direction** | Linux → Android                                            |
| **Purpose**   | Command the Android device to play a ringtone and show a full-screen alert |

```json
{
  "type": "device.ring"
}
```

| Field  | Type     | Description            |
| ------ | -------- | ---------------------- |
| `type` | `string` | Always `"device.ring"` |

No additional fields. The Android app plays the system ringtone and presents a dismissible full-screen alert.

---

### `remote.key`

| Field         | Value                                                      |
| ------------- | ---------------------------------------------------------- |
| **Direction** | Android → Linux                                            |
| **Purpose**   | Trigger a key event on the Linux desktop (presentation remote) |

```json
{
  "type": "remote.key",
  "key": "next"
}
```

| Field  | Type     | Description                                                                 |
| ------ | -------- | --------------------------------------------------------------------------- |
| `type` | `string` | Always `"remote.key"`                                                       |
| `key`  | `string` | One of `"next"` (→), `"prev"` (←), `"stop"` (Escape), `"blank"` (B key)   |

The daemon maps the key name to the OS key event and dispatches it to the focused window via `xdotool` (X11) or `ydotool` (Wayland).

---

### `error`

| Field         | Value                                           |
| ------------- | ----------------------------------------------- |
| **Direction** | Bidirectional                                   |
| **Purpose**   | Report a protocol-level error to the other side |

```json
{
  "type": "error",
  "code": "malformed_json",
  "message": "Failed to parse JSON: Expecting property name enclosed in double quotes at line 1 column 2"
}
```

| Field     | Type     | Description                                    |
| --------- | -------- | ---------------------------------------------- |
| `type`    | `string` | Always `"error"`                               |
| `code`    | `string` | Machine-readable error code (see table below)  |
| `message` | `string` | Human-readable error description for debugging |

---

## Error Codes

| Code             | Meaning                                         | When it occurs                                   |
| ---------------- | ----------------------------------------------- | ------------------------------------------------ |
| `malformed_json` | The message could not be parsed as valid JSON   | Received data is not valid JSON                  |
| `unknown_type`   | The `type` field contains an unrecognised value | A message has a `type` not listed in this spec   |
| `auth_required`  | The client has not yet authenticated            | A non-auth message is sent before `auth.request` |
| `auth_rejected`  | Authentication was explicitly rejected          | Invalid PIN or device not trusted                |

---

## Versioning

The protocol version follows [SemVer](https://semver.org/):

- **Patch** (0.1.x) — bug fixes, clarifications, no message changes
- **Minor** (0.x.0) — new message types added, existing types unchanged
- **Major** (x.0.0) — breaking changes to existing message types

The daemon advertises its version via mDNS TXT records and the `GET /` health endpoint. Clients should check compatibility before connecting.
