#!/bin/bash
# build-config.sh — Build config layer (SOUL.md + TOOLS.md)
# No root, no qemu — just tar up workspace files
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
VERSION="${1:-0.2.0}"
OUTPUT="$SCRIPT_DIR/config-${VERSION}.tar.gz"
STAGING="$SCRIPT_DIR/work-config"

echo "=== Building config layer v${VERSION} ==="

rm -rf "$STAGING"
mkdir -p "$STAGING/root/.openclaw/workspace-dev"

# Copy workspace files from assets/
for f in SOUL.md TOOLS.md; do
    if [ -f "$REPO_ROOT/assets/$f" ]; then
        cp "$REPO_ROOT/assets/$f" "$STAGING/root/.openclaw/workspace-dev/$f"
        echo "  + $f"
    else
        echo "  WARNING: assets/$f not found, skipping"
    fi
done

# Package
cd "$STAGING"
tar czf "$OUTPUT" ./root

echo ""
echo "=== Config layer built ==="
ls -lh "$OUTPUT"
echo "Contents:"
tar tzf "$OUTPUT"

rm -rf "$STAGING"
