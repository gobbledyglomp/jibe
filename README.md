<div align="center">
  <img src="branding/logos/banner.png" alt="Jibe" width="720" />

  [![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
  [![Version](https://img.shields.io/badge/version-0.9.0--pre-orange)](https://github.com/gobbledyglomp/jibe/releases)
</div>

---

Clipboard sync, file transfer, notification mirroring, find my phone, presentation remote, and battery status — all over your local network. No accounts, no cloud, no cables.

---

## Install

### Linux daemon

Requires Python 3.13+ and `xdotool` (X11) or `ydotool` (Wayland) for presentation remote.

```bash
bash <(curl -fsSL https://raw.githubusercontent.com/gobbledyglomp/jibe/main/deploy/install.sh)
```

Or manually with pipx:

```bash
pipx install git+https://github.com/gobbledyglomp/jibe.git#subdirectory=daemon
```

The dashboard is at **http://127.0.0.1:8777/** — check the terminal on first run for the generated admin password.

To start on login:
```bash
cp deploy/jibe.service ~/.config/systemd/user/
systemctl --user enable --now jibe
```

### Docker (headless)

> Clipboard sync, tray icon, and presentation remote are desktop-session features and are unavailable in the standard headless Docker setup.

```bash
git clone https://github.com/gobbledyglomp/jibe.git && cd jibe
docker compose up -d
docker compose logs daemon | grep -A3 "Password"   # initial dashboard password
```

### Android

Download `app-release.apk` from [Releases](https://github.com/gobbledyglomp/jibe/releases) and sideload it. The app discovers the daemon automatically via mDNS.

To build from source: [android/README.md](android/README.md)

---

## Pair a device

1. Run `jibe --pair` (or use the tray menu / dashboard → Pairing).
2. A 6-digit PIN appears — enter it in the Android app.
3. Done. The device is trusted for all future connections.

---

## Architecture

```
Android App  ──WSS──►  Jibe Daemon (Python, aiohttp)
                              │
                    ┌─────────┼─────────┐
                 SQLite    mDNS       REST API
               (devices) (zeroconf)  (dashboard)
```

- [docs/architecture.md](docs/architecture.md) — module map and design decisions
- [docs/protocol.md](docs/protocol.md) — WebSocket message reference
- [docs/flows.md](docs/flows.md) — discovery, pairing, and connection state diagrams

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[GNU General Public License v3.0](LICENSE)
