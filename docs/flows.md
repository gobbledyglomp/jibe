# Connection Flows

> From network discovery to active session.

## Discovery

```mermaid
sequenceDiagram
    participant Daemon
    participant LAN as Local Network
    participant Client as Android App

    Note over Daemon: Startup
    Daemon->>LAN: Multicast announcement<br/>"I am _jibe._tcp at 192.168.1.10:8765"

    Note over Daemon,LAN: Daemon is now silent — listening on UDP 5353

    Note over Client: User opens Jibe app
    Client->>LAN: Multicast query<br/>"Who provides _jibe._tcp?"
    LAN->>Daemon: (query forwarded)
    Daemon->>Client: Reply: IP=192.168.1.10, port=8765,<br/>version=0.1.0, platform=linux

    Note over Client: Discovery complete — client has IP + port
    Client->>Daemon: Open WebSocket ws://192.168.1.10:8765/ws
```

The daemon does **not** continuously broadcast. It announces once at
startup, then responds on-demand when queried. This is standard mDNS
behaviour — the same mechanism behind Chromecast and AirPlay.

---

## Connection States

A connection can only move forward through these states.

```mermaid
stateDiagram-v2
    [*] --> AWAITING_AUTH : WebSocket opened
    AWAITING_AUTH --> AUTHENTICATED : auth accepted
    AWAITING_AUTH --> DISCONNECTED : closed / rate limited
    AUTHENTICATED --> DISCONNECTED : closed
    DISCONNECTED --> [*]
```

| State | Allowed | On violation |
|-------|---------|-------------|
| `AWAITING_AUTH` | Only `auth.request` | `error: auth_required` |
| `AUTHENTICATED` | Any valid message type | Routed to handler |
| `DISCONNECTED` | Nothing | Removed from registry |
