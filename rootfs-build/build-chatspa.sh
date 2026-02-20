#!/bin/bash
# build-chatspa.sh — Build Chat SPA layer
# No qemu needed — pure JS build on host
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WEB_DIR="$REPO_ROOT/web"
VERSION="${1:-$(node -e "console.log(require('$REPO_ROOT/package.json').version || '0.1.0')")}"
OUTPUT="$SCRIPT_DIR/chatspa-${VERSION}.tar.gz"
STAGING="$SCRIPT_DIR/work-chatspa"

echo "=== Building Chat SPA layer v${VERSION} ==="

# Build SPA
echo "[1/3] Building Chat SPA..."
cd "$WEB_DIR"
pnpm install --frozen-lockfile 2>&1 | tail -3
pnpm build 2>&1 | tail -3

# Stage files with correct path prefix
echo "[2/3] Staging..."
rm -rf "$STAGING"
mkdir -p "$STAGING/root/lilclaw-ui"
cp -r "$WEB_DIR/dist" "$STAGING/root/lilclaw-ui/dist"
cp "$WEB_DIR/serve-ui.cjs" "$STAGING/root/lilclaw-ui/serve-ui.cjs"

# Package
echo "[3/3] Packaging..."
cd "$STAGING"
tar czf "$OUTPUT" ./root/lilclaw-ui

echo ""
echo "=== Chat SPA layer built ==="
ls -lh "$OUTPUT"
echo "Contents:"
tar tzf "$OUTPUT" | head -20

# Cleanup
rm -rf "$STAGING"
