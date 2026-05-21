#!/usr/bin/env bash
# Build a signed release APK and publish it to GitHub Releases.
#
# Prerequisites:
#   - JDK 17+ (JAVA_HOME or /usr/lib/jvm/java-21-openjdk)
#   - android/local.properties with signing.* (see android/README.md)
#   - GitHub CLI: gh auth login
#
# Usage:
#   bash android/scripts/publish-release.sh
#   bash android/scripts/publish-release.sh --build-only

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ANDROID_DIR="${REPO_ROOT}/android"
VERSION_NAME="0.9.0-pre"
TAG="v${VERSION_NAME}"
ASSET_NAME="jibe-${VERSION_NAME}.apk"
APK_OUT="${ANDROID_DIR}/app/build/outputs/apk/release/app-release.apk"
DIST_DIR="${ANDROID_DIR}/dist"
BUILD_ONLY=false

for arg in "$@"; do
  case "$arg" in
    --build-only) BUILD_ONLY=true ;;
    *) echo "Unknown argument: $arg"; exit 1 ;;
  esac
done

if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/default; do
    if [[ -x "${candidate}/bin/java" ]]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}${PATH}"

if ! command -v java &>/dev/null; then
  echo "ERROR: Java not found. Install JDK 17+ and set JAVA_HOME."
  exit 1
fi

if [[ ! -f "${ANDROID_DIR}/local.properties" ]]; then
  echo "ERROR: ${ANDROID_DIR}/local.properties missing (signing + SDK paths)."
  exit 1
fi

echo "Building release APK..."
(cd "$ANDROID_DIR" && ./gradlew assembleRelease --no-daemon)

if [[ ! -f "$APK_OUT" ]]; then
  echo "ERROR: APK not found at $APK_OUT"
  exit 1
fi

mkdir -p "$DIST_DIR"
cp "$APK_OUT" "${DIST_DIR}/${ASSET_NAME}"
(
  cd "$DIST_DIR"
  sha256sum "$ASSET_NAME" > SHA256SUMS
)

echo ""
echo "Built: ${DIST_DIR}/${ASSET_NAME}"
echo "Checksums: ${DIST_DIR}/SHA256SUMS"
cat "${DIST_DIR}/SHA256SUMS"

if "$BUILD_ONLY"; then
  exit 0
fi

if ! command -v gh &>/dev/null; then
  echo ""
  echo "Install GitHub CLI and authenticate, then re-run without --build-only:"
  echo "  sudo pacman -S github-cli   # Arch"
  echo "  gh auth login"
  echo "  bash android/scripts/publish-release.sh"
  exit 0
fi

if ! gh auth status &>/dev/null; then
  echo "ERROR: gh is not authenticated. Run: gh auth login"
  exit 1
fi

NOTES_FILE="$(mktemp)"
cat >"$NOTES_FILE" <<EOF
Android companion for [Jibe](https://github.com/gobbledyglomp/jibe) **${VERSION_NAME}**.

- Same Wi-Fi as the Linux host running the Jibe daemon
- Pair with the dashboard PIN (daemon 0.9.0-pre)
- Sideload \`${ASSET_NAME}\` — uninstall any older build if install fails (signature mismatch)

**Checksum (SHA-256):** see \`SHA256SUMS\` in this release.
EOF

echo ""
echo "Publishing GitHub release ${TAG}..."
gh release view "$TAG" &>/dev/null && \
  gh release upload "$TAG" "${DIST_DIR}/${ASSET_NAME}" "${DIST_DIR}/SHA256SUMS" --clobber || \
  gh release create "$TAG" \
    "${DIST_DIR}/${ASSET_NAME}" \
    "${DIST_DIR}/SHA256SUMS" \
    --repo gobbledyglomp/jibe \
    --title "${VERSION_NAME}" \
    --notes-file "$NOTES_FILE"

rm -f "$NOTES_FILE"

echo ""
echo "Done: https://github.com/gobbledyglomp/jibe/releases/tag/${TAG}"
