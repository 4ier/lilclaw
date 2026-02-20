#!/bin/bash
# build-on-device.sh — Build all three rootfs layers on Android via proot
# Run from host machine with adb access to a Pixel device
# 
# Prerequisites:
#   - adb connected to device (set ADB_SERIAL below)
#   - proot + proot_loader + libtalloc.so.2 at /data/local/tmp/ on device
#   - Internet access on device
#
# Output: three tar.gz files pulled to current directory
set -euo pipefail

ADB_SERIAL="${ADB_SERIAL:-9A271FFBA005UH}"
ADB="adb -s $ADB_SERIAL"
DEVICE_TMP="/data/local/tmp"
ROOTFS="$DEVICE_TMP/build-rootfs"
PROOT="$DEVICE_TMP/proot"
LOADER="$DEVICE_TMP/proot_loader"

ALPINE_VERSION="3.23.3"
NODE_VERSION="24.13.0"  # from Alpine 3.23 apk
OPENCLAW_VERSION="${1:-2026.2.17}"
CHATSPA_VERSION="0.3.0"

proot_exec() {
    $ADB shell "
export LD_LIBRARY_PATH=$DEVICE_TMP
export PROOT_LOADER=$LOADER
export PROOT_TMP_DIR=$DEVICE_TMP
$PROOT --link2symlink -0 \
  -r $ROOTFS \
  -b /dev -b /proc -b /sys \
  -w /root \
  /usr/bin/env HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  sh -c '$1' 2>&1
"
}

echo "=== Building LilClaw rootfs layers ==="
echo "Alpine $ALPINE_VERSION | Node $NODE_VERSION | OpenClaw $OPENCLAW_VERSION"
echo ""

# ── Step 1: Download Alpine minirootfs ──
echo "[1/8] Downloading Alpine $ALPINE_VERSION minirootfs..."
ALPINE_URL="https://dl-cdn.alpinelinux.org/alpine/v3.23/releases/aarch64/alpine-minirootfs-${ALPINE_VERSION}-aarch64.tar.gz"
wget -q "$ALPINE_URL" -O /tmp/alpine-minirootfs.tar.gz
$ADB push /tmp/alpine-minirootfs.tar.gz $DEVICE_TMP/

# ── Step 2: Extract and setup base ──
echo "[2/8] Setting up base rootfs..."
$ADB shell "
rm -rf $ROOTFS
mkdir -p $ROOTFS
cd $ROOTFS
tar xzf $DEVICE_TMP/alpine-minirootfs.tar.gz
echo 'nameserver 8.8.8.8' > etc/resolv.conf
"

# ── Step 3: Install Node.js + npm + git ──
echo "[3/8] Installing Node.js, npm, git..."
proot_exec 'apk add --no-cache nodejs npm git'

# ── Step 4: Package base layer ──
echo "[4/8] Packaging base layer..."
$ADB shell "
cd $ROOTFS
tar czf $DEVICE_TMP/base-arm64-2.0.0.tar.gz \
  --exclude='./root/*' \
  --exclude='./tmp/*' \
  .
"
$ADB pull $DEVICE_TMP/base-arm64-2.0.0.tar.gz ./
echo "  → base-arm64-2.0.0.tar.gz ($(du -h base-arm64-2.0.0.tar.gz | cut -f1))"

# ── Step 5: Install OpenClaw ──
echo "[5/8] Installing OpenClaw $OPENCLAW_VERSION (this takes ~2 min)..."
proot_exec "npm install -g --ignore-scripts openclaw@$OPENCLAW_VERSION"

# ── Step 6: koffi fix + prune ──
echo "[6/8] Applying koffi fix and pruning..."
proot_exec '
OCDIR=/usr/local/lib/node_modules/openclaw/node_modules

# CRITICAL: delete glibc-linked koffi prebuild (causes SIGSEGV on musl+proot)
rm -f $OCDIR/koffi/build/koffi/linux_arm64/koffi.node

# Remove all non-musl_arm64 koffi prebuilds
for p in darwin_arm64 darwin_x64 freebsd_arm64 freebsd_ia32 freebsd_x64 linux_armhf linux_ia32 linux_loong64 linux_riscv64d linux_x64 musl_x64 openbsd_ia32 openbsd_x64 win32_arm64 win32_ia32 win32_x64; do
  rm -rf $OCDIR/koffi/build/koffi/$p 2>/dev/null
done

# Remove glibc-linked node-pty
rm -rf $OCDIR/node-pty

# Remove unused heavy deps
rm -rf $OCDIR/@node-llama-cpp $OCDIR/node-llama-cpp 2>/dev/null
rm -rf $OCDIR/pdfjs-dist 2>/dev/null
rm -rf $OCDIR/sharp $OCDIR/@img 2>/dev/null
rm -rf $OCDIR/@napi-rs 2>/dev/null
rm -rf $OCDIR/puppeteer $OCDIR/puppeteer-core $OCDIR/chromium 2>/dev/null

# Remove wrong-arch native binaries
find /usr/local/lib/node_modules -type d -name "darwin-*" -exec rm -rf {} + 2>/dev/null
find /usr/local/lib/node_modules -type d -name "win32-*" -exec rm -rf {} + 2>/dev/null
find /usr/local/lib/node_modules -type d -name "linux-x64*" -exec rm -rf {} + 2>/dev/null
find /usr/local/lib/node_modules -type d -name "linux-ia32*" -exec rm -rf {} + 2>/dev/null

# Remove junk files
find /usr/local/lib/node_modules -name "*.map" -delete 2>/dev/null
find /usr/local/lib/node_modules -name "*.d.ts" -delete 2>/dev/null
find /usr/local/lib/node_modules -name "*.d.mts" -delete 2>/dev/null
find /usr/local/lib/node_modules \( -name "CHANGELOG*" -o -name ".npmignore" -o -name ".eslintrc*" -o -name "tsconfig*" \) -delete 2>/dev/null
find /usr/local/lib/node_modules -type d \( -name "test" -o -name "tests" -o -name "__tests__" -o -name "docs" -o -name "example" -o -name "examples" \) -exec rm -rf {} + 2>/dev/null

# Clean npm cache
rm -rf /root/.npm /tmp/*

echo "Size after prune: $(du -sh /usr/local/lib/node_modules/openclaw | cut -f1)"
openclaw --version
'

# ── Step 7: Package openclaw layer ──
echo "[7/8] Packaging openclaw layer..."
$ADB shell "
cd $ROOTFS
tar czf $DEVICE_TMP/openclaw-${OPENCLAW_VERSION}.tar.gz ./usr/local/bin/openclaw ./usr/local/lib/node_modules/
"
$ADB pull $DEVICE_TMP/openclaw-${OPENCLAW_VERSION}.tar.gz ./
echo "  → openclaw-${OPENCLAW_VERSION}.tar.gz ($(du -h openclaw-${OPENCLAW_VERSION}.tar.gz | cut -f1))"

# ── Step 8: Build chatspa layer ──
echo "[8/8] Building chatspa layer..."
if [ -d "../web" ]; then
    (cd ../web && pnpm build 2>&1 | tail -1)
    mkdir -p /tmp/chatspa-staging/root/lilclaw-ui
    cp -r ../web/dist /tmp/chatspa-staging/root/lilclaw-ui/
    cp ../web/serve-ui.cjs /tmp/chatspa-staging/root/lilclaw-ui/
    (cd /tmp/chatspa-staging && tar czf /tmp/chatspa-${CHATSPA_VERSION}.tar.gz ./root/lilclaw-ui/)
    cp /tmp/chatspa-${CHATSPA_VERSION}.tar.gz ./
    echo "  → chatspa-${CHATSPA_VERSION}.tar.gz ($(du -h chatspa-${CHATSPA_VERSION}.tar.gz | cut -f1))"
else
    echo "  ⚠ web/ directory not found, skipping chatspa layer"
fi

echo ""
echo "=== Done ==="
echo ""
ls -lh base-arm64-*.tar.gz openclaw-*.tar.gz chatspa-*.tar.gz 2>/dev/null
echo ""
echo "Upload to GitHub: gh release create layers-vN base-arm64-*.tar.gz openclaw-*.tar.gz chatspa-*.tar.gz"
