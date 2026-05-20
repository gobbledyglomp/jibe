#!/usr/bin/env bash
# Jibe native install script for Linux desktops.
#
# Installs the daemon via pipx, registers a .desktop entry, installs the
# application icon, and optionally enables the systemd user service so Jibe
# starts automatically on login.
#
# Usage:
#   bash deploy/install.sh           # install and enable autostart
#   bash deploy/install.sh --no-autostart

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AUTOSTART=true
JIBE_BIN="${HOME}/.local/bin/jibe"
DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
CONFIG_HOME="${XDG_CONFIG_HOME:-$HOME/.config}"

for arg in "$@"; do
  case "$arg" in
    --no-autostart) AUTOSTART=false ;;
    *) echo "Unknown argument: $arg"; exit 1 ;;
  esac
done

_wait_for_dashboard() {
  local deadline=$((SECONDS + 25))
  while (( SECONDS < deadline )); do
    if curl -sf -o /dev/null --connect-timeout 1 http://127.0.0.1:8777/ 2>/dev/null; then
      return 0
    fi
    sleep 0.25
  done
  return 1
}

_fetch_initial_password() {
  local deadline=$((SECONDS + 20))
  local line=""
  while (( SECONDS < deadline )); do
    line=$(journalctl --user -u jibe -b --no-pager -o cat 2>/dev/null \
      | sed -n 's/.*Password (save now): //p' | tail -1)
    if [[ -n "$line" ]]; then
      printf '%s' "$line"
      return 0
    fi
    sleep 0.25
  done
  return 1
}

_session_is_wayland() {
  [[ -n "${WAYLAND_DISPLAY:-}" || "${XDG_SESSION_TYPE:-}" == "wayland" ]]
}

_enable_ydotool_user_service() {
  # Arch and some distros ship /usr/lib/systemd/user/ydotool.service (not ydotoold).
  if [[ -f /usr/lib/systemd/user/ydotool.service ]]; then
    systemctl --user daemon-reload
    systemctl --user enable ydotool 2>/dev/null || true
    systemctl --user start ydotool 2>/dev/null || true
    return
  fi
  local systemd_dir="${CONFIG_HOME}/systemd/user"
  mkdir -p "$systemd_dir"
  cp "${REPO_ROOT}/deploy/ydotoold.service" "${systemd_dir}/ydotoold.service"
  systemctl --user daemon-reload
  systemctl --user enable ydotoold 2>/dev/null || true
  systemctl --user start ydotoold 2>/dev/null || true
}

_ydotool_systemd_unit_name() {
  if [[ -f /usr/lib/systemd/user/ydotool.service ]]; then
    echo "ydotool"
  else
    echo "ydotoold"
  fi
}

_setup_presentation_remote() {
  if _session_is_wayland; then
    if ! command -v ydotool &>/dev/null; then
      echo ""
      echo "Wayland detected — install ydotool for presentation remote:"
      echo "  sudo pacman -S ydotool    # Arch"
      return
    fi
    _enable_ydotool_user_service
  elif command -v ydotool &>/dev/null; then
    # Install from SSH/tty often has no WAYLAND_DISPLAY — still register the daemon unit.
    _enable_ydotool_user_service
    echo ""
    echo "ydotool user service registered (install was not in a Wayland session)."
  elif ! command -v xdotool &>/dev/null; then
    echo ""
    echo "Optional: install xdotool for presentation remote on X11:"
    echo "  sudo pacman -S xdotool"
    return
  fi

  if [[ ! -e /dev/uinput ]]; then
    echo ""
    echo "Presentation remote: load the uinput module once:"
    echo "  sudo modprobe uinput"
  fi

  if getent group input &>/dev/null && ! id -nG "$USER" | grep -qw input; then
    local ydotool_unit
    ydotool_unit="$(_ydotool_systemd_unit_name)"
    echo ""
    echo "Presentation remote on Wayland requires the 'input' group:"
    echo "  sudo usermod -aG input $USER"
    echo "  Log out and back in, then: systemctl --user restart ${ydotool_unit} jibe"
  fi
}

# ---------------------------------------------------------------------------
# 1. Install daemon via pipx
# ---------------------------------------------------------------------------
if ! command -v pipx &>/dev/null; then
  echo "pipx not found. Install it with your package manager:"
  echo "  Arch:         sudo pacman -S python-pipx"
  echo "  Debian/Ubuntu: sudo apt install pipx"
  exit 1
fi

echo "Installing jibe via pipx (from ${REPO_ROOT}/daemon)..."
pipx install --force "${REPO_ROOT}/daemon"
pipx ensurepath 2>/dev/null || true
export PATH="${HOME}/.local/bin:${PATH}"

if [[ ! -x "$JIBE_BIN" ]]; then
  echo "ERROR: jibe binary not found at $JIBE_BIN"
  exit 1
fi

echo "jibe installed at $JIBE_BIN"

# ---------------------------------------------------------------------------
# 2. Presentation remote (ydotoold / xdotool)
# ---------------------------------------------------------------------------
_setup_presentation_remote

# ---------------------------------------------------------------------------
# 3. Install icon into XDG icon theme
# ---------------------------------------------------------------------------
ICON_DIR="${DATA_HOME}/icons/hicolor/scalable/apps"
mkdir -p "$ICON_DIR"
cp "$REPO_ROOT/branding/logos/jibe-icon-filled.svg" "$ICON_DIR/jibe.svg"
if command -v gtk-update-icon-cache &>/dev/null; then
  gtk-update-icon-cache -f "${DATA_HOME}/icons/hicolor" 2>/dev/null || true
fi
echo "Icon installed to $ICON_DIR/jibe.svg"

# ---------------------------------------------------------------------------
# 4. Install .desktop entry (absolute path — launcher PATH may omit pipx)
# ---------------------------------------------------------------------------
APPS_DIR="${DATA_HOME}/applications"
mkdir -p "$APPS_DIR"
sed "s|^Exec=jibe$|Exec=${JIBE_BIN}|" "$REPO_ROOT/deploy/jibe.desktop" > "$APPS_DIR/jibe.desktop"
if command -v update-desktop-database &>/dev/null; then
  update-desktop-database "$APPS_DIR" 2>/dev/null || true
fi
echo "Desktop entry installed to $APPS_DIR/jibe.desktop"

# ---------------------------------------------------------------------------
# 5. Optionally enable systemd user service (autostart on login)
# ---------------------------------------------------------------------------
if "$AUTOSTART"; then
  SYSTEMD_DIR="${CONFIG_HOME}/systemd/user"
  mkdir -p "$SYSTEMD_DIR"
  cp "$REPO_ROOT/deploy/jibe.service" "$SYSTEMD_DIR/jibe.service"
  systemctl --user daemon-reload
  echo "Starting Jibe..."
  if systemctl --user is-enabled jibe &>/dev/null; then
    systemctl --user restart jibe
  else
    systemctl --user enable --now jibe
  fi
  _wait_for_dashboard || true
  echo "Systemd service enabled — Jibe runs in the background and starts on login."
else
  echo "Autostart skipped. Run: $JIBE_BIN"
fi

echo ""
if "$AUTOSTART"; then
  echo "Dashboard: http://127.0.0.1:8777/"
  if password=$(_fetch_initial_password); then
    echo ""
    echo "  Username: admin"
    echo "  Password: ${password}"
    echo ""
    echo "Save this password now — it is only shown once (also in your user journal)."
  else
    echo "  Use your existing admin password (no new user was created)."
  fi
  echo ""
  echo "  Status:  systemctl --user status jibe"
  echo "  Logs:    journalctl --user -u jibe -f"
  echo ""
  echo "Do not run \`jibe\` in a terminal while the service is active (port conflict)."
  echo "Open a new terminal or run: export PATH=\"\$HOME/.local/bin:\$PATH\""
else
  echo "Done. Run: $JIBE_BIN"
  echo "Add to PATH permanently: pipx ensurepath  (then open a new terminal)"
fi
