# Troubleshooting

Common install/runtime issues and known limitations as of **0.9.0-pre**.

## Install and uninstall

### `install.sh` — remove Jibe completely

From the repo you cloned:

```bash
bash deploy/uninstall.sh
```

This stops the user systemd unit, runs `pipx uninstall jibe`, and removes the `.desktop` entry and launcher icon. **User data** (`~/.local/share/jibe` — SQLite DB, TLS certs, admin password hash) is kept on purpose; delete that folder for a full reset.

### `pipx` only — remove

```bash
pipx uninstall jibe
systemctl --user disable --now jibe 2>/dev/null || true
rm -f ~/.config/systemd/user/jibe.service
```

### Docker — remove

```bash
docker compose down
# optional: rm -rf the clone; data lives in the mounted XDG volume
```

---

## Port already in use (`errno 98`)

**Symptom:** `OSError: [Errno 98] address already in use` on port **8776** (or **8777** for the dashboard).

**Cause:** Two daemon instances. Typical case: `install.sh` enabled the systemd user service (`jibe`), then you ran `jibe` again in a terminal.

**Fix:**

```bash
systemctl --user status jibe          # is the service running?
systemctl --user stop jibe            # only if you want a manual foreground run
jibe                                  # or use the service, not both
```

Check listeners: `ss -tlnp | grep -E '8776|8777'`

---

## Tray icon invisible or blank

**Symptom:** Log line `Tray icon not found at .../branding/... — using blank placeholder`.

**Cause (fixed in recent builds):** pipx installs do not include the git `branding/` tree; the tray must load `jibe/assets/jibe-tray-icon.png` from the wheel.

**Fix:** Reinstall after pulling the fix:

```bash
bash deploy/install.sh    # or: pipx install --force ./daemon
systemctl --user restart jibe
```

**Wayland without AppIndicator:** Install system packages `python-gobject` and `libayatana-appindicator3-gtk3` (names vary by distro). Without them, pystray may not show a tray entry on Wayland.

---

## First-run admin password

| Install path | Where the password appears |
|---|---|
| `install.sh` + systemd | `journalctl --user -u jibe -b \| grep -A4 'Password (save now)'` |
| `jibe` in foreground | Printed once on stdout when the DB is created |
| Docker | `docker compose logs daemon \| grep -A4 'Password (save now)'` |

It is shown **once**. If you missed it, reset by stopping Jibe and removing `~/.local/share/jibe/jibe.db` (you will lose paired devices and settings).

---

## Android: "App not installed"

Usually a **signature mismatch** (debug APK vs release) or an older install with a higher `versionCode`.

```bash
adb uninstall com.jibe.app
adb install -r app-release.apk
```

Use `adb install -v` for the exact failure reason.

---

## Install / packaging audit (high-risk areas)

Areas that historically caused user-visible bugs and deserve extra scrutiny:

| Area | Risk | Notes |
|---|---|---|
| **pipx / wheel layout** | High | Assets must ship inside `jibe/` (`package-data`). Paths must not assume a git checkout beside the venv. |
| **Single instance** | High | No global lock file; systemd + manual `jibe` double-starts collide on 8776/8777. |
| **systemd user unit** | Medium | `jibe.service` assumes a graphical user session for tray; headless logins get no tray (expected). |
| **Docker** | Medium | No clipboard (no display), `JIBE_NO_TRAY=1`; mDNS needs `network_mode: host`. |
| **TLS + dashboard** | Medium | WSS on 8776, plain HTTP dashboard on 8777 localhost only. |
| **Clipboard monitor** | Medium | Headless hosts log once and disable; incoming sync still works. |
| **Remote input** | Medium | Needs `ydotoold` + uinput permissions; not wired in Docker by default. |
| **Tests vs CLI** | Low | Some tests still import `_build_parser` from `main` instead of `jibe.cli` — CI may fail until updated. |
| **Android discovery** | Low | mDNS on isolated Wi-Fi/VLANs; firewall must allow 8776/tcp on the LAN. |

When reporting bugs, include: install path (`install.sh` / pipx / Docker), `systemctl --user status jibe`, relevant log lines, and output of `ss -tlnp | grep 877`.
