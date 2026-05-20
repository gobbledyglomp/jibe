#!/usr/bin/env bash
# Factory-reset Jibe: database, pairing, TLS certs, transfer cache.
#
# Stops the user service if present, wipes local data, then restarts the
# service when it was enabled. On next start you get a fresh admin password.
#
# Usage:
#   bash deploy/reset-data.sh        # interactive confirm
#   bash deploy/reset-data.sh --yes  # no prompt (scripts/CI)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ASSUME_YES=false

for arg in "$@"; do
  case "$arg" in
    --yes|-y) ASSUME_YES=true ;;
    *) echo "Unknown argument: $arg"; exit 1 ;;
  esac
done

DATA_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/jibe"
CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/jibe"

echo "Stopping Jibe if it is running..."
systemctl --user stop jibe 2>/dev/null || true

if command -v jibe &>/dev/null; then
  if "$ASSUME_YES"; then
    jibe --reset-data --yes
  else
    jibe --reset-data
  fi
else
  if [[ ! -d "$DATA_DIR" && ! -d "$CACHE_DIR" ]]; then
    echo "No Jibe user data found — nothing to reset."
  else
    if ! "$ASSUME_YES"; then
      echo "jibe not in PATH; will delete:"
      echo "  $DATA_DIR"
      echo "  $CACHE_DIR"
      read -rp "Continue? [y/N] " ans
      if [[ "${ans,,}" != "y" && "${ans,,}" != "yes" ]]; then
        echo "Reset cancelled."
        exit 0
      fi
    fi
    rm -rf "$DATA_DIR" "$CACHE_DIR"
    echo "Removed user data."
  fi
fi

if systemctl --user is-enabled jibe &>/dev/null; then
  echo "Restarting systemd user service..."
  systemctl --user start jibe
  echo ""
  echo "Fresh install state. Retrieve the new admin password:"
  echo "  journalctl --user -u jibe -b | grep -A4 'Password (save now)'"
  echo "Dashboard: http://127.0.0.1:8777/"
else
  echo ""
  echo "Start Jibe when ready (jibe, or enable the systemd service)."
fi
