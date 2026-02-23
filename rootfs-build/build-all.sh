#!/bin/bash
# build-all.sh — Master build & release script for LilClaw rootfs layers
#
# Usage:
#   ./build-all.sh                    # Build all layers
#   ./build-all.sh chatspa            # Build only chatspa layer
#   ./build-all.sh chatspa config     # Build chatspa + config
#   ./build-all.sh --release          # Build all + upload to GitHub release
#   ./build-all.sh --release chatspa  # Build chatspa + upload
#
# Layers (in extraction order):
#   base     — Alpine arm64 + Node.js (needs: root, qemu-aarch64-static)
#   openclaw — OpenClaw npm + prune + esbuild bundle (needs: root, qemu)
#   chatspa  — Chat SPA (Vite build, no root needed)
#   config   — SOUL.md + TOOLS.md (no root needed)
#
# Environment:
#   GITHUB_RELEASE — release tag name (default: layers-v3)
#   SKIP_UPLOAD    — set to "1" to build without uploading
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RELEASE_TAG="${GITHUB_RELEASE:-layers-v3}"

# Parse args
DO_RELEASE=false
LAYERS_TO_BUILD=()
for arg in "$@"; do
    case "$arg" in
        --release) DO_RELEASE=true ;;
        base|openclaw|chatspa|config) LAYERS_TO_BUILD+=("$arg") ;;
        *) echo "Unknown arg: $arg"; exit 1 ;;
    esac
done

# Default: build all
if [ ${#LAYERS_TO_BUILD[@]} -eq 0 ]; then
    LAYERS_TO_BUILD=(base openclaw chatspa config)
fi

echo "╔══════════════════════════════════════════╗"
echo "║  LilClaw Layer Builder                   ║"
echo "╚══════════════════════════════════════════╝"
echo ""
echo "Layers: ${LAYERS_TO_BUILD[*]}"
echo "Release: $DO_RELEASE (tag: $RELEASE_TAG)"
echo ""

BUILT_FILES=()
MANIFEST_ENTRIES=()

# ── Helper: read version from manifest or fallback ──
get_current_version() {
    local layer="$1"
    if [ -f "$SCRIPT_DIR/manifest.json" ]; then
        jq -r ".layers[] | select(.name == \"$layer\") | .version" "$SCRIPT_DIR/manifest.json" 2>/dev/null || echo ""
    fi
}

# ── Helper: bump patch version ──
bump_version() {
    local v="$1"
    if [[ "$v" == *"."* ]]; then
        local prefix="${v%.*}"
        local patch="${v##*.}"
        echo "${prefix}.$((patch + 1))"
    else
        echo "${v}.1"
    fi
}

# ── Build functions ──

build_base() {
    local version="${BASE_VERSION:-$(get_current_version base)}"
    [ -z "$version" ] && version="2.0.0"
    echo "━━━ Building base v${version} ━━━"
    
    if [ "$(id -u)" -ne 0 ]; then
        echo "ERROR: base layer requires root (for chroot + qemu)"
        echo "  Run: sudo ./build-all.sh base"
        return 1
    fi
    
    bash "$SCRIPT_DIR/build-base.sh" "$version"
    local file="base-arm64-${version}.tar.gz"
    local size=$(stat -c%s "$SCRIPT_DIR/$file")
    BUILT_FILES+=("$SCRIPT_DIR/$file")
    MANIFEST_ENTRIES+=("{\"name\":\"base\",\"file\":\"$file\",\"version\":\"$version\",\"size\":$size}")
    echo ""
}

build_openclaw() {
    local version="${OPENCLAW_VERSION:-$(get_current_version openclaw)}"
    [ -z "$version" ] && version="2026.2.17"
    echo "━━━ Building openclaw v${version} ━━━"
    
    if [ "$(id -u)" -ne 0 ]; then
        echo "ERROR: openclaw layer requires root (for chroot + qemu)"
        echo "  Run: sudo ./build-all.sh openclaw"
        return 1
    fi
    
    bash "$SCRIPT_DIR/build-openclaw.sh" "$version"
    # build-openclaw outputs with -bundled suffix
    local file="openclaw-${version}.tar.gz"
    # Check if bundled version exists
    if [ -f "$SCRIPT_DIR/openclaw-${version}-bundled.tar.gz" ]; then
        file="openclaw-${version}-bundled.tar.gz"
    fi
    local size=$(stat -c%s "$SCRIPT_DIR/$file")
    BUILT_FILES+=("$SCRIPT_DIR/$file")
    MANIFEST_ENTRIES+=("{\"name\":\"openclaw\",\"file\":\"$file\",\"version\":\"$version\",\"size\":$size}")
    echo ""
}

build_chatspa() {
    local version="${CHATSPA_VERSION:-}"
    if [ -z "$version" ]; then
        local current=$(get_current_version chatspa)
        version=$(bump_version "${current:-0.7.0}")
    fi
    echo "━━━ Building chatspa v${version} ━━━"
    
    bash "$SCRIPT_DIR/build-chatspa.sh" "$version"
    local file="chatspa-${version}.tar.gz"
    local size=$(stat -c%s "$SCRIPT_DIR/$file")
    BUILT_FILES+=("$SCRIPT_DIR/$file")
    MANIFEST_ENTRIES+=("{\"name\":\"chatspa\",\"file\":\"$file\",\"version\":\"$version\",\"size\":$size}")
    echo ""
}

build_config() {
    local version="${CONFIG_VERSION:-}"
    if [ -z "$version" ]; then
        local current=$(get_current_version config)
        version=$(bump_version "${current:-0.2.0}")
    fi
    echo "━━━ Building config v${version} ━━━"
    
    bash "$SCRIPT_DIR/build-config.sh" "$version"
    local file="config-${version}.tar.gz"
    local size=$(stat -c%s "$SCRIPT_DIR/$file")
    BUILT_FILES+=("$SCRIPT_DIR/$file")
    MANIFEST_ENTRIES+=("{\"name\":\"config\",\"file\":\"$file\",\"version\":\"$version\",\"size\":$size}")
    echo ""
}

# ── Execute builds ──

for layer in "${LAYERS_TO_BUILD[@]}"; do
    "build_${layer}"
done

# ── Generate manifest ──

echo "━━━ Generating manifest.json ━━━"

# Start with existing manifest entries for layers we didn't build
FINAL_MANIFEST="[]"
if [ -f "$SCRIPT_DIR/manifest.json" ]; then
    for layer_name in base openclaw chatspa config; do
        # Skip if we built this layer (we have a fresh entry)
        skip=false
        for built in "${LAYERS_TO_BUILD[@]}"; do
            [ "$built" = "$layer_name" ] && skip=true
        done
        if [ "$skip" = false ]; then
            existing=$(jq -c ".layers[] | select(.name == \"$layer_name\")" "$SCRIPT_DIR/manifest.json" 2>/dev/null || echo "")
            if [ -n "$existing" ]; then
                FINAL_MANIFEST=$(echo "$FINAL_MANIFEST" | jq ". + [$existing]")
            fi
        fi
    done
fi

# Add newly built entries
for entry in "${MANIFEST_ENTRIES[@]}"; do
    FINAL_MANIFEST=$(echo "$FINAL_MANIFEST" | jq ". + [$entry]")
done

# Sort: base, openclaw, chatspa, config
SORTED=$(echo "$FINAL_MANIFEST" | jq '[
    (.[] | select(.name == "base")),
    (.[] | select(.name == "openclaw")),
    (.[] | select(.name == "chatspa")),
    (.[] | select(.name == "config"))
] | [.[] | select(. != null)]')

echo "{\"layers\": $SORTED}" | jq . > "$SCRIPT_DIR/manifest.json"
BUILT_FILES+=("$SCRIPT_DIR/manifest.json")

echo "manifest.json:"
cat "$SCRIPT_DIR/manifest.json"
echo ""

# ── Upload to GitHub Release ──

if [ "$DO_RELEASE" = true ] && [ "${SKIP_UPLOAD:-}" != "1" ]; then
    echo "━━━ Uploading to GitHub release $RELEASE_TAG ━━━"
    
    cd "$REPO_ROOT"
    
    # Ensure release exists
    if ! gh release view "$RELEASE_TAG" &>/dev/null; then
        echo "Creating release $RELEASE_TAG..."
        gh release create "$RELEASE_TAG" --title "Layer Assets v3" --notes "Rootfs layer assets for LilClaw"
    fi
    
    for f in "${BUILT_FILES[@]}"; do
        fname=$(basename "$f")
        echo "  Uploading $fname..."
        # Delete existing asset if present, then upload
        gh release delete-asset "$RELEASE_TAG" "$fname" --yes 2>/dev/null || true
        gh release upload "$RELEASE_TAG" "$f"
    done
    
    echo ""
    echo "✓ All assets uploaded to $RELEASE_TAG"
fi

# ── Update RootfsManager fallbacks ──

echo "━━━ Summary ━━━"
echo ""
echo "Built layers:"
for f in "${BUILT_FILES[@]}"; do
    echo "  $(basename "$f") ($(du -h "$f" | cut -f1))"
done
echo ""

if [ "$DO_RELEASE" = false ]; then
    echo "To upload: ./build-all.sh --release ${LAYERS_TO_BUILD[*]}"
fi

# Remind about RootfsManager update
echo ""
echo "Remember to update FALLBACK_LAYERS in RootfsManager.kt if versions changed."
