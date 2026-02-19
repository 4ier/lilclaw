#!/bin/bash
# build-rootfs.sh — Build minimal arm64 rootfs for LilClaw
# Run as root (needs chroot)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOTFS_DIR="$SCRIPT_DIR/rootfs"
MINIROOTFS="$SCRIPT_DIR/alpine-minirootfs-3.21.3-aarch64.tar.gz"
OUTPUT="$SCRIPT_DIR/rootfs-arm64.tar.gz"

echo "=== LilClaw rootfs builder ==="

# Clean previous build
if [ -d "$ROOTFS_DIR" ]; then
    echo "[1/8] Cleaning previous rootfs..."
    rm -rf "$ROOTFS_DIR"
fi

# Extract minirootfs
echo "[2/8] Extracting Alpine minirootfs..."
mkdir -p "$ROOTFS_DIR"
tar xzf "$MINIROOTFS" -C "$ROOTFS_DIR"

# Copy QEMU binary for arm64 emulation
echo "[3/8] Setting up QEMU..."
cp /usr/bin/qemu-aarch64-static "$ROOTFS_DIR/usr/bin/"

# Setup DNS inside chroot
echo "nameserver 8.8.8.8" > "$ROOTFS_DIR/etc/resolv.conf"

# Mount necessary filesystems
mount --bind /proc "$ROOTFS_DIR/proc" || true
mount --bind /sys "$ROOTFS_DIR/sys" || true
mount --bind /dev "$ROOTFS_DIR/dev" || true

cleanup() {
    echo "Cleaning up mounts..."
    umount "$ROOTFS_DIR/proc" 2>/dev/null || true
    umount "$ROOTFS_DIR/sys" 2>/dev/null || true
    umount "$ROOTFS_DIR/dev" 2>/dev/null || true
}
trap cleanup EXIT

# Install Node.js inside chroot
echo "[4/8] Installing Node.js..."
chroot "$ROOTFS_DIR" /usr/bin/qemu-aarch64-static /bin/sh -c '
    apk update
    apk add --no-cache nodejs npm
    node --version
    npm --version
'

# Install build dependencies for native modules
echo "[5/8] Installing build tools for native modules..."
chroot "$ROOTFS_DIR" /usr/bin/qemu-aarch64-static /bin/sh -c '
    apk add --no-cache python3 make g++ linux-headers git
'

# Install OpenClaw
echo "[6/8] Installing OpenClaw..."
chroot "$ROOTFS_DIR" /usr/bin/qemu-aarch64-static /bin/sh -c '
    mkdir -p /root/.npm-global
    npm config set prefix /root/.npm-global
    export PATH=/root/.npm-global/bin:$PATH
    npm install -g openclaw 2>&1 | tail -30
    
    # Verify koffi native module
    OCDIR=/root/.npm-global/lib/node_modules/openclaw
    if [ -d "$OCDIR/node_modules/koffi" ]; then
        echo "koffi found, checking native build..."
        ls $OCDIR/node_modules/koffi/build/ 2>/dev/null || echo "koffi build dir missing, rebuilding..."
        cd $OCDIR/node_modules/koffi && npm rebuild 2>&1 | tail -5
    fi
    
    # Verify
    echo "OpenClaw installed at: $(ls /root/.npm-global/bin/openclaw 2>/dev/null && echo OK || echo MISSING)"
'

# Aggressive pruning
echo "[7/8] Pruning dependencies..."
chroot "$ROOTFS_DIR" /usr/bin/qemu-aarch64-static /bin/sh -c '
    OCDIR=/root/.npm-global/lib/node_modules/openclaw/node_modules
    
    echo "Before pruning: $(du -sh /root/.npm-global | cut -f1)"
    
    # === MEGA REMOVALS (>10MB each) ===
    
    # node-llama-cpp: ~711MB — no local LLM on phone
    rm -rf $OCDIR/@node-llama-cpp $OCDIR/node-llama-cpp
    
    # pdfjs-dist: ~38MB — no PDF processing needed
    rm -rf $OCDIR/pdfjs-dist
    
    # sharp + libvips: ~17MB — Android has native image APIs
    rm -rf $OCDIR/sharp $OCDIR/@img
    
    # napi-rs prebuilds: ~32MB
    rm -rf $OCDIR/@napi-rs
    
    # === UNUSED CHANNEL SDKs ===
    # LilClaw connects locally, no external channel SDKs needed
    rm -rf $OCDIR/discord.js $OCDIR/@discordjs
    rm -rf $OCDIR/whatsapp-web.js
    rm -rf $OCDIR/slack-bolt $OCDIR/@slack
    rm -rf $OCDIR/telegraf  # gateway handles telegram, not the app
    rm -rf $OCDIR/matrix-js-sdk $OCDIR/matrix-bot-sdk
    rm -rf $OCDIR/@anthropic-ai
    rm -rf $OCDIR/pupeeteer $OCDIR/puppeteer $OCDIR/puppeteer-core $OCDIR/chromium
    
    # === BUILD/DEV TOOLS (runtime unnecessary) ===
    rm -rf $OCDIR/typescript
    rm -rf $OCDIR/esbuild $OCDIR/@esbuild
    rm -rf $OCDIR/@rollup $OCDIR/rollup
    rm -rf $OCDIR/webpack $OCDIR/webpack-cli
    rm -rf $OCDIR/eslint $OCDIR/@eslint
    rm -rf $OCDIR/prettier
    rm -rf $OCDIR/ts-node $OCDIR/tsx
    rm -rf $OCDIR/@types
    
    # === JUNK FILES ===
    find /root/.npm-global -name "*.map" -delete 2>/dev/null
    find /root/.npm-global -name "*.d.ts" -delete 2>/dev/null
    find /root/.npm-global -name "*.d.mts" -delete 2>/dev/null
    find /root/.npm-global -name "README.md" -delete 2>/dev/null
    find /root/.npm-global -name "CHANGELOG*" -delete 2>/dev/null
    find /root/.npm-global -name "LICENSE*" -delete 2>/dev/null
    find /root/.npm-global -name "*.md" ! -name "package.json" -delete 2>/dev/null
    find /root/.npm-global -name ".npmignore" -delete 2>/dev/null
    find /root/.npm-global -name ".eslintrc*" -delete 2>/dev/null
    find /root/.npm-global -name ".prettierrc*" -delete 2>/dev/null
    find /root/.npm-global -name "tsconfig*" -delete 2>/dev/null
    find /root/.npm-global -type d -name "test" -exec rm -rf {} + 2>/dev/null
    find /root/.npm-global -type d -name "tests" -exec rm -rf {} + 2>/dev/null
    find /root/.npm-global -type d -name "__tests__" -exec rm -rf {} + 2>/dev/null
    find /root/.npm-global -type d -name "docs" -exec rm -rf {} + 2>/dev/null
    find /root/.npm-global -type d -name "example" -exec rm -rf {} + 2>/dev/null
    find /root/.npm-global -type d -name "examples" -exec rm -rf {} + 2>/dev/null
    
    # Remove build tools installed for compilation
    apk del python3 make g++ linux-headers git
    
    # Clean all caches
    rm -rf /root/.npm /tmp/* /var/cache/apk/*
    rm -rf /root/.node-gyp /root/.cache
    
    echo "After pruning: $(du -sh /root/.npm-global | cut -f1)"
'

# Remove QEMU binary (not needed on device)
rm "$ROOTFS_DIR/usr/bin/qemu-aarch64-static"

# Package
echo "[8/8] Packaging rootfs..."
cd "$ROOTFS_DIR"
tar czf "$OUTPUT" .
ls -lh "$OUTPUT"

echo ""
echo "=== Build complete ==="
echo "Rootfs: $OUTPUT"
echo "Size: $(du -sh "$OUTPUT" | cut -f1)"
du -sh "$ROOTFS_DIR" | sed 's/\t.*/ (uncompressed)/'
