#!/bin/bash
# build-openclaw.sh — Build OpenClaw layer (installed inside arm64 chroot)
# Requires: qemu-aarch64-static, root, base layer already built
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WORK="$SCRIPT_DIR/work-openclaw"
MINIROOTFS="$SCRIPT_DIR/alpine-minirootfs-3.21.3-aarch64.tar.gz"
VERSION="${1:-2026.2.17}"
OUTPUT="$SCRIPT_DIR/openclaw-${VERSION}.tar.gz"

echo "=== Building OpenClaw layer v${VERSION} ==="

# Start from fresh Alpine + Node.js (same as base, but we'll only package .npm-global)
rm -rf "$WORK"
mkdir -p "$WORK"

echo "[1/5] Preparing build environment..."
tar xzf "$MINIROOTFS" -C "$WORK"
cp /usr/bin/qemu-aarch64-static "$WORK/usr/bin/"
echo "nameserver 8.8.8.8" > "$WORK/etc/resolv.conf"

echo "[2/5] Installing Node.js + npm + build tools..."
chroot "$WORK" /usr/bin/qemu-aarch64-static /bin/sh -c '
    apk update
    apk add --no-cache nodejs npm python3 make g++ linux-headers
    node --version
    npm --version
'

echo "[3/5] Installing OpenClaw (this takes a while in qemu)..."
chroot "$WORK" /usr/bin/qemu-aarch64-static /bin/sh -c '
    mkdir -p /root/.npm-global
    npm config set prefix /root/.npm-global
    export PATH=/root/.npm-global/bin:$PATH

    # Install OpenClaw globally
    npm install -g openclaw 2>&1 | tail -20

    # Verify
    /root/.npm-global/bin/openclaw --version || echo "WARNING: openclaw --version failed"
'

echo "[4/5] Pruning OpenClaw dependencies..."
chroot "$WORK" /usr/bin/qemu-aarch64-static /bin/sh -c '
    OCDIR=/root/.npm-global/lib/node_modules/openclaw/node_modules
    echo "Before: $(du -sh /root/.npm-global | cut -f1)"

    # === MEGA: unused heavy deps ===
    rm -rf $OCDIR/@node-llama-cpp $OCDIR/node-llama-cpp  # ~711MB local LLM
    rm -rf $OCDIR/pdfjs-dist                              # ~38MB PDF
    rm -rf $OCDIR/sharp $OCDIR/@img                       # ~17MB images
    rm -rf $OCDIR/@napi-rs                                # ~32MB prebuilds

    # === Unused channel SDKs (gateway connects locally) ===
    rm -rf $OCDIR/discord.js $OCDIR/@discordjs
    rm -rf $OCDIR/whatsapp-web.js
    rm -rf $OCDIR/slack-bolt $OCDIR/@slack
    rm -rf $OCDIR/matrix-js-sdk $OCDIR/matrix-bot-sdk
    rm -rf $OCDIR/puppeteer $OCDIR/puppeteer-core $OCDIR/chromium

    # === Build/dev tools (not needed at runtime) ===
    rm -rf $OCDIR/typescript $OCDIR/esbuild $OCDIR/@esbuild
    rm -rf $OCDIR/@rollup $OCDIR/rollup
    rm -rf $OCDIR/webpack $OCDIR/webpack-cli
    rm -rf $OCDIR/eslint $OCDIR/@eslint $OCDIR/prettier
    rm -rf $OCDIR/ts-node $OCDIR/tsx $OCDIR/@types

    # === Wrong-arch native binaries ===
    find /root/.npm-global -name "*.node" -path "*/linux-x64/*" -delete 2>/dev/null || true
    find /root/.npm-global -type d -name "darwin-*" -exec rm -rf {} + 2>/dev/null || true
    find /root/.npm-global -type d -name "win32-*" -exec rm -rf {} + 2>/dev/null || true
    find /root/.npm-global -type d -name "linux-x64" -exec rm -rf {} + 2>/dev/null || true

    # === Junk files ===
    find /root/.npm-global -name "*.map" -delete 2>/dev/null || true
    find /root/.npm-global -name "*.d.ts" -delete 2>/dev/null || true
    find /root/.npm-global -name "*.d.mts" -delete 2>/dev/null || true
    find /root/.npm-global \( -name "README.md" -o -name "CHANGELOG*" -o -name "LICENSE*" \
        -o -name ".npmignore" -o -name ".eslintrc*" -o -name "tsconfig*" \) -delete 2>/dev/null || true
    find /root/.npm-global -type d \( -name "test" -o -name "tests" -o -name "__tests__" \
        -o -name "docs" -o -name "example" -o -name "examples" \) \
        -exec rm -rf {} + 2>/dev/null || true

    # Remove build tools
    apk del python3 make g++ linux-headers
    rm -rf /root/.npm /root/.cache /root/.node-gyp /tmp/* /var/cache/apk/*

    echo "After: $(du -sh /root/.npm-global | cut -f1)"

    # Final verify
    export PATH=/root/.npm-global/bin:$PATH
    openclaw --version || echo "WARNING: openclaw --version failed after prune"
'

# Remove qemu
rm "$WORK/usr/bin/qemu-aarch64-static"

# Package ONLY the .npm-global directory (with path prefix for correct extraction)
echo "[5/5] Packaging OpenClaw layer..."
cd "$WORK"
tar czf "$OUTPUT" ./root/.npm-global

echo ""
echo "=== OpenClaw layer built ==="
ls -lh "$OUTPUT"
echo "Compressed: $(du -sh "$OUTPUT" | cut -f1)"
echo "Uncompressed: $(du -sh "$WORK/root/.npm-global" | cut -f1)"

# Cleanup
rm -rf "$WORK"
