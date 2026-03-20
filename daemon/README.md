# Jibe Daemon

The Python daemon that powers Jibe on the Linux side. It broadcasts itself on the local network via mDNS, accepts WebSocket connections from Android, and processes protocol messages.

## Prerequisites

- Python 3.13+
- Linux (Arch recommended, any distro with D-Bus should work)

## Setup

```bash
# Create a virtual environment (one-time)
python -m venv .venv

# Activate the virtual environment
source .venv/bin/activate

# Install dependencies
pip install -r requirements.txt
```

## Run the daemon

```bash
# Make sure the venv is activated first
source .venv/bin/activate

python main.py
```

## Run tests

```bash
source .venv/bin/activate

pytest tests/ -v
```

## Project structure

```
daemon/
├── main.py              # Entrypoint — start here
├── requirements.txt     # Pinned Python dependencies
├── jibe/                # Core package
│   ├── __init__.py      # Version constant
│   ├── api.py           # Message parsing & protocol validation
│   ├── server.py        # aiohttp WebSocket + HTTP server
│   ├── discovery.py     # mDNS broadcast via zeroconf
│   ├── auth.py          # Device pairing (future)
│   ├── clipboard.py     # Clipboard sync (future)
│   ├── notifications.py # Notification mirroring (future)
│   ├── transfer.py      # File transfer (future)
│   └── db.py            # SQLite storage (future)
├── tests/               # pytest test suite
└── web/                 # React web UI assets (future)
```
