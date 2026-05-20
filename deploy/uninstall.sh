#!/usr/bin/env bash
# Remove a Jibe install created by deploy/install.sh (pipx + desktop + systemd).
#
# Usage:
#   bash deploy/uninstall.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
CONFIG_HOME="${XDG_CONFIG_HOME:-$HOME/.config}"

echo "Stopping and disabling systemd user service (if present)..."
systemctl --user disable --now jibe 2>/dev/null || true
systemctl --user daemon-reload 2>/dev/null || true
rm -f "${CONFIG_HOME}/systemd/user/jibe.service"

echo "Removing pipx package..."
if command -v pipx &>/dev/null; then
  pipx uninstall jibe 2>/dev/null || true
else
  echo "  pipx not found — skip pipx uninstall"
fi

echo "Removing desktop entry and icon..."
rm -f "${DATA_HOME}/applications/jibe.desktop"
rm -f "${DATA_HOME}/icons/hicolor/scalable/apps/jibe.svg"
if command -v update-desktop-database &>/dev/null; then
  update-desktop-database "${DATA_HOME}/applications" 2>/dev/null || true
fi
if command -v gtk-update-icon-cache &>/dev/null; then
  gtk-update-icon-cache -f "${DATA_HOME}/icons/hicolor" 2>/dev/null || true
fi

echo ""
echo "Jibe removed from your user account."
echo "User data (database, TLS certs, config) is kept at:"
echo "  ${DATA_HOME}/jibe"
echo "Delete that directory manually if you want a full reset."
