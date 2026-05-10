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
    Daemon->>Client: Reply: IP=192.168.1.10, port=8765,<br/>version=0.5.0-beta, platform=linux

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

---

## TLS Certificate Lifecycle

The daemon uses a **permanent self-signed certificate**, following an SSH-like "Trust on First Use" (TOFU) model. Because the daemon runs on a local network without a public domain name, standard Certificate Authorities (like Let's Encrypt) cannot be used.

To ensure strict security and prevent Man-in-the-Middle (MITM) attacks, Jibe relies on **out-of-band certificate pinning** via a QR code.

```mermaid
sequenceDiagram
    participant Client as Android App
    participant Daemon as Linux Daemon

    Note over Daemon: 1. Certificate Lifecycle
    Daemon->>Daemon: First Run: Generate RSA-4096 Self-Signed Cert
    Daemon->>Daemon: Subsequent Runs: Load existing permanent cert
    Daemon->>Daemon: Compute SHA-256 Fingerprint

    Note over Client,Daemon: 2. Out-of-Band Pinning
    Daemon-->>Client: App scans QR Code (receives Cert Fingerprint)

    Note over Client,Daemon: 3. Secure Transport Handshake
    Client->>Daemon: Initiate TLS Connection
    Daemon->>Client: Present Self-Signed Certificate
    
    Note over Client: App computes SHA-256 of received cert<br/>and strictly compares against pinned fingerprint
    
    Client->>Daemon: TLS Handshake Complete
    Note over Client,Daemon: Encrypted tunnel established.<br/>All WebSocket traffic (wss://) is now secure.
```

