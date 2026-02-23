export type ConnectionState = 'disconnected' | 'connecting' | 'connected' | 'error'

export interface MessageContent {
  type: 'text' | 'image'
  text?: string
  url?: string
}

export interface ChatMessage {
  role: 'user' | 'assistant'
  content: MessageContent[]
  timestamp?: number
}

export interface AgentEvent {
  kind: 'thinking' | 'tool_use' | 'done' | 'error'
  data?: unknown
}

export interface SessionInfo {
  key: string
  label?: string
  displayName?: string
  lastActivity?: number
}

export interface GatewayCallbacks {
  onConnectionChange: (state: ConnectionState) => void
  onChatEvent: (sessionKey: string, state: 'delta' | 'final', content: MessageContent[]) => void
  onAgentEvent: (sessionKey: string, event: AgentEvent) => void
  onHistoryLoaded: (sessionKey: string, messages: ChatMessage[]) => void
  onSessionsLoaded: (sessions: SessionInfo[]) => void
  onError: (error: string) => void
}

interface PendingRequest {
  resolve: (response: unknown) => void
  reject: (error: Error) => void
}

export class GatewayClient {
  private ws: WebSocket | null = null
  private callbacks: GatewayCallbacks
  private port: number
  private token: string
  private reconnectAttempt = 0
  private maxReconnectAttempt = 10
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private pendingRequests = new Map<string, PendingRequest>()
  private requestId = 0
  private shouldReconnect = true
  private lastSessionKey = 'main'

  constructor(callbacks: GatewayCallbacks, port = 3000, token = 'lilclaw-local') {
    this.callbacks = callbacks
    this.port = port
    this.token = token
  }

  updateConfig(port: number, token: string) {
    this.port = port
    this.token = token
  }

  connect() {
    if (this.ws?.readyState === WebSocket.OPEN || this.ws?.readyState === WebSocket.CONNECTING) {
      return
    }

    this.shouldReconnect = true
    this.callbacks.onConnectionChange('connecting')

    try {
      // Use same hostname as the page to work in both browser and Android WebView
      const wsHost = window.location.hostname || '127.0.0.1'
      this.ws = new WebSocket(`ws://${wsHost}:${this.port}`)

      this.ws.onopen = () => {
        this.reconnectAttempt = 0
      }

      this.ws.onmessage = (event) => {
        this.handleMessage(event.data)
      }

      this.ws.onerror = () => {
        this.callbacks.onError('WebSocket error')
      }

      this.ws.onclose = () => {
        this.callbacks.onConnectionChange('disconnected')
        this.scheduleReconnect()
      }
    } catch {
      this.callbacks.onConnectionChange('error')
      this.scheduleReconnect()
    }
  }

  disconnect() {
    this.shouldReconnect = false
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    if (this.ws) {
      this.ws.close()
      this.ws = null
    }
  }

  private scheduleReconnect() {
    if (!this.shouldReconnect || this.reconnectAttempt >= this.maxReconnectAttempt) {
      this.callbacks.onConnectionChange('error')
      return
    }

    const delay = Math.min(1000 * Math.pow(2, this.reconnectAttempt), 30000)
    this.reconnectAttempt++

    this.reconnectTimer = setTimeout(() => {
      this.connect()
    }, delay)
  }

  private handleMessage(data: string) {
    try {
      const msg = JSON.parse(data)
      const type = msg.type || ''

      // Handle events: {"type": "event", "event": "...", "payload": {...}}
      if (type === 'event' || msg.event) {
        this.handleEvent(msg)
        return
      }

      // Handle responses: {"type": "res", "id": "...", "ok": true, "payload": {...}}
      if ((type === 'res' || msg.id) && this.pendingRequests.has(msg.id)) {
        const pending = this.pendingRequests.get(msg.id)!
        this.pendingRequests.delete(msg.id)

        if (msg.ok) {
          pending.resolve(msg.payload ?? msg.result)
        } else {
          const err = msg.error
          const errMsg = typeof err === 'object' ? (err?.message || JSON.stringify(err)) : String(err || 'Request failed')
          pending.reject(new Error(errMsg))
        }
      }
    } catch {
      this.callbacks.onError('Failed to parse message')
    }
  }

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private handleEvent(msg: any) {
    const event = msg.event || ''
    const payload = msg.payload || msg.data || {}

    switch (event) {
      case 'connect.challenge':
        this.sendConnectRequest()
        break

      case 'chat': {
        // payload.state: "delta" | "final"
        // payload.message: { role, content: [{type:"text", text:"..."}] }
        const state = payload.state as 'delta' | 'final' | undefined
        const message = payload.message
        const content = message?.content || payload.content
        const sessionKey = payload.sessionKey || this.lastSessionKey

        if (content) {
          // Normalize content to MessageContent[]
          const normalized: MessageContent[] = Array.isArray(content)
            ? content.map((c: any) => ({ type: c.type || 'text', text: c.text, url: c.url }))
            : [{ type: 'text', text: String(content) }]

          this.callbacks.onChatEvent(
            sessionKey || 'main',
            state || 'final',
            normalized
          )
        }
        break
      }

      case 'agent': {
        const kind = payload.kind as 'thinking' | 'tool_use' | 'done' | 'error' | undefined
        const sessionKey = payload.sessionKey || this.lastSessionKey
        if (kind) {
          this.callbacks.onAgentEvent(sessionKey || 'main', {
            kind,
            data: payload,
          })
        }
        break
      }
    }
  }

  private sendConnectRequest() {
    const version = (window as unknown as Record<string, string>).__LILCLAW_VERSION || '0.0.0'
    this.sendRequest('connect', {
      minProtocol: 3,
      maxProtocol: 3,
      client: {
        id: 'openclaw-control-ui',
        version,
        platform: 'android',
        mode: 'webchat',
      },
      role: 'operator',
      scopes: ['operator.read', 'operator.write', 'operator.admin'],
      caps: [],
      auth: { token: this.token },
      locale: 'en-US',
      userAgent: `lilclaw-chat/${version}`,
    }).then(() => {
      this.callbacks.onConnectionChange('connected')
    }).catch((err) => {
      this.callbacks.onError(err.message)
      this.callbacks.onConnectionChange('error')
    })
  }

  private sendRequest(method: string, params: unknown): Promise<unknown> {
    return new Promise((resolve, reject) => {
      if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
        reject(new Error('Not connected'))
        return
      }

      const id = `req_${++this.requestId}`
      this.pendingRequests.set(id, { resolve, reject })

      this.ws.send(JSON.stringify({
        type: 'req',
        id,
        method,
        params,
      }))

      setTimeout(() => {
        if (this.pendingRequests.has(id)) {
          this.pendingRequests.delete(id)
          reject(new Error('Request timeout'))
        }
      }, 30000)
    })
  }

  async sendMessage(sessionKey: string, message: string, attachments?: Array<{mimeType: string; content: string}>): Promise<void> {
    this.lastSessionKey = sessionKey
    const idempotencyKey = `msg_${Date.now()}_${Math.random().toString(36).slice(2)}`
    const params: Record<string, unknown> = {
      sessionKey,
      message,
      idempotencyKey,
    }
    if (attachments && attachments.length > 0) {
      params.attachments = attachments
    }
    await this.sendRequest('chat.send', params)
  }

  async loadHistory(sessionKey: string, limit = 50): Promise<void> {
    this.lastSessionKey = sessionKey
    const result = await this.sendRequest('chat.history', {
      sessionKey,
      limit,
    }) as { messages?: Array<{ role: string; content: unknown }> } | undefined

    if (result?.messages) {
      const parsed: ChatMessage[] = result.messages
        .filter((m) => m.role === 'user' || m.role === 'assistant')
        .map((m) => {
          // Normalize content - could be string or [{type,text}]
          let content: MessageContent[]
          if (Array.isArray(m.content)) {
            content = m.content.map((c: any) => ({
              type: c.type || 'text',
              text: c.text,
              url: c.url,
            }))
          } else if (typeof m.content === 'string') {
            content = [{ type: 'text', text: m.content }]
          } else {
            content = [{ type: 'text', text: String(m.content || '') }]
          }

          // Strip gateway metadata from user messages
          // Format: "Conversation info (untrusted metadata):\n{...json...}\n[timestamp] actual message"
          if (m.role === 'user') {
            content = content.map((c) => {
              if (c.type !== 'text' || !c.text) return c
              let text = c.text
              // Strip "Conversation info..." header + JSON block + timestamp prefix
              const timestampMatch = text.match(/\[(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)\s+\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}\s+\w+\]\s*/)
              if (timestampMatch && text.includes('Conversation info')) {
                const idx = text.lastIndexOf(timestampMatch[0])
                text = text.slice(idx + timestampMatch[0].length)
              }
              return { ...c, text: text.trim() }
            }).filter((c) => c.text) // Remove empty content blocks
          }

          return {
            role: m.role as 'user' | 'assistant',
            content,
          }
        })
        .filter((m) => m.content.length > 0) // Remove messages that became empty after stripping

      // Filter out heartbeat exchanges and silent replies (system noise, not user conversation)
      const filtered = parsed.filter((m) => {
        const text = m.content.map((c) => c.text || '').join(' ')
        if (m.role === 'user' && /HEARTBEAT\.md|heartbeat prompt/i.test(text)) return false
        if (m.role === 'assistant' && /^\s*(HEARTBEAT_OK|NO_REPLY)\s*$/.test(text)) return false
        return true
      })
      this.callbacks.onHistoryLoaded(sessionKey, filtered)
    }
  }

  async abortChat(sessionKey: string): Promise<void> {
    await this.sendRequest('chat.abort', { sessionKey })
  }

  async listSessions(): Promise<void> {
    const result = await this.sendRequest('sessions.list', {}) as { sessions?: SessionInfo[] } | undefined
    if (result?.sessions) {
      this.callbacks.onSessionsLoaded(result.sessions)
    }
  }

  async patchSession(key: string, patch: { label?: string }): Promise<void> {
    await this.sendRequest('sessions.patch', { key, ...patch })
  }
}
