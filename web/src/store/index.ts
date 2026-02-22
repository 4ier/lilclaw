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

  // UI
  showDrawer: boolean
  showSettings: boolean
  theme: 'system' | 'light' | 'dark'

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

  setShowDrawer: (show: boolean) => void
  setShowSettings: (show: boolean) => void
  setTheme: (theme: 'system' | 'light' | 'dark') => void
  updateSettings: (port: number, token: string) => void

  // Helpers
  getSessionDisplayName: (key: string) => string
}

export const useStore = create<AppState>()(
  persist(
    (set, get) => {
      let client: GatewayClient | null = null

      // Gateway uses internal session keys like "agent:dev:main" or "agent:<id>:<key>"
      // Normalize to the user-facing key (strip "agent:<id>:" prefix)
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
                // Final message - add to messages and clear streaming
                set((state) => {
                  const existingMessages = state.messages[sessionKey] || []
                  return {
                    messages: {
                      ...state.messages,
                      [sessionKey]: [
                        ...existingMessages,
                        { role: 'assistant', content, timestamp: Date.now() },
                      ],
                    },
                    streaming: {
                      ...state.streaming,
                      [sessionKey]: { content: [], isStreaming: false },
                    },
                    agentState: {
                      ...state.agentState,
                      [sessionKey]: null,
                    },
                    typing: { ...state.typing, [sessionKey]: false },
                  }
                })
              }
            },
            onAgentEvent: (rawSessionKey, event) => {
              const sessionKey = normalizeSessionKey(rawSessionKey)
              set((state) => ({
                agentState: {
                  ...state.agentState,
                  [sessionKey]: event,
                },
                // Any agent event means we're past the "waiting" phase
                typing: { ...state.typing, [sessionKey]: false },
              }))
            },
            onHistoryLoaded: (sessionKey, messages) => {
              set((state) => ({
                messages: {
                  ...state.messages,
                  [sessionKey]: messages,
                },
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
        showDrawer: false,
        showSettings: false,
        theme: 'system',
        client: null,

        // Actions
        connect: () => {
          if (!client) {
            initClient()
          }
          client?.connect()
        },

        disconnect: () => {
          client?.disconnect()
        },

        sendMessage: async (message: string) => {
          const { currentSessionKey } = get()

          // Add user message immediately
          const userMessage: ChatMessage = {
            role: 'user',
            content: [{ type: 'text', text: message }],
            timestamp: Date.now(),
          }

          set((state) => ({
            messages: {
              ...state.messages,
              [currentSessionKey]: [
                ...(state.messages[currentSessionKey] || []),
                userMessage,
              ],
            },
            // Show typing indicator immediately
            typing: { ...state.typing, [currentSessionKey]: true },
          }))

          await client?.sendMessage(currentSessionKey, message)

          // Auto-name session: if this is the first user message and session has no label
          const state = get()
          const sessionMessages = state.messages[currentSessionKey] || []
          const userMessages = sessionMessages.filter((m) => m.role === 'user')
          const sessionInfo = state.sessions.find((s) => s.key === currentSessionKey)
          if (userMessages.length === 1 && !sessionInfo?.label) {
            // Take first 30 chars of the message as label
            const label = message.length > 30 ? message.slice(0, 30) + '…' : message
            client?.patchSession(currentSessionKey, { label }).then(() => {
              get().loadSessions()
            }).catch(() => {})
          }
        },

        abortChat: async () => {
          const { currentSessionKey } = get()
          await client?.abortChat(currentSessionKey)
        },

        switchSession: (sessionKey: string) => {
          set({ currentSessionKey: sessionKey, showDrawer: false })
          get().loadHistory()
        },

        createSession: (sessionKey: string) => {
          set((state) => ({
            sessions: [...state.sessions.filter((s) => s.key !== sessionKey), { key: sessionKey }],
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

        setShowDrawer: (show: boolean) => set({ showDrawer: show }),
        setShowSettings: (show: boolean) => set({ showSettings: show }),

        setTheme: (theme) => {
          set({ theme })
          if (theme === 'dark') {
            document.documentElement.classList.add('dark')
          } else if (theme === 'light') {
            document.documentElement.classList.remove('dark')
          } else {
            // "system" — prefer native Android signal, fall back to CSS media query
            const prefersDark = (window as any).__SYSTEM_DARK ??
              window.matchMedia('(prefers-color-scheme: dark)').matches
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

// Initialize theme on load + listen for system theme changes
if (typeof window !== 'undefined') {
  // Check URL param from Android WebView for initial system dark state
  const urlParams = new URLSearchParams(window.location.search)
  const darkParam = urlParams.get('dark')
  if (darkParam !== null) {
    ;(window as any).__SYSTEM_DARK = darkParam === '1'
  }

  const applyTheme = (theme: string) => {
    if (theme === 'dark') {
      document.documentElement.classList.add('dark')
    } else if (theme === 'light') {
      document.documentElement.classList.remove('dark')
    } else {
      // "system" — prefer native Android signal, fall back to CSS media query
      const prefersDark = (window as any).__SYSTEM_DARK ??
        window.matchMedia('(prefers-color-scheme: dark)').matches
      document.documentElement.classList.toggle('dark', prefersDark)
    }
  }

  const stored = localStorage.getItem('lilclaw-chat-storage')
  let initialTheme = 'system'
  if (stored) {
    try {
      const { state } = JSON.parse(stored)
      initialTheme = state?.theme || 'system'
    } catch {
      // Ignore parse errors
    }
  }
  applyTheme(initialTheme)

  // Listen for OS theme changes — only applies when theme is "system"
  // Standard: CSS media query change (works in browsers)
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    const currentTheme = useStore.getState().theme
    if (currentTheme === 'system') {
      applyTheme('system')
    }
  })
  // Android WebView: custom event injected by Kotlin when system dark mode changes
  window.addEventListener('systemthemechange', ((e: CustomEvent) => {
    const currentTheme = useStore.getState().theme
    if (currentTheme === 'system') {
      const isDark = e.detail?.dark ?? false
      document.documentElement.classList.toggle('dark', isDark)
    }
  }) as EventListener)
}
