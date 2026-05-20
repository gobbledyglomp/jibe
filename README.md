<div align="center">
  <p align="center">
    <img src="branding/logos/banner.svg" alt="Jibe" width="720"><br><br>
    <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="License: GPL v3"></a>
    <a href="https://github.com/gobbledyglomp/jibe/releases"><img src="https://img.shields.io/badge/version-0.9.0--pre-orange" alt="Version"></a>
  </p>
</div>

---

Clipboard sync · file transfer · notification mirroring · find my phone · presentation remote · battery status — over LAN. No accounts, no cloud, no cables.

---

## Android

Download [`app-release.apk`](https://github.com/gobbledyglomp/jibe/releases) and sideload it. Keep the phone on the same Wi-Fi as the daemon. It discovers the daemon automatically.

---

## Linux daemon

| | `install.sh` | `pipx` | Docker |
|---|---|---|---|
| Icon in app launcher | ✓ | optional | ✗ |
| System tray | ✓ | optional | ✗ |
| Autostart on login | ✓ (default) | optional | ✓ (`restart: unless-stopped`) |
| Clipboard sync | ✓ | ✓ | ✗ |
| Presentation remote | ✓ | ✓ | ✗ |
| Terminal required day-to-day | ✗ | ✗ | ✗ |

Dashboard is always at **http://127.0.0.1:8777/** (same for all paths).

---

### `install.sh` (recommended for desktop users)

```bash
# 1. Prerequisites (example — Arch)
sudo pacman -S python-pipx git xdotool   # pipx is required; rest is optional

# 2. Clone and install
git clone https://github.com/gobbledyglomp/jibe.git && cd jibe
bash deploy/install.sh          # adds icon, launcher entry, starts on login
```

> Omit autostart: `bash deploy/install.sh --no-autostart`

**First-run password** — generated once, printed to the log:
```bash
journalctl --user -u jibe -b --no-pager | grep -A4 'Password (save now)'
```

Then: bookmark **http://127.0.0.1:8777/** → log in as `admin` → go to [Pair](#pairing).

**Running Jibe** — with default autostart, the daemon already runs in the background. Use the application menu or `systemctl --user status jibe`. Do not run `jibe` in a terminal unless you stopped the service first (`systemctl --user stop jibe`).

**Factory reset** (fresh database, new admin password, re-pair Android):
```bash
bash deploy/reset-data.sh
```
Or: `systemctl --user stop jibe && jibe --reset-data`

**Uninstall** — from the cloned repo:
```bash
bash deploy/uninstall.sh          # keeps data (reinstall preserves pairing)
bash deploy/uninstall.sh --purge  # also deletes ~/.local/share/jibe
```

---

### `pipx` (minimal, bring your own launcher)

```bash
# 1. Prerequisites
sudo pacman -S python-pipx       # Arch; or: sudo apt install pipx

# 2. Install
pipx install git+https://github.com/gobbledyglomp/jibe.git#subdirectory=daemon
pipx ensurepath && exec $SHELL   # make 'jibe' available

# 3. First run — copy the password from the output
jibe
```

**First-run password** is printed on stdout. Save it, then Ctrl+C.

Optional autostart (systemd user service):
```bash
cp deploy/jibe.service ~/.config/systemd/user/
systemctl --user daemon-reload && systemctl --user enable --now jibe
```

Then: bookmark **http://127.0.0.1:8777/** → log in as `admin` → go to [Pair](#pairing).

If you also enabled the systemd service, do not run `jibe` manually at the same time (port conflict). See [docs/troubleshooting.md](docs/troubleshooting.md).

**Factory reset:** `systemctl --user stop jibe && jibe --reset-data`

**Uninstall:** `pipx uninstall jibe` (data remains in `~/.local/share/jibe` unless you run `jibe --reset-data --yes` first).

---

### Docker (headless — no clipboard, tray, or remote)

```bash
# 1. Prerequisites: Docker + Compose v2

# 2. Start
git clone https://github.com/gobbledyglomp/jibe.git && cd jibe
docker compose up -d

# 3. Retrieve the first-run password
docker compose logs daemon | grep -A4 'Password (save now)'
```

Then: bookmark **http://127.0.0.1:8777/** → log in as `admin` → go to [Pair](#pairing).

---

### Pairing

1. Open the dashboard → **Daemon → Start Pairing** (or tray → Start Pairing).
2. A 6-digit PIN appears.
3. Open the Android app → enter the PIN.
4. Done. The device reconnects automatically from now on.

After pairing, change the default admin password under **Settings → Users**.

---

## Troubleshooting

Install issues, port conflicts, tray icons, and known limitations: [docs/troubleshooting.md](docs/troubleshooting.md).

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[GNU General Public License v3.0](LICENSE)
