# LilClaw Rootfs Build System

分层 rootfs 构建系统。App 首次启动时从 GitHub Releases 按顺序下载并解压这些层。

## 层结构

| 层 | 内容 | 大小 | 更新频率 |
|---|---|---|---|
| **base** | Alpine arm64 + Node.js | ~41 MB | 极少 (OS/Node 升级) |
| **openclaw** | OpenClaw + 依赖 + esbuild bundle | ~42 MB | 跟随 OpenClaw 发版 |
| **chatspa** | Chat SPA (Vite build + serve-ui.cjs) | ~244 KB | 每次前端改动 |
| **config** | SOUL.md + TOOLS.md | ~3 KB | 调整人格/工具配置 |

层按顺序解压到同一个 rootfs 目录，后面的层覆盖前面的文件。

## 快速使用

```bash
# 构建轻量层（不需要 root）
./build-all.sh chatspa config

# 构建全部层（base 和 openclaw 需要 root + qemu-aarch64-static）
sudo ./build-all.sh

# 构建并上传到 GitHub Release
./build-all.sh --release chatspa config

# 指定版本
CHATSPA_VERSION=0.8.0 CONFIG_VERSION=0.3.0 ./build-all.sh chatspa config
```

## 单独构建

```bash
./build-base.sh 2.0.0         # 需要 root
./build-openclaw.sh 2026.2.17  # 需要 root
./build-chatspa.sh 0.8.0       # 不需要 root
./build-config.sh 0.3.0        # 不需要 root
```

## 快速部署到设备

```bash
# 只更新 Chat SPA（不重启 gateway）
./deploy-chatspa.sh --no-restart

# 更新 Chat SPA 并重启 app
./deploy-chatspa.sh
```

## 文件说明

- `build-all.sh` — 主构建脚本，编排所有层的构建和上传
- `build-base.sh` — Alpine + Node.js 基础层
- `build-openclaw.sh` — OpenClaw 安装 + 裁剪 + esbuild 打包
- `build-chatspa.sh` — Chat SPA 前端构建
- `build-config.sh` — 配置文件层（从 `assets/` 读取）
- `deploy-chatspa.sh` — ADB 快速部署到真机
- `manifest.json` — 层版本清单（App 启动时拉取）

## 依赖

- **base/openclaw 层**: root、qemu-aarch64-static（`apt install qemu-user-static`）、Alpine minirootfs
- **chatspa 层**: pnpm（或 npm）、Node.js
- **config 层**: 无特殊依赖
- **上传**: `gh` CLI（已登录）

## 版本管理

`manifest.json` 是版本的唯一真相来源。App 的 `RootfsManager.kt` 中有 `FALLBACK_LAYERS` 硬编码作为离线回退。

构建新版本后记得更新 `FALLBACK_LAYERS`。
