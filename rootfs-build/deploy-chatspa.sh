#!/bin/bash
# Quick deploy Chat SPA to Pixel via ADB
# Usage: ./deploy-chatspa.sh [--no-restart]
set -euo pipefail

DEVICE="9A271FFBA005UH"
WEB_DIR="$(cd "$(dirname "$0")/../web" && pwd)"
PKG="com.lilclaw.app"
TMP_TAR="/tmp/chatspa-update.tar.gz"

echo "=== Building Chat SPA ==="
cd "$WEB_DIR"
npx vite build 2>&1 | tail -3

echo "=== Packaging ==="
tar czf "$TMP_TAR" -C . dist/ serve-ui.cjs
echo "$(du -h "$TMP_TAR" | cut -f1) compressed"

echo "=== Pushing to Pixel ==="
adb -s "$DEVICE" push "$TMP_TAR" /data/local/tmp/chatspa-update.tar.gz 2>&1 | tail -1

echo "=== Extracting ==="
adb -s "$DEVICE" shell "run-as $PKG sh -c '
  cd files/rootfs/root/lilclaw-ui
  rm -rf dist
  tar xzf /data/local/tmp/chatspa-update.tar.gz
'"

if [[ "${1:-}" != "--no-restart" ]]; then
  echo "=== Restarting app ==="
  adb -s "$DEVICE" shell am force-stop "$PKG"
  sleep 1
  adb -s "$DEVICE" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 2>/dev/null
  echo "=== Waiting for ready ==="
  for i in $(seq 1 15); do
    sleep 1
    if adb -s "$DEVICE" shell "run-as $PKG sh -c 'test -f files/rootfs/root/lilclaw-ui/dist/index.html'" 2>/dev/null; then
      # Check if ports are up
      if adb -s "$DEVICE" shell "ps -ef | grep 'serve-ui' | grep -v grep" >/dev/null 2>&1; then
        echo "=== Ready (${i}s) ==="
        break
      fi
    fi
    echo -n "."
  done
fi

echo "=== Done ==="
