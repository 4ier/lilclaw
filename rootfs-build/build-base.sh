#!/bin/bash
# build-base.sh — Build minimal Alpine arm64 base layer
# Requires: qemu-aarch64-static, root (for chroot)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WORK="$SCRIPT_DIR/work-base"
MINIROOTFS="$SCRIPT_DIR/alpine-minirootfs-3.21.3-aarch64.tar.gz"
VERSION="${1:-1.0.0}"
OUTPUT="$SCRIPT_DIR/base-arm64-${VERSION}.tar.gz"

echo "=== Building base layer v${VERSION} ==="

# Download minirootfs if missing
if [ ! -f "$MINIROOTFS" ]; then
    echo "[0] Downloading Alpine minirootfs..."
    wget -O "$MINIROOTFS" "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.3-aarch64.tar.gz"
fi

# Clean
rm -rf "$WORK"
mkdir -p "$WORK"

# Extract
echo "[1/4] Extracting Alpine minirootfs..."
tar xzf "$MINIROOTFS" -C "$WORK"
cp /usr/bin/qemu-aarch64-static "$WORK/usr/bin/"
echo "nameserver 8.8.8.8" > "$WORK/etc/resolv.conf"

# Install Node.js only (no npm — saves space, openclaw layer brings its own)
echo "[2/4] Installing Node.js..."
chroot "$WORK" /usr/bin/qemu-aarch64-static /bin/sh -c '
    apk update
    apk add --no-cache nodejs
    node --version
    # Clean apk cache
    rm -rf /var/cache/apk/*
'

# Create directory structure for upper layers
echo "[3/4] Creating layer mount points..."
chroot "$WORK" /usr/bin/qemu-aarch64-static /bin/sh -c '
    mkdir -p /root/.npm-global/bin /root/.npm-global/lib/node_modules
    mkdir -p /root/lilclaw-ui
    mkdir -p /root/.openclaw

    # Add npm-global to PATH in profile
    echo "export PATH=/root/.npm-global/bin:\$PATH" >> /root/.profile
    echo "export PATH=/root/.npm-global/bin:\$PATH" >> /etc/profile
'

# Remove qemu
rm "$WORK/usr/bin/qemu-aarch64-static"

# Package
echo "[4/4] Packaging base layer..."
cd "$WORK"
tar czf "$OUTPUT" .

echo ""
echo "=== Base layer built ==="
ls -lh "$OUTPUT"
echo "Compressed: $(du -sh "$OUTPUT" | cut -f1)"
echo "Uncompressed: $(du -sh "$WORK" | cut -f1)"

# Cleanup
rm -rf "$WORK"
