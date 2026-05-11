# Jibe Daemon

The Python daemon that powers Jibe on the Linux side. It broadcasts itself on the LAN via mDNS, accepts WebSocket connections from Android, handles all protocol messages, and serves a local web dashboard.

## Prerequisites

**Python 3.13+** plus the following system packages:

| Package | Purpose |
|---------|---------|
| `xdotool` | Presentation remote (X11) |
| `ydotool` + `ydotoold` | Presentation remote (Wayland) |
| `dbus` / `libnotify` | Notification mirroring via `notify-send` |
| `wl-clipboard` or `xclip` | Clipboard sync |

**Arch Linux:**
```bash
sudo pacman -S xdotool ydotool libnotify wl-clipboard
```

**Debian/Ubuntu:**
```bash
sudo apt install xdotool ydotool libnotify-bin wl-clipboard
```

---

## Install — pip/pipx (recommended)

For a full native install (pipx + icon + .desktop entry + optional autostart):
```bash
bash <(curl -fsSL https://raw.githubusercontent.com/gobbledyglomp/jibe/main/deploy/install.sh)
```

Or just the daemon:
```bash
pipx install git+https://github.com/gobbledyglomp/jibe.git#subdirectory=daemon
jibe --pair
```

`pipx` creates an isolated environment automatically. The `jibe` binary lands in `~/.local/bin/`.

---

## Install — development (venv)

```bash
cd daemon
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

# Run directly
python main.py --pair
```

---

## Usage

```
jibe                   # start daemon (TLS on, tray on)
jibe --pair            # print 6-digit PIN to pair a new Android device
jibe --no-tls          # plaintext WebSocket (development only)
jibe -p 9000           # listen on a custom port
jibe --regen-certs     # force regenerate the self-signed TLS certificate
jibe --no-tray         # headless mode (no system tray)
jibe -v                # verbose / debug logging
```

Ports (defaults):
- **8776** — WSS WebSocket (Android connections, TLS)
- **8777** — HTTP dashboard / REST API (localhost-only, plain HTTP)

To pair at any time without restarting: `kill -USR1 <pid>`

---

## Run as a systemd user service

```bash
# Copy the unit template (assumes pipx install)
cp ../deploy/jibe.service ~/.config/systemd/user/

# Enable and start
systemctl --user daemon-reload
systemctl --user enable --now jibe

# Logs
journalctl --user -u jibe -f
```

For the Wayland presentation remote, also enable the companion unit:
```bash
cp ../deploy/ydotoold.service ~/.config/systemd/user/
systemctl --user enable --now ydotoold
```

---

## Run with Docker

See the root [docker-compose.yml](../docker-compose.yml). Docker runs headless (`--no-tray`); clipboard sync, tray, and presentation remote require additional host mounts (documented as comments in the compose file).

```bash
docker compose up -d
# dashboard at http://localhost:8777/
```

---

## Run tests

```bash
cd daemon
source .venv/bin/activate
pytest tests/ -v
```

---

## Project structure

```
daemon/
├── main.py                  # Thin script entry point (delegates to jibe.cli)
├── requirements.txt         # Pinned dependencies for development / Docker
├── pyproject.toml           # PEP 621 package metadata + pip/pipx entry point
└── jibe/
    ├── __init__.py          # __version__
    ├── cli.py               # Full CLI wiring (main() entry point)
    ├── core/
    │   ├── api.py           # Message parsing and protocol validation
    │   ├── auth.py          # PIN pairing, fingerprint trust, rate limiting
    │   ├── auth_jwt.py      # JWT session auth for the dashboard
    │   ├── config.py        # All constants (port, paths, timeouts, …)
    │   ├── db.py            # Async SQLite layer (devices, history, sessions)
    │   └── tls.py           # Self-signed certificate generation and SSL context
    ├── handlers/
    │   ├── battery.py       # Device battery telemetry
    │   ├── clipboard.py     # Clipboard sync (read/write via pyperclip/wl-paste)
    │   ├── device_features.py # Feature flag negotiation
    │   ├── notifications.py # Notification mirroring via notify-send
    │   ├── ping.py / pong.py # Heartbeat handlers
    │   ├── remote.py        # Presentation remote key dispatch (xdotool/ydotool)
    │   ├── ring.py          # Find my phone — outbound ring message
    │   ├── router.py        # MessageRouter — dispatches by message type
    │   └── transfer.py      # Chunked file upload with checksum verification
    ├── network/
    │   ├── connection.py    # Per-connection state machine + ConnectionRegistry
    │   ├── dashboard_event_log.py # In-memory event log for dashboard live feed
    │   ├── discovery.py     # mDNS broadcast via zeroconf
    │   ├── ping_probe.py    # RTT probe tracker
    │   └── server.py        # aiohttp: WSS server + plain-HTTP dashboard
    ├── ui/
    │   └── tray.py          # pystray system tray (optional, desktop only)
    └── web/
        └── static/          # Dashboard HTML/CSS/JS (served at /web/)
```
