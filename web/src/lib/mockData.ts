import type { ChatMessage } from '../lib/gateway'

export const mockConversation: ChatMessage[] = [
  {
    role: 'user',
    content: [{ type: 'text', text: '给我展示一下 Markdown 的各种渲染效果' }],
    timestamp: Date.now() - 60000,
  },
  {
    role: 'assistant',
    content: [{
      type: 'text',
      text: `# Heading 1
## Heading 2
### Heading 3

这是一段普通文本，包含 **粗体**、*斜体*、~~删除线~~ 和 \`行内代码\`。

> 这是一段引用
> 可以多行

---

### 链接和图片

[OpenClaw GitHub](https://github.com/openclaw/openclaw)

![一张测试图片](https://picsum.photos/400/200)

### 列表

无序列表：
- 第一项
- 第二项
  - 嵌套项 A
  - 嵌套项 B
- 第三项

有序列表：
1. 步骤一：安装依赖
2. 步骤二：配置环境
3. 步骤三：启动服务

任务列表：
- [x] 完成 Chat SPA
- [x] 连接 Gateway
- [ ] 部署到 rootfs
- [ ] Android WebView 集成`
    }],
    timestamp: Date.now() - 55000,
  },
  {
    role: 'user',
    content: [{ type: 'text', text: '代码块呢？多种语言的' }],
    timestamp: Date.now() - 50000,
  },
  {
    role: 'assistant',
    content: [{
      type: 'text',
      text: `### Python

\`\`\`python
import asyncio
from dataclasses import dataclass

@dataclass
class Message:
    role: str
    content: str
    timestamp: float = 0.0

async def stream_response(messages: list[Message]) -> str:
    """Stream tokens from the model."""
    buffer = []
    async for token in model.generate(messages):
        buffer.append(token)
        yield "".join(buffer)
    return "".join(buffer)
\`\`\`

### TypeScript

\`\`\`typescript
interface GatewayConfig {
  port: number
  token: string
  model: string
}

const connect = async (config: GatewayConfig): Promise<WebSocket> => {
  const ws = new WebSocket(\`ws://localhost:\${config.port}\`)
  
  return new Promise((resolve, reject) => {
    ws.onopen = () => resolve(ws)
    ws.onerror = (e) => reject(e)
  })
}
\`\`\`

### Rust

\`\`\`rust
use tokio::sync::mpsc;

#[derive(Debug, Clone)]
struct ChatMessage {
    role: String,
    content: String,
}

async fn process_messages(mut rx: mpsc::Receiver<ChatMessage>) {
    while let Some(msg) = rx.recv().await {
        println!("[{}]: {}", msg.role, msg.content);
    }
}
\`\`\`

### Shell

\`\`\`bash
#!/bin/bash
# 启动 LilClaw gateway
cd /root && node /usr/local/bin/openclaw gateway run \\
  --port 3000 \\
  --token lilclaw-local \\
  --allow-unconfigured
\`\`\``
    }],
    timestamp: Date.now() - 45000,
  },
  {
    role: 'user',
    content: [{ type: 'text', text: '表格能渲染吗？' }],
    timestamp: Date.now() - 40000,
  },
  {
    role: 'assistant',
    content: [{
      type: 'text',
      text: `当然可以！

| 功能 | 状态 | 优先级 |
|------|------|--------|
| WebSocket 连接 | ✅ 完成 | P0 |
| Markdown 渲染 | ✅ 完成 | P0 |
| 代码高亮 | ✅ 完成 | P0 |
| Session 管理 | ✅ 完成 | P1 |
| 图片分享 | 🔲 待做 | P1 |
| 拉群 | 🔲 待做 | P2 |
| 充值系统 | 🔲 待做 | P2 |

还有一个更复杂的表格：

| Provider | Model | 价格 (input/1M tokens) | 价格 (output/1M tokens) |
|----------|-------|----------------------|----------------------|
| OpenAI | GPT-4o | $2.50 | $10.00 |
| Anthropic | Claude Sonnet 4 | $3.00 | $15.00 |
| DeepSeek | DeepSeek Chat | $0.14 | $0.28 |
| AWS Bedrock | Claude Opus 4 | $15.00 | $75.00 |`
    }],
    timestamp: Date.now() - 35000,
  },
  {
    role: 'user',
    content: [{ type: 'text', text: '数学公式和特殊字符呢？还有 HTML？' }],
    timestamp: Date.now() - 30000,
  },
  {
    role: 'assistant',
    content: [{
      type: 'text',
      text: `### 特殊字符 & Emoji

Arrow: → ← ↑ ↓ ↔
Math: × ÷ ± ≤ ≥ ≠ ≈ ∞ π √ Σ ∫ ∂
Emoji: 🐾 🦞 🚀 ✨ 🎵 💡 🔧 ⚡

### 内联 HTML

<details>
<summary>点击展开详细信息</summary>

这是折叠的内容！支持 **Markdown** 嵌套。

- 列表项 1
- 列表项 2

</details>

<div style="padding: 12px; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); border-radius: 8px; color: white; text-align: center; margin: 8px 0;">
  <strong>✨ LilClaw — Pocket AI Gateway ✨</strong>
</div>

### 长段代码 + JSON

\`\`\`json
{
  "agents": {
    "defaults": {
      "model": {
        "primary": "deepseek/deepseek-chat"
      },
      "workspace": "/root/.openclaw/workspace-dev",
      "skipBootstrap": true
    },
    "list": [
      {
        "id": "dev",
        "default": true,
        "workspace": "/root/.openclaw/workspace-dev"
      }
    ]
  },
  "gateway": {
    "mode": "local",
    "port": 3000,
    "bind": "loopback",
    "auth": { "token": "lilclaw-local" }
  }
}
\`\`\``
    }],
    timestamp: Date.now() - 25000,
  },
  {
    role: 'user',
    content: [{ type: 'text', text: '最后测一下流式输出的效果' }],
    timestamp: Date.now() - 20000,
  },
  {
    role: 'assistant',
    content: [{
      type: 'text',
      text: `好的，这是一条模拟的流式消息。

在实际使用中，文字会逐步出现，就像这样一个字一个字地显示出来，最后面有一个闪烁的光标。

当 agent 在思考或调用工具时，顶部会显示状态指示器。`
    }],
    timestamp: Date.now() - 15000,
  },
]
