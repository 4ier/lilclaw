# LilClaw Layered Rootfs Architecture

## Layers

```
rootfs/                          ← base layer 解压目标
├── .layers.json                 ← 层版本清单（app 写入）
├── bin/, etc/, lib/, usr/       ← Alpine base
├── usr/bin/node                 ← Node.js 24.13.0 (base 层自带)
├── usr/local/
│   ├── bin/openclaw             ← openclaw layer
│   └── lib/node_modules/openclaw/
└── root/
    ├── android-compat.cjs       ← patches os.networkInterfaces() for Android
    ├── .openclaw/               ← gateway runtime (config, workspace, sessions)
    │   └── openclaw.json
    └── lilclaw-ui/              ← chatspa layer 解压目标
        ├── dist/                ← Vite 构建产物
        └── serve-ui.cjs         ← 静态文件服务器 (port 3001)
```

## Layer Packages (GitHub Releases: `layers-v2`)

| Layer    | File                          | Compressed | Uncompressed | Contents                              |
|----------|-------------------------------|------------|--------------|---------------------------------------|
| base     | base-arm64-2.0.0.tar.gz      | 41MB       | ~130MB       | Alpine 3.23.3 + Node 24.13.0 + npm + git |
| openclaw | openclaw-2026.2.17.tar.gz    | 32MB       | ~214MB       | OpenClaw 2026.2.17 (pruned, koffi fixed) |
| chatspa  | chatspa-0.3.0.tar.gz         | 231KB      | ~730KB       | Chat SPA + serve-ui.cjs               |

**Total: ~73MB** (was 289MB monolithic)

## CRITICAL: koffi SIGSEGV Fix

The `linux_arm64/koffi.node` prebuild is **glibc-linked** and causes SIGSEGV when loaded
in Alpine (musl) under proot. During openclaw layer build, this file MUST be deleted:

```bash
rm -f node_modules/koffi/build/koffi/linux_arm64/koffi.node
```

koffi's loader will fall back to `musl_arm64/koffi.node` automatically.

Root cause: `process.platform` returns `linux` under proot → koffi loads `linux_arm64` 
(glibc) instead of `musl_arm64` → dlopen corrupts memory → SIGSEGV during V8 JIT.

## Build Strategy

**NOT using qemu chroot** (OOM issues with npm install).
Instead, build directly on Android device via proot:

```bash
# On device (via adb shell):
# 1. Download Alpine 3.23.3 minirootfs
# 2. Extract, proot into it
# 3. apk add nodejs npm git
# 4. npm install -g --ignore-scripts openclaw@2026.2.17
# 5. Delete linux_arm64/koffi.node + prune
# 6. tar up layers separately
```

See `build-on-device.sh` for the complete script.

## Manifest (.layers.json)

Written by the app after extraction:

```json
{
  "base": { "version": "2.0.0", "installedAt": "2026-02-20T15:00:00Z" },
  "openclaw": { "version": "2026.2.17", "installedAt": "2026-02-20T15:02:00Z" },
  "chatspa": { "version": "0.3.0", "installedAt": "2026-02-20T15:02:05Z" }
}
```

## App Initialization Flow

```
1. Check rootfs/.layers.json exists?
   NO  → fresh install, download all 3 layers
   YES → read local versions

2. For each layer (base → openclaw → chatspa):
   if layer missing or version outdated:
     download → extract (overlays onto rootfs/) → update .layers.json

3. Write android-compat.cjs if missing

4. Start gateway (port 3000) + serve-ui.cjs (port 3001)

5. WebView loads http://localhost:3001
```

## Update Flow

- **chatspa update**: Download 231KB, extract, restart serve-ui → instant UI update
- **openclaw update**: Download ~32MB, extract over existing, restart gateway
- **base update**: Rare, requires full re-extraction of all layers
