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

for arg in "$@"; do
  case "$arg" in
    --no-autostart) AUTOSTART=false ;;
    *) echo "Unknown argument: $arg"; exit 1 ;;
  esac
done

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

# ---------------------------------------------------------------------------
# 2. Install icon into XDG icon theme
# ---------------------------------------------------------------------------
ICON_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor/scalable/apps"
mkdir -p "$ICON_DIR"
cp "$REPO_ROOT/branding/logos/jibe-icon-filled.svg" "$ICON_DIR/jibe.svg"
if command -v gtk-update-icon-cache &>/dev/null; then
  gtk-update-icon-cache -f "${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor" 2>/dev/null || true
fi
echo "Icon installed to $ICON_DIR/jibe.svg"

# ---------------------------------------------------------------------------
# 3. Install .desktop entry
# ---------------------------------------------------------------------------
APPS_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
mkdir -p "$APPS_DIR"
cp "$REPO_ROOT/deploy/jibe.desktop" "$APPS_DIR/jibe.desktop"
if command -v update-desktop-database &>/dev/null; then
  update-desktop-database "$APPS_DIR" 2>/dev/null || true
fi
echo "Desktop entry installed to $APPS_DIR/jibe.desktop"

# ---------------------------------------------------------------------------
# 4. Optionally enable systemd user service (autostart on login)
# ---------------------------------------------------------------------------
if "$AUTOSTART"; then
  SYSTEMD_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
  mkdir -p "$SYSTEMD_DIR"
  cp "$REPO_ROOT/deploy/jibe.service" "$SYSTEMD_DIR/jibe.service"
  systemctl --user daemon-reload
  if systemctl --user is-enabled jibe &>/dev/null; then
    systemctl --user restart jibe
  else
    systemctl --user enable --now jibe
  fi
  echo "Systemd service enabled — Jibe runs in the background and starts on login."
else
  echo "Autostart skipped. Run 'jibe' once from your application launcher or terminal."
fi

echo ""
if "$AUTOSTART"; then
  cat <<'EOF'
Done. Jibe is already running as a background service.

  Dashboard:  http://127.0.0.1:8777/
  Status:     systemctl --user status jibe
  Logs:       journalctl --user -u jibe -f
  Password:   journalctl --user -u jibe -b | grep -A4 'Password (save now)'

Do not run `jibe` in a terminal while the service is active (port 8776 conflict).
Use the application menu launcher, or stop the service first: systemctl --user stop jibe

To remove Jibe: bash deploy/uninstall.sh
To factory-reset (fresh DB, new password): bash deploy/reset-data.sh
EOF
else
  echo "Done. Launch Jibe from your application menu or run 'jibe' in a terminal."
  echo "To remove Jibe: bash deploy/uninstall.sh"
  echo "To factory-reset data: bash deploy/reset-data.sh"
fi
