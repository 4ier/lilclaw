#!/bin/bash
# build-rootfs-v2.sh — Build minimal arm64 rootfs for LilClaw
# Strategy: install openclaw on x86 host, copy JS files, cross-compile koffi for arm64
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOTFS_DIR="$SCRIPT_DIR/rootfs"
MINIROOTFS="$SCRIPT_DIR/alpine-minirootfs-3.21.3-aarch64.tar.gz"
OUTPUT="$SCRIPT_DIR/rootfs-arm64.tar.gz"
STAGING="$SCRIPT_DIR/staging"

echo "=== LilClaw rootfs builder v2 ==="

# Clean previous
rm -rf "$ROOTFS_DIR" "$STAGING"

###############################################
# PHASE 1: Install OpenClaw on host (x86, fast)
###############################################
echo "[1/7] Installing OpenClaw to staging area..."
mkdir -p "$STAGING"
cd "$STAGING"
npm init -y > /dev/null 2>&1
npm install openclaw 2>&1 | tail -5
OCDIR="$STAGING/node_modules/openclaw"
echo "  OpenClaw installed: $(du -sh "$OCDIR" | cut -f1)"

###############################################
# PHASE 2: Prune dependencies (before copying)
###############################################
echo "[2/7] Pruning dependencies..."
echo "  Before: $(du -sh "$OCDIR/node_modules" | cut -f1)"

NM="$OCDIR/node_modules"

# === MEGA REMOVALS ===
# node-llama-cpp: ~711MB — no local LLM on phone
rm -rf "$NM/@node-llama-cpp" "$NM/node-llama-cpp"

# pdfjs-dist: ~38MB
rm -rf "$NM/pdfjs-dist"

# sharp + libvips: ~17MB
rm -rf "$NM/sharp" "$NM/@img"

# napi-rs prebuilds (wrong arch anyway)
rm -rf "$NM/@napi-rs"

# === UNUSED CHANNEL SDKs ===
rm -rf "$NM/discord.js" "$NM/@discordjs"
rm -rf "$NM/whatsapp-web.js"
rm -rf "$NM/slack-bolt" "$NM/@slack"
rm -rf "$NM/matrix-js-sdk" "$NM/matrix-bot-sdk"
rm -rf "$NM/puppeteer" "$NM/puppeteer-core" "$NM/chromium"

# === BUILD/DEV TOOLS ===
rm -rf "$NM/typescript"
rm -rf "$NM/esbuild" "$NM/@esbuild"
rm -rf "$NM/@rollup" "$NM/rollup"
rm -rf "$NM/webpack" "$NM/webpack-cli"
rm -rf "$NM/eslint" "$NM/@eslint"
rm -rf "$NM/prettier"
rm -rf "$NM/ts-node" "$NM/tsx"
rm -rf "$NM/@types"

# === Remove x86 native binaries (will be replaced by arm64) ===
# koffi x86 prebuilds — we'll rebuild for arm64
find "$NM/koffi" -name "*.node" -path "*/linux-x64/*" -delete 2>/dev/null || true
find "$NM/koffi" -name "*.node" -path "*/darwin-*" -delete 2>/dev/null || true
find "$NM/koffi" -name "*.node" -path "*/win32-*" -delete 2>/dev/null || true

# === JUNK FILES ===
find "$OCDIR" -name "*.map" -delete 2>/dev/null || true
find "$OCDIR" -name "*.d.ts" -delete 2>/dev/null || true
find "$OCDIR" -name "*.d.mts" -delete 2>/dev/null || true
find "$OCDIR" -name "README.md" -delete 2>/dev/null || true
find "$OCDIR" -name "CHANGELOG*" -delete 2>/dev/null || true
find "$OCDIR" -name "LICENSE*" -delete 2>/dev/null || true
find "$OCDIR" -name ".npmignore" -delete 2>/dev/null || true
find "$OCDIR" -name ".eslintrc*" -delete 2>/dev/null || true
find "$OCDIR" -name ".prettierrc*" -delete 2>/dev/null || true
find "$OCDIR" -name "tsconfig*" -delete 2>/dev/null || true
find "$OCDIR" -type d -name "test" -exec rm -rf {} + 2>/dev/null || true
find "$OCDIR" -type d -name "tests" -exec rm -rf {} + 2>/dev/null || true
find "$OCDIR" -type d -name "__tests__" -exec rm -rf {} + 2>/dev/null || true
find "$OCDIR" -type d -name "docs" -exec rm -rf {} + 2>/dev/null || true
find "$OCDIR" -type d -name "example" -exec rm -rf {} + 2>/dev/null || true
find "$OCDIR" -type d -name "examples" -exec rm -rf {} + 2>/dev/null || true

echo "  After: $(du -sh "$OCDIR/node_modules" | cut -f1)"

###############################################
# PHASE 3: Build Alpine arm64 rootfs
###############################################
echo "[3/7] Extracting Alpine minirootfs..."
mkdir -p "$ROOTFS_DIR"
tar xzf "$MINIROOTFS" -C "$ROOTFS_DIR"

# Setup QEMU
cp /usr/bin/qemu-aarch64-static "$ROOTFS_DIR/usr/bin/"
echo "nameserver 8.8.8.8" > "$ROOTFS_DIR/etc/resolv.conf"

###############################################
# PHASE 4: Install Node.js in arm64 rootfs
###############################################
echo "[4/7] Installing Node.js (arm64)..."
chroot "$ROOTFS_DIR" /usr/bin/qemu-aarch64-static /bin/sh -c '
    apk update && apk add --no-cache nodejs
    echo "Node.js $(node --version) installed"
'

###############################################
# PHASE 5: Copy pruned OpenClaw into rootfs
###############################################
echo "[5/7] Copying OpenClaw into rootfs..."
mkdir -p "$ROOTFS_DIR/root/.npm-global/lib/node_modules"
mkdir -p "$ROOTFS_DIR/root/.npm-global/bin"
cp -a "$OCDIR" "$ROOTFS_DIR/root/.npm-global/lib/node_modules/openclaw"

# Create bin symlink
ln -sf ../lib/node_modules/openclaw/bin/openclaw.mjs "$ROOTFS_DIR/root/.npm-global/bin/openclaw"

# Setup npm prefix
chroot "$ROOTFS_DIR" /usr/bin/qemu-aarch64-static /bin/sh -c '
    echo "prefix=/root/.npm-global" > /root/.npmrc
'

echo "  OpenClaw in rootfs: $(du -sh "$ROOTFS_DIR/root/.npm-global" | cut -f1)"

###############################################
# PHASE 6: Cross-compile koffi for arm64
###############################################
echo "[6/7] Building koffi for arm64..."

# Install build tools in arm64 chroot
chroot "$ROOTFS_DIR" /usr/bin/qemu-aarch64-static /bin/sh -c '
    apk add --no-cache npm python3 make g++
    cd /root/.npm-global/lib/node_modules/openclaw/node_modules/koffi
    npm rebuild 2>&1 | tail -10
    echo "koffi native files:"
    find /root/.npm-global/lib/node_modules/openclaw/node_modules/koffi -name "*.node" -ls 2>/dev/null
    # Remove build tools
    apk del npm python3 make g++
    rm -rf /root/.npm /root/.cache /root/.node-gyp /tmp/* /var/cache/apk/*
'

###############################################
# PHASE 7: Package
###############################################
echo "[7/7] Packaging rootfs..."

# Remove QEMU
rm -f "$ROOTFS_DIR/usr/bin/qemu-aarch64-static"

# Don't include /proc /sys /dev contents
cd "$ROOTFS_DIR"
tar czf "$OUTPUT" \
    --exclude='./proc/*' \
    --exclude='./sys/*' \
    --exclude='./dev/*' \
    .

echo ""
echo "=== Build complete ==="
ls -lh "$OUTPUT"
echo "Compressed: $(du -sh "$OUTPUT" | cut -f1)"
du -sh "$ROOTFS_DIR" | sed 's/\t.*/ (uncompressed)/'

# Cleanup staging
rm -rf "$STAGING"
echo "Staging cleaned."
