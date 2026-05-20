#!/usr/bin/env bash
# Remove a Jibe install created by deploy/install.sh (pipx + desktop + systemd).
#
# Usage:
#   bash deploy/uninstall.sh           # keep database and certs
#   bash deploy/uninstall.sh --purge   # also delete all user data

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
CONFIG_HOME="${XDG_CONFIG_HOME:-$HOME/.config}"
PURGE=false

for arg in "$@"; do
  case "$arg" in
    --purge) PURGE=true ;;
    *) echo "Unknown argument: $arg"; exit 1 ;;
  esac
done

echo "Stopping and disabling systemd user service (if present)..."
systemctl --user disable --now jibe 2>/dev/null || true
systemctl --user daemon-reload 2>/dev/null || true
rm -f "${CONFIG_HOME}/systemd/user/jibe.service"

if "$PURGE"; then
  echo "Purging user data..."
  bash "${SCRIPT_DIR}/reset-data.sh" --yes
fi

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

if ! "$PURGE"; then
  echo ""
  echo "User data kept at ${DATA_HOME}/jibe (database, certs, pairing)."
  echo "  Factory reset:  bash deploy/reset-data.sh"
  echo "  Remove + data:  bash deploy/uninstall.sh --purge"
fi

echo ""
echo "Jibe removed from your user account."
