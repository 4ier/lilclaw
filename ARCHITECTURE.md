# LilClaw Architecture

Pocket AI Gateway for Android — install, configure, chat.

## How It Works

```
Android App (Kotlin/Compose)
  └─ proot (userspace chroot, no root needed)
       └─ Alpine Linux arm64 rootfs
            ├─ Node.js
            ├─ OpenClaw gateway (port 3000, WebSocket)
            └─ serve-ui.cjs (port 3001, static files)

WebView → http://127.0.0.1:3001 (Chat SPA)
       → ws://127.0.0.1:3000 (Gateway protocol v3)
```

## App Lifecycle

1. **First launch**: Setup screen → provider config → `bootstrap()`
   - Download/extract rootfs layers → write config → start processes → poll ports
2. **Subsequent launches**: Setup detects `isSetupComplete` → `quickStart()`
   - Check for layer updates → start processes → poll ports
3. **Running**: WebView loads Chat SPA, which connects to gateway via WebSocket

## Module Map

### Android (app/src/main/kotlin/com/lilclaw/app/)

| File | Responsibility | Lines |
|------|---------------|-------|
| **service/GatewayManager.kt** | Orchestrates rootfs + config + processes | ~200 |
| **service/gateway/RootfsManager.kt** | Layer download, extraction, version tracking | ~280 |
| **service/gateway/ProcessRunner.kt** | proot command building, process lifecycle | ~100 |
| **service/gateway/ConfigWriter.kt** | Generate openclaw.json config | ~130 |
| **service/GatewayService.kt** | Android foreground service + wake lock | ~130 |
| **data/SettingsRepository.kt** | DataStore for provider/key/model/port | ~70 |
| **data/GatewayClient.kt** | WebSocket client (standby — used by native UI) | ~420 |
| **ui/setup/** | Setup wizard (Welcome → Provider → Launching → Done) | ~770 |
| **ui/settings/** | Settings screen (gateway status, provider info, about) | ~210 |
| **ui/webview/** | WebView container with keyboard handling | ~140 |
| **bridge/** | A11y + Device bridges (not yet integrated) | ~300 |
| **share/** | Share receiver + card exporter (partial) | ~210 |
| **di/AppModule.kt** | Koin dependency injection | ~20 |
| **navigation/** | Compose navigation (setup → webview → settings) | ~50 |

### Web SPA (web/src/)

| File | Responsibility |
|------|---------------|
| **lib/gateway.ts** | WebSocket client, OpenClaw protocol v3 |
| **store/index.ts** | Zustand store — connection, sessions, messages |
| **components/ChatScreen.tsx** | Main chat UI |
| **components/MessageBubble.tsx** | Markdown rendering, code blocks, HTML sandbox |
| **components/SessionDrawer.tsx** | Session list sidebar |
| **components/Settings.tsx** | Theme picker, native bridge |
| **serve-ui.cjs** | Zero-dependency Node.js static file server |

## Key Decisions

- **targetSdk 28**: Required for proot execve (W^X exemption on Android 10+)
- **Layered rootfs**: base (Alpine+Node) + openclaw + chatspa — independent update cycles
- **Remote manifest**: APK never needs updating for layer version bumps
- **WebView over Compose**: Gateway already has a web protocol; avoid duplicating in native
- **BuildConfig.VERSION_NAME**: Single source of truth for version across all surfaces
- **esbuild bundle**: 2841 JS modules → 1 bundled file (17MB). Gateway startup 20s → 6.4s on ARM64 proot. Native modules (koffi, better-sqlite3) kept external.
