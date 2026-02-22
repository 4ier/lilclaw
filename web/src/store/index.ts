import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import {
  GatewayClient,
  type ConnectionState,
  type ChatMessage,
  type MessageContent,
  type AgentEvent,
  type SessionInfo,
} from '../lib/gateway'
import { saveMessages, loadAllMessages, deleteSessionMessages } from '../lib/messageDb'

interface StreamingContent {
  content: MessageContent[]
  isStreaming: boolean
}

interface AppState {
  // Connection
  connectionState: ConnectionState
  gatewayPort: number
  authToken: string

  // Sessions
  currentSessionKey: string
  sessions: SessionInfo[]

  // Messages
  messages: Record<string, ChatMessage[]>
  streaming: Record<string, StreamingContent>
  agentState: Record<string, AgentEvent | null>

  // Typing: user sent message, waiting for first response
  typing: Record<string, boolean>

  // Offline message queue
  pendingMessages: Array<{ sessionKey: string; message: string; timestamp: number }>

  // UI
  showDrawer: boolean
  showSettings: boolean
  theme: 'system' | 'light' | 'dark'
  cacheLoaded: boolean

  // Client
  client: GatewayClient | null

  // Actions
  connect: () => void
  disconnect: () => void
  sendMessage: (message: string) => Promise<void>
  abortChat: () => Promise<void>
  switchSession: (sessionKey: string) => void
  createSession: (sessionKey: string) => void
  loadHistory: () => Promise<void>
  loadSessions: () => Promise<void>
  renameSession: (key: string, label: string) => Promise<void>
  deleteMessage: (sessionKey: string, index: number) => void
  retryLastMessage: () => void
  deleteSession: (sessionKey: string) => void
  loadCachedMessages: () => Promise<void>
  flushPendingMessages: () => Promise<void>

  setShowDrawer: (show: boolean) => void
  setShowSettings: (show: boolean) => void
  setTheme: (theme: 'system' | 'light' | 'dark') => void
  updateSettings: (port: number, token: string) => void

  // Helpers
  getSessionDisplayName: (key: string) => string
  isGenerating: (sessionKey?: string) => boolean
}

// Persist messages to IndexedDB (debounced)
const persistTimers = new Map<string, ReturnType<typeof setTimeout>>()
function debouncedPersist(sessionKey: string, messages: ChatMessage[]) {
  const existing = persistTimers.get(sessionKey)
  if (existing) clearTimeout(existing)
  persistTimers.set(sessionKey, setTimeout(() => {
    saveMessages(sessionKey, messages)
    persistTimers.delete(sessionKey)
  }, 500))
}

export const useStore = create<AppState>()(
  persist(
    (set, get) => {
      let client: GatewayClient | null = null

      const normalizeSessionKey = (key: string): string => {
        const match = key.match(/^agent:[^:]+:(.+)$/)
        return match ? match[1] : key
      }

      const initClient = () => {
        const state = get()
        client = new GatewayClient(
          {
            onConnectionChange: (connectionState) => {
              set({ connectionState })
              if (connectionState === 'connected') {
                get().loadSessions()
                get().loadHistory()
                // Flush any messages queued while offline
                get().flushPendingMessages()
              }
            },
            onChatEvent: (rawSessionKey, eventState, content) => {
              const sessionKey = normalizeSessionKey(rawSessionKey)
              if (eventState === 'delta') {
                set((state) => ({
                  typing: { ...state.typing, [sessionKey]: false },
                  streaming: {
                    ...state.streaming,
                    [sessionKey]: { content, isStreaming: true },
                  },
                }))
              } else {
                // Final message
                set((state) => {
                  const existingMessages = state.messages[sessionKey] || []
                  const newMessages = [
                    ...existingMessages,
                    { role: 'assistant' as const, content, timestamp: Date.now() },
                  ]
                  debouncedPersist(sessionKey, newMessages)
                  return {
                    messages: { ...state.messages, [sessionKey]: newMessages },
                    streaming: {
                      ...state.streaming,
                      [sessionKey]: { content: [], isStreaming: false },
                    },
                    agentState: { ...state.agentState, [sessionKey]: null },
                    typing: { ...state.typing, [sessionKey]: false },
                  }
                })
              }
            },
            onAgentEvent: (rawSessionKey, event) => {
              const sessionKey = normalizeSessionKey(rawSessionKey)
              set((state) => ({
                agentState: { ...state.agentState, [sessionKey]: event },
                typing: { ...state.typing, [sessionKey]: false },
              }))
            },
            onHistoryLoaded: (sessionKey, messages) => {
              // Server history replaces cached messages
              debouncedPersist(sessionKey, messages)
              set((state) => ({
                messages: { ...state.messages, [sessionKey]: messages },
              }))
            },
            onSessionsLoaded: (sessions) => {
              set({ sessions })
            },
            onError: (error) => {
              console.error('Gateway error:', error)
            },
          },
          state.gatewayPort,
          state.authToken
        )
        set({ client })
      }

      return {
        // Initial state
        connectionState: 'disconnected',
        gatewayPort: 3000,
        authToken: 'lilclaw-local',
        currentSessionKey: 'main',
        sessions: [],
        messages: {},
        streaming: {},
        agentState: {},
        typing: {},
        pendingMessages: [],
        showDrawer: false,
        showSettings: false,
        theme: 'system',
        cacheLoaded: false,
        client: null,

        // Load cached messages from IndexedDB (call on app init)
        loadCachedMessages: async () => {
          const cached = await loadAllMessages()
          if (Object.keys(cached).length > 0) {
            set((state) => {
              // Only set cached messages for sessions that don't already have server data
              const merged = { ...cached }
              for (const key of Object.keys(state.messages)) {
                if (state.messages[key].length > 0) {
                  merged[key] = state.messages[key]
                }
              }
              return { messages: merged, cacheLoaded: true }
            })
          } else {
            set({ cacheLoaded: true })
          }
        },

        connect: () => {
          if (!client) initClient()
          client?.connect()
        },

        disconnect: () => {
          client?.disconnect()
        },

        sendMessage: async (message: string) => {
          const { currentSessionKey, connectionState } = get()

          const userMessage: ChatMessage = {
            role: 'user',
            content: [{ type: 'text', text: message }],
            timestamp: Date.now(),
          }

          // Always add message to local state immediately (optimistic)
          set((state) => {
            const newMessages = [
              ...(state.messages[currentSessionKey] || []),
              userMessage,
            ]
            debouncedPersist(currentSessionKey, newMessages)
            return {
              messages: { ...state.messages, [currentSessionKey]: newMessages },
              typing: { ...state.typing, [currentSessionKey]: connectionState === 'connected' },
              sessions: state.sessions.map((s) =>
                s.key === currentSessionKey ? { ...s, lastActivity: Date.now() } : s
              ),
            }
          })

          if (connectionState === 'connected') {
            // Online: send immediately
            try {
              await client?.sendMessage(currentSessionKey, message)
            } catch {
              // Failed to send — queue it
              set((state) => ({
                pendingMessages: [...state.pendingMessages, { sessionKey: currentSessionKey, message, timestamp: Date.now() }],
                typing: { ...state.typing, [currentSessionKey]: false },
              }))
              return
            }
          } else {
            // Offline: queue for later
            set((state) => ({
              pendingMessages: [...state.pendingMessages, { sessionKey: currentSessionKey, message, timestamp: Date.now() }],
            }))
            return
          }

          // Auto-name session: if first user message and no label yet
          const state = get()
          const sessionMessages = state.messages[currentSessionKey] || []
          const userMessages = sessionMessages.filter((m) => m.role === 'user')
          const sessionInfo = state.sessions.find((s) => s.key === currentSessionKey)
          if (userMessages.length === 1 && !sessionInfo?.label) {
            const label = message.length > 30 ? message.slice(0, 30) + '…' : message
            set((state) => ({
              sessions: state.sessions.map((s) =>
                s.key === currentSessionKey ? { ...s, label } : s
              ),
            }))
            client?.patchSession(currentSessionKey, { label }).catch(() => {})
          }
        },

        abortChat: async () => {
          const { currentSessionKey } = get()
          await client?.abortChat(currentSessionKey)
          set((state) => ({
            streaming: {
              ...state.streaming,
              [currentSessionKey]: { content: [], isStreaming: false },
            },
            typing: { ...state.typing, [currentSessionKey]: false },
            agentState: { ...state.agentState, [currentSessionKey]: null },
          }))
        },

        switchSession: (sessionKey: string) => {
          set({ currentSessionKey: sessionKey, showDrawer: false })
          get().loadHistory()
        },

        createSession: (sessionKey: string) => {
          set((state) => ({
            sessions: [...state.sessions.filter((s) => s.key !== sessionKey), { key: sessionKey, lastActivity: Date.now() }],
            currentSessionKey: sessionKey,
            showDrawer: false,
          }))
        },

        loadHistory: async () => {
          const { currentSessionKey } = get()
          await client?.loadHistory(currentSessionKey)
        },

        loadSessions: async () => {
          await client?.listSessions()
        },

        renameSession: async (key: string, label: string) => {
          await client?.patchSession(key, { label })
          await get().loadSessions()
        },

        deleteMessage: (sessionKey: string, index: number) => {
          set((state) => {
            const msgs = [...(state.messages[sessionKey] || [])]
            msgs.splice(index, 1)
            debouncedPersist(sessionKey, msgs)
            return { messages: { ...state.messages, [sessionKey]: msgs } }
          })
        },

        retryLastMessage: () => {
          const { currentSessionKey, messages } = get()
          const msgs = messages[currentSessionKey] || []

          // Find last user message
          let lastUserIdx = -1
          for (let i = msgs.length - 1; i >= 0; i--) {
            if (msgs[i].role === 'user') { lastUserIdx = i; break }
          }
          if (lastUserIdx === -1) return

          const lastUserMsg = msgs[lastUserIdx]
          const text = lastUserMsg.content
            .filter((c) => c.type === 'text' && c.text)
            .map((c) => c.text)
            .join('\n')

          if (!text) return

          // Remove messages after (and including) the last user message, then re-send
          const trimmed = msgs.slice(0, lastUserIdx)
          set((state) => ({
            messages: { ...state.messages, [currentSessionKey]: trimmed },
          }))

          // Re-send
          get().sendMessage(text)
        },

        deleteSession: (sessionKey: string) => {
          deleteSessionMessages(sessionKey)
          set((state) => {
            const newMessages = { ...state.messages }
            delete newMessages[sessionKey]
            const newSessions = state.sessions.filter((s) => s.key !== sessionKey)
            const needSwitch = state.currentSessionKey === sessionKey
            return {
              messages: newMessages,
              sessions: newSessions,
              pendingMessages: state.pendingMessages.filter((p) => p.sessionKey !== sessionKey),
              currentSessionKey: needSwitch ? (newSessions[0]?.key || 'main') : state.currentSessionKey,
            }
          })
        },

        flushPendingMessages: async () => {
          const { pendingMessages } = get()
          if (pendingMessages.length === 0) return

          // Take all pending and clear the queue
          set({ pendingMessages: [] })

          for (const pending of pendingMessages) {
            try {
              set((state) => ({
                typing: { ...state.typing, [pending.sessionKey]: true },
              }))
              await client?.sendMessage(pending.sessionKey, pending.message)

              // Auto-name if needed
              const state = get()
              const sessionMessages = state.messages[pending.sessionKey] || []
              const userMessages = sessionMessages.filter((m) => m.role === 'user')
              const sessionInfo = state.sessions.find((s) => s.key === pending.sessionKey)
              if (userMessages.length <= 1 && !sessionInfo?.label) {
                const label = pending.message.length > 30 ? pending.message.slice(0, 30) + '…' : pending.message
                set((state) => ({
                  sessions: state.sessions.map((s) =>
                    s.key === pending.sessionKey ? { ...s, label } : s
                  ),
                }))
                client?.patchSession(pending.sessionKey, { label }).catch(() => {})
              }
            } catch {
              // Re-queue failed messages
              set((state) => ({
                pendingMessages: [...state.pendingMessages, pending],
                typing: { ...state.typing, [pending.sessionKey]: false },
              }))
            }
          }
        },

        setShowDrawer: (show: boolean) => set({ showDrawer: show }),
        setShowSettings: (show: boolean) => set({ showSettings: show }),

        setTheme: (theme) => {
          set({ theme })
          if (theme === 'dark') {
            document.documentElement.classList.add('dark')
          } else if (theme === 'light') {
            document.documentElement.classList.remove('dark')
          } else {
            const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
            document.documentElement.classList.toggle('dark', prefersDark)
          }
        },

        updateSettings: (port: number, token: string) => {
          set({ gatewayPort: port, authToken: token })
          client?.updateConfig(port, token)
          client?.disconnect()
          client?.connect()
        },

        getSessionDisplayName: (key: string) => {
          const session = get().sessions.find((s) => s.key === key)
          const raw = session?.label?.trim() || session?.displayName?.trim() || key
          if (raw === 'main') return 'LilClaw'
          if (/^chat-\d+$/.test(raw)) return 'New Chat'
          return raw
        },

        isGenerating: (sessionKey?: string) => {
          const key = sessionKey || get().currentSessionKey
          const state = get()
          return !!(state.typing[key] || state.streaming[key]?.isStreaming)
        },
      }
    },
    {
      name: 'lilclaw-chat-storage',
      partialize: (state) => ({
        gatewayPort: state.gatewayPort,
        authToken: state.authToken,
        currentSessionKey: state.currentSessionKey,
        theme: state.theme,
      }),
    }
  )
)

// Initialize theme on load
if (typeof window !== 'undefined' && document.documentElement) {
  const stored = localStorage.getItem('lilclaw-chat-storage')
  if (stored) {
    try {
      const { state } = JSON.parse(stored)
      if (state?.theme === 'dark') {
        document.documentElement.classList.add('dark')
      } else if (state?.theme !== 'light') {
        const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
        document.documentElement.classList.toggle('dark', prefersDark)
      }
    } catch {
      // Ignore
    }
  } else {
    const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
    document.documentElement.classList.toggle('dark', prefersDark)
  }
}
