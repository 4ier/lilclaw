#!/bin/bash
# Sync bundled rootfs layers from GitHub releases to local assets.
# Run before local builds to ensure assets match manifest.
set -euo pipefail

ASSETS_DIR="app/src/main/assets/rootfs"
RELEASE_TAG="layers-v3"
REPO="4ier/lilclaw"

echo "Syncing layers from $RELEASE_TAG..."
rm -rf "$ASSETS_DIR"
mkdir -p "$ASSETS_DIR"

gh release download "$RELEASE_TAG" --pattern "*.tar.gz" --dir "$ASSETS_DIR" --repo "$REPO"

echo "=== Bundled assets ==="
ls -lh "$ASSETS_DIR"
echo "Done. Assets ready for local build."
