import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import {
  GatewayClient,
  type ConnectionState,
  type ChatMessage,
  type MessageContent,
  type AgentEvent,
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
  sessions: string[]

  // Messages
  messages: Record<string, ChatMessage[]>
  streaming: Record<string, StreamingContent>
  agentState: Record<string, AgentEvent | null>

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

  setShowDrawer: (show: boolean) => void
  setShowSettings: (show: boolean) => void
  setTheme: (theme: 'system' | 'light' | 'dark') => void
  updateSettings: (port: number, token: string) => void
}

export const useStore = create<AppState>()(
  persist(
    (set, get) => {
      let client: GatewayClient | null = null

      const initClient = () => {
        const state = get()
        client = new GatewayClient(
          {
            onConnectionChange: (connectionState) => {
              set({ connectionState })
              if (connectionState === 'connected') {
                get().loadHistory()
              }
            },
            onChatEvent: (sessionKey, eventState, content) => {
              if (eventState === 'delta') {
                set((state) => ({
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
                  }
                })
              }
            },
            onAgentEvent: (sessionKey, event) => {
              set((state) => ({
                agentState: {
                  ...state.agentState,
                  [sessionKey]: event,
                },
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
        sessions: ['main'],
        messages: {},
        streaming: {},
        agentState: {},
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
          }))

          await client?.sendMessage(currentSessionKey, message)
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
            sessions: [...new Set([...state.sessions, sessionKey])],
            currentSessionKey: sessionKey,
            showDrawer: false,
          }))
        },

        loadHistory: async () => {
          const { currentSessionKey } = get()
          await client?.loadHistory(currentSessionKey)
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
      }
    },
    {
      name: 'lilclaw-chat-storage',
      partialize: (state) => ({
        gatewayPort: state.gatewayPort,
        authToken: state.authToken,
        currentSessionKey: state.currentSessionKey,
        sessions: state.sessions,
        theme: state.theme,
      }),
    }
  )
)

// Initialize theme on load
if (typeof window !== 'undefined') {
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
      // Ignore parse errors
    }
  } else {
    const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
    document.documentElement.classList.toggle('dark', prefersDark)
  }
}
