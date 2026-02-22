# LilClaw (小爪)

OpenClaw on Android — Pocket AI Gateway

一只龙虾住进了你的 Android 手机。安装即用，无需 root，本地运行 AI。

## Architecture

```
Android App (Kotlin/Compose, 109MB APK)
  └─ proot (userspace chroot, no root needed)
       └─ Alpine Linux arm64 rootfs
            ├─ Node.js
            ├─ OpenClaw gateway (port 3000, WebSocket)
            └─ serve-ui.cjs (port 3001, static files)

WebView → http://127.0.0.1:3001 (Chat SPA)
       → ws://127.0.0.1:3000 (Gateway protocol v3)
```

## Layered Rootfs

APK 内置 3 层 rootfs（首次启动从 assets 解压，零网络下载）：

| Layer | File | Size | Content |
|-------|------|------|---------|
| base | `base-arm64-2.0.0.tar.gz` | 41MB | Alpine Linux + Node.js |
| openclaw | `openclaw-2026.2.17-bundled.tar.gz` | 42MB | OpenClaw gateway (esbuild bundled) |
| chatspa | `chatspa-0.6.0.tar.gz` | 242KB | Chat SPA (React + Tailwind) |

### Layer 更新流程

层版本由 GitHub Release `layers-v3` 的 `manifest.json` 控制。

**发布新 chatspa 版本时必须同步更新 3 个地方：**

1. **构建 + 上传 tarball**
   ```bash
   cd web && npm run build
   tar czf chatspa-X.Y.Z.tar.gz -C dist .
   gh release upload layers-v3 chatspa-X.Y.Z.tar.gz --clobber
   gh release delete-asset layers-v3 chatspa-OLD.tar.gz --yes
   ```

2. **更新 `rootfs-build/manifest.json`**
   ```json
   {
     "layers": [
       { "name": "chatspa", "file": "chatspa-X.Y.Z.tar.gz", "version": "X.Y.Z", "size": <bytes> }
     ]
   }
   ```
   ```bash
   gh release upload layers-v3 rootfs-build/manifest.json --clobber
   ```

3. **更新 `RootfsManager.kt` FALLBACK_LAYERS**（代码内的硬编码后备）
   ```kotlin
   LayerInfo("chatspa", "chatspa-X.Y.Z.tar.gz", ...)
   ```

4. **更新 APK 内置 assets**（如果发新 APK release）
   ```bash
   cp chatspa-X.Y.Z.tar.gz app/src/main/assets/rootfs/
   ./gradlew assembleDebug
   ```

⚠️ **manifest.json 是 app 运行时拉取的版本真相来源。** 如果只更新了 release asset 但没更新 manifest，已安装的 app 不会知道有新版本。

### 已安装 App 的更新逻辑

```
App 启动 → quickStart()
  → fetchManifestLayers() 从 GitHub 拉最新 manifest.json
  → 对比本地 .layers.json（已安装版本）
  → 有差异的层 → 下载 + 解压覆盖
  → 无差异 → 直接启动
```

## 发版 Checklist

### 发 GitHub Release（带 APK）

```bash
# 1. 确保 manifest.json 和 FALLBACK_LAYERS 已更新
# 2. 确保 assets/rootfs/ 下有最新的 tar.gz（.gitignore 排除了）
# 3. 构建
./gradlew assembleDebug

# 4. Tag + Release
git tag vX.Y.Z
git push origin vX.Y.Z
gh release create vX.Y.Z app/build/outputs/apk/debug/app-debug.apk --title "vX.Y.Z — ..."

# 5. 验证
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 仅更新 Chat SPA（不发新 APK）

已安装的 app 下次启动时自动检测 manifest 变化并更新 chatspa 层。
只需完成上面"Layer 更新流程"的步骤 1-2。

## NativeBridge

Kotlin ↔ SPA 通信通过 `window.LilClaw` JS interface：

| Method | 功能 | SPA Callback |
|--------|------|-------------|
| `startVoice()` | 开始语音识别 (zh-CN) | `__lilclaw_onVoiceText(text)` / `__lilclaw_onVoicePartial(text)` |
| `stopVoice()` | 停止录音 | `__lilclaw_onVoiceState(state)` |
| `takePhoto()` | 拍照 | `__lilclaw_onImagePicked(dataUrl)` |
| `pickImage()` | 选图 | `__lilclaw_onImagePicked(dataUrl)` |
| `pickFile()` | 选文件 | `__lilclaw_onImagePicked(dataUrl)` |
| `haptic(type)` | 触觉反馈 (light/medium/heavy) | — |
| `openSettings()` | 打开原生设置页 | — |

## Gateway 可靠性

- **Crash 自动恢复**: `monitorGateway()` 每 5s 检查进程，挂了自动重启，最多 3 次
- **WakeLock**: 无超时，service 停止时释放
- **START_STICKY**: 被系统杀后自动重启
- **WebView 提前加载**: serve-ui ready (~2s) 就加载 WebView，不等 gateway (~6s)

## Build

```bash
# SPA
cd web && npm run build

# APK (需要 Java 17 + Android SDK)
./gradlew assembleDebug
# 产出: app/build/outputs/apk/debug/app-debug.apk

# 部署 SPA 到已安装的设备（热更新，不需要重装 APK）
bash rootfs-build/deploy-chatspa.sh
```

## Key Constraints

- **targetSdk 28**: proot 需要 execve 的 W^X 豁免
- **无新 npm 依赖**: 只用 package.json 里已有的
- **assets/rootfs/*.tar.gz 不入 git**: 在 .gitignore 中排除，构建前需手动准备
