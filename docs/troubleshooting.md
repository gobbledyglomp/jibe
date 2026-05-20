# Troubleshooting

Common install/runtime issues and known limitations as of **0.9.0-pre**.

## Install and uninstall

### `install.sh` — remove Jibe completely

From the repo you cloned:

```bash
bash deploy/uninstall.sh
```

This stops the user systemd unit, runs `pipx uninstall jibe`, and removes the `.desktop` entry and launcher icon. **User data is kept by default** (`~/.local/share/jibe`).

| Goal | Command |
|---|---|
| Re-test from scratch (keep app installed) | `bash deploy/reset-data.sh` or `jibe --reset-data` |
| Uninstall app but keep pairing for later | `bash deploy/uninstall.sh` |
| Remove everything | `bash deploy/uninstall.sh --purge` |

### Factory reset (testing / fresh password)

Stops Jibe, deletes:

- `~/.local/share/jibe` — SQLite DB, TLS certs, dashboard recovery key
- `~/.cache/jibe` — incomplete file transfers

```bash
bash deploy/reset-data.sh        # asks for confirmation
bash deploy/reset-data.sh --yes  # no prompt
```

Without the repo: `systemctl --user stop jibe && jibe --reset-data --yes`

On next start you get a **new** admin password (check logs as on first install). **Re-pair Android** — old device tokens are invalid. TLS cert changes — you may need to clear the app’s trust or reinstall the APK if pinning fails.

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

It is shown **once**. If you missed it, run `bash deploy/reset-data.sh` (or `jibe --reset-data`) and start Jibe again.

---

## Android: "App not installed"

Usually a **signature mismatch** (debug APK vs release) or an older install with a higher `versionCode`.

```bash
adb uninstall com.jibe.app
adb install -r app-release.apk
```

Use `adb install -v` for the exact failure reason.

---

## Presentation remote not working (ydotool exit 2)

On **Wayland** (default on Arch/KDE/GNOME), Jibe uses `ydotool`, which needs the `ydotoold` daemon and access to `/dev/uinput`.

**Symptoms:** log lines like `ydotool exited 2` or `ydotoold exited immediately with code 2`.

**Fix (Arch):**

```bash
sudo pacman -S ydotool
sudo usermod -aG input $USER
# log out and back in (or reboot)
systemctl --user enable --now ydotool    # unit name from the Arch package
systemctl --user restart jibe
```

On Arch the systemd unit is **`ydotool.service`**, not `ydotoold`. If you see `Unit ydotoold.service does not exist`, use `ydotool` above, re-run `bash deploy/install.sh`, or install Jibe’s unit manually:

```bash
mkdir -p ~/.config/systemd/user
cp deploy/ydotoold.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now ydotoold
```

If `/dev/uinput` is missing: `sudo modprobe uinput`.

`install.sh` enables the distro `ydotool` unit when present, otherwise copies `deploy/ydotoold.service`. It also registers the service when `ydotool` is installed but the installer ran without a Wayland session (SSH/tty).

On **X11**, install `xdotool` instead — no `ydotoold` required.

---

## `jibe: command not found` after install

pipx installs to `~/.local/bin`. Either open a **new terminal**, run `pipx ensurepath` and restart your shell, or:

```bash
export PATH="$HOME/.local/bin:$PATH"
```

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
| **Remote input** | Medium | Wayland: `input` group + `ydotoold` user service; X11: `xdotool`. See above. |
| **Tests vs CLI** | Low | Some tests still import `_build_parser` from `main` instead of `jibe.cli` — CI may fail until updated. |
| **Android discovery** | Low | mDNS on isolated Wi-Fi/VLANs; firewall must allow 8776/tcp on the LAN. |

When reporting bugs, include: install path (`install.sh` / pipx / Docker), `systemctl --user status jibe`, relevant log lines, and output of `ss -tlnp | grep 877`.
