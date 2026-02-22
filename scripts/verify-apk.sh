#!/bin/bash
# verify-apk.sh — 构建后自动验证 APK，卡住低级错误
set -uo pipefail

APK_DIR="app/build/outputs/apk/debug"
APK=$(ls "$APK_DIR"/lilclaw-v*.apk 2>/dev/null | head -1)

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'
FAIL=0

check() {
    if [ "$1" = "0" ]; then
        echo -e "${GREEN}✓${NC} $2"
    else
        echo -e "${RED}✗${NC} $2"
        FAIL=1
    fi
}

echo "=== LilClaw APK Verification ==="
echo ""

# 1. APK exists with correct naming
if [ -z "$APK" ]; then
    echo -e "${RED}✗${NC} No lilclaw-v*.apk found in $APK_DIR"
    exit 1
fi
check 0 "APK found: $(basename "$APK")"

# 2. No "app-debug" in filename
echo "$APK" | grep -q "app-debug" && check 1 "Filename contains 'app-debug'" || check 0 "Filename clean (no 'app-debug')"

# 3. Version in filename matches build.gradle
GRADLE_VERSION=$(grep 'versionName' app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)".*/\1/')
echo "$APK" | grep -q "$GRADLE_VERSION" && check 0 "Version matches gradle ($GRADLE_VERSION)" || check 1 "Version mismatch: APK=$(basename "$APK"), gradle=$GRADLE_VERSION"

# 4. Version > latest GitHub release
if command -v gh &>/dev/null; then
    GH_VERSION=$(gh release list --limit 1 --json tagName -q '.[0].tagName' 2>/dev/null | sed 's/^v//' || echo "")
    if [ -n "$GH_VERSION" ]; then
        if [ "$(printf '%s\n' "$GH_VERSION" "$GRADLE_VERSION" | sort -V | tail -1)" = "$GRADLE_VERSION" ]; then
            check 0 "Version $GRADLE_VERSION ≥ GitHub latest $GH_VERSION"
        else
            check 1 "Version $GRADLE_VERSION not newer than GitHub $GH_VERSION"
        fi
    fi
fi

# 5. Rootfs assets bundled (not empty)
ASSET_COUNT=$(unzip -l "$APK" 2>/dev/null | grep -c "assets/rootfs/" || true)
[ "$ASSET_COUNT" -ge 3 ] && check 0 "Rootfs assets bundled ($ASSET_COUNT files)" || check 1 "Rootfs assets missing ($ASSET_COUNT files, expected ≥3)"

# 6. Assets are gzip (not decompressed by AAPT)
BASE_SIZE=$(unzip -l "$APK" 2>/dev/null | grep "base-arm64" | awk '{print $1}')
if [ -n "$BASE_SIZE" ] && [ "$BASE_SIZE" -lt 80000000 ]; then
    check 0 "Base layer is gzip ($BASE_SIZE bytes < 80MB)"
else
    check 1 "Base layer may be decompressed ($BASE_SIZE bytes)"
fi

# 7. APK size sanity check (should be 80-150MB with bundled layers)
APK_SIZE=$(stat -f%z "$APK" 2>/dev/null || stat -c%s "$APK" 2>/dev/null)
APK_MB=$((APK_SIZE / 1048576))
if [ "$APK_MB" -ge 80 ] && [ "$APK_MB" -le 150 ]; then
    check 0 "APK size ${APK_MB}MB (expected 80-150MB)"
elif [ "$APK_MB" -lt 20 ]; then
    check 1 "APK size ${APK_MB}MB — rootfs likely not bundled"
else
    check 1 "APK size ${APK_MB}MB — unexpected"
fi

# 8. Source-level: AndroidManifest uses @string/app_name (not hardcoded)
if grep -q '@string/app_name' app/src/main/AndroidManifest.xml; then
    check 0 "AndroidManifest uses @string/app_name"
else
    check 1 "AndroidManifest has hardcoded label (should use @string/app_name)"
fi

echo ""
if [ "$FAIL" -eq 0 ]; then
    echo -e "${GREEN}All checks passed.${NC}"
else
    echo -e "${RED}Some checks failed. Fix before releasing.${NC}"
    exit 1
fi
