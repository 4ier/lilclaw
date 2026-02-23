#!/bin/bash
# build-rootfs-v3.sh — Build minimal arm64 rootfs for LilClaw
# Strategy: npm install -g on host (nested deps), prune, copy to arm64 rootfs, cross-compile koffi
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOTFS_DIR="$SCRIPT_DIR/rootfs"
MINIROOTFS="$SCRIPT_DIR/alpine-minirootfs-3.21.3-aarch64.tar.gz"
OUTPUT="$SCRIPT_DIR/rootfs-arm64.tar.gz"
STAGING="$SCRIPT_DIR/staging-global"

echo "=== LilClaw rootfs builder v3 ==="

# Clean previous
rm -rf "$ROOTFS_DIR" "$STAGING"

###############################################
# PHASE 1: Install OpenClaw globally to staging prefix
###############################################
echo "[1/7] Installing OpenClaw to staging prefix..."
mkdir -p "$STAGING"
# npm install -g with --prefix puts everything in lib/node_modules/openclaw/node_modules/
npm install -g openclaw --prefix="$STAGING" 2>&1 | tail -5
OCDIR="$STAGING/lib/node_modules/openclaw"
echo "  OpenClaw total: $(du -sh "$OCDIR" | cut -f1)"
echo "  node_modules: $(du -sh "$OCDIR/node_modules" | cut -f1)"

###############################################
# PHASE 2: Prune dependencies
###############################################
echo "[2/7] Pruning dependencies..."
NM="$OCDIR/node_modules"
echo "  Before: $(du -sh "$NM" | cut -f1)"

# === MEGA REMOVALS (>10MB each) ===
rm -rf "$NM/@node-llama-cpp" "$NM/node-llama-cpp" 2>/dev/null || true
rm -rf "$NM/pdfjs-dist" 2>/dev/null || true
rm -rf "$NM/sharp" "$NM/@img" 2>/dev/null || true
rm -rf "$NM/@napi-rs" 2>/dev/null || true

# === UNUSED CHANNEL SDKs ===
rm -rf "$NM/discord.js" "$NM/@discordjs" 2>/dev/null || true
rm -rf "$NM/whatsapp-web.js" 2>/dev/null || true
rm -rf "$NM/slack-bolt" "$NM/@slack" 2>/dev/null || true
rm -rf "$NM/matrix-js-sdk" "$NM/matrix-bot-sdk" 2>/dev/null || true
rm -rf "$NM/puppeteer" "$NM/puppeteer-core" "$NM/chromium" 2>/dev/null || true

# === BUILD/DEV TOOLS ===
rm -rf "$NM/typescript" 2>/dev/null || true
rm -rf "$NM/esbuild" "$NM/@esbuild" 2>/dev/null || true
rm -rf "$NM/@rollup" "$NM/rollup" 2>/dev/null || true
rm -rf "$NM/webpack" "$NM/webpack-cli" 2>/dev/null || true
rm -rf "$NM/eslint" "$NM/@eslint" 2>/dev/null || true
rm -rf "$NM/prettier" 2>/dev/null || true
rm -rf "$NM/ts-node" "$NM/tsx" 2>/dev/null || true
rm -rf "$NM/@types" 2>/dev/null || true

# === Remove wrong-arch native binaries ===
find "$NM" -name "*.node" -path "*/linux-x64/*" -delete 2>/dev/null || true
find "$NM" -name "*.node" -path "*/darwin-*" -delete 2>/dev/null || true
find "$NM" -name "*.node" -path "*/win32-*" -delete 2>/dev/null || true
# Also clean prebuilds directories for wrong arch
find "$NM" -type d -name "linux-x64" -exec rm -rf {} + 2>/dev/null || true
find "$NM" -type d -name "darwin-x64" -exec rm -rf {} + 2>/dev/null || true
find "$NM" -type d -name "darwin-arm64" -exec rm -rf {} + 2>/dev/null || true
find "$NM" -type d -name "win32-x64" -exec rm -rf {} + 2>/dev/null || true
find "$NM" -type d -name "win32-arm64" -exec rm -rf {} + 2>/dev/null || true

# === JUNK FILES ===
find "$OCDIR" -name "*.map" -delete 2>/dev/null || true
find "$OCDIR" -name "*.d.ts" -delete 2>/dev/null || true
find "$OCDIR" -name "*.d.mts" -delete 2>/dev/null || true
find "$OCDIR" \( -name "README.md" -o -name "CHANGELOG*" -o -name "LICENSE*" \
    -o -name ".npmignore" -o -name ".eslintrc*" -o -name ".prettierrc*" \
    -o -name "tsconfig*" \) -delete 2>/dev/null || true
find "$OCDIR" -type d \( -name "test" -o -name "tests" -o -name "__tests__" \
    -o -name "docs" -o -name "example" -o -name "examples" \) \
    -exec rm -rf {} + 2>/dev/null || true

echo "  After: $(du -sh "$NM" | cut -f1)"
echo "  OpenClaw total after prune: $(du -sh "$OCDIR" | cut -f1)"

###############################################
# PHASE 3: Build Alpine arm64 rootfs
###############################################
echo "[3/7] Extracting Alpine minirootfs..."
mkdir -p "$ROOTFS_DIR"
tar xzf "$MINIROOTFS" -C "$ROOTFS_DIR"
cp /usr/bin/qemu-aarch64-static "$ROOTFS_DIR/usr/bin/"
echo "nameserver 8.8.8.8" > "$ROOTFS_DIR/etc/resolv.conf"

###############################################
# PHASE 4: Install Node.js (no npm — saves ~10MB)
###############################################
echo "[4/7] Installing Node.js (arm64)..."
chroot "$ROOTFS_DIR" /usr/bin/qemu-aarch64-static /bin/sh -c '
    apk update && apk add --no-cache nodejs
    node --version
'

###############################################
# PHASE 5: Copy pruned OpenClaw into rootfs
###############################################
echo "[5/7] Copying OpenClaw into rootfs..."
mkdir -p "$ROOTFS_DIR/root/.npm-global/lib/node_modules"
mkdir -p "$ROOTFS_DIR/root/.npm-global/bin"
cp -a "$OCDIR" "$ROOTFS_DIR/root/.npm-global/lib/node_modules/openclaw"

# Create bin symlink  
BINFILE=$(ls "$STAGING/bin/openclaw" 2>/dev/null && echo "openclaw" || echo "")
if [ -z "$BINFILE" ]; then
    # Find the actual entry point
    ENTRY=$(node -e "const p=require('$OCDIR/package.json'); console.log(typeof p.bin === 'string' ? p.bin : Object.values(p.bin)[0])")
    ln -sf "../lib/node_modules/openclaw/$ENTRY" "$ROOTFS_DIR/root/.npm-global/bin/openclaw"
else
    cp -a "$STAGING/bin/openclaw" "$ROOTFS_DIR/root/.npm-global/bin/openclaw"
fi

echo "  OpenClaw in rootfs: $(du -sh "$ROOTFS_DIR/root/.npm-global" | cut -f1)"

###############################################
# PHASE 6: Cross-compile koffi for arm64
###############################################
echo "[6/7] Building koffi for arm64..."
KOFFI_DIR="$ROOTFS_DIR/root/.npm-global/lib/node_modules/openclaw/node_modules/koffi"
if [ -d "$KOFFI_DIR" ]; then
    chroot "$ROOTFS_DIR" /usr/bin/qemu-aarch64-static /bin/sh -c '
        apk add --no-cache npm python3 make g++
        cd /root/.npm-global/lib/node_modules/openclaw/node_modules/koffi
        npm rebuild 2>&1 | tail -10
        echo "=== koffi native files ==="
        find . -name "*.node" -ls 2>/dev/null
        # CRITICAL: delete glibc-linked linux_arm64/koffi.node (causes SIGSEGV under musl)
        rm -f build/koffi/linux_arm64/koffi.node 2>/dev/null || true
        echo "Deleted linux_arm64/koffi.node (glibc → musl fallback)"
        # Clean build tools
        apk del npm python3 make g++
        rm -rf /root/.npm /root/.cache /root/.node-gyp /tmp/* /var/cache/apk/*
    '
else
    echo "  WARNING: koffi not found, skipping native build"
fi

###############################################
# PHASE 7: Package
###############################################
echo "[7/7] Packaging rootfs..."
rm -f "$ROOTFS_DIR/usr/bin/qemu-aarch64-static"

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
