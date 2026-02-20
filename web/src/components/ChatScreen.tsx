import { useState, useRef, useEffect, type FormEvent } from 'react'
import { useStore } from '../store'
import MessageBubble from './MessageBubble'

function ConnectionIndicator() {
  const { connectionState } = useStore()

  const colors = {
    connected: 'bg-green-500',
    connecting: 'bg-yellow-500 animate-pulse',
    disconnected: 'bg-gray-400',
    error: 'bg-red-500',
  }

  const labels = {
    connected: 'Connected',
    connecting: 'Connecting...',
    disconnected: 'Disconnected',
    error: 'Error',
  }

  return (
    <div className="flex items-center gap-1.5 text-[11px] text-gray-500 dark:text-gray-400">
      <span className={`w-1.5 h-1.5 rounded-full ${colors[connectionState]}`} />
      <span>{labels[connectionState]}</span>
    </div>
  )
}

function AgentStatus() {
  const { agentState, currentSessionKey } = useStore()
  const state = agentState[currentSessionKey]

  if (!state) return null

  const labels = {
    thinking: 'Thinking...',
    tool_use: 'Using tools...',
    done: '',
    error: 'Error occurred',
  }

  if (!labels[state.kind]) return null

  return (
    <div className="flex items-center gap-2 px-4 py-2 text-sm text-gray-600 dark:text-gray-400 bg-gray-50 dark:bg-gray-800">
      <svg
        className="w-4 h-4 animate-spin"
        viewBox="0 0 24 24"
        fill="none"
      >
        <circle
          className="opacity-25"
          cx="12"
          cy="12"
          r="10"
          stroke="currentColor"
          strokeWidth="4"
        />
        <path
          className="opacity-75"
          fill="currentColor"
          d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
        />
      </svg>
      <span>{labels[state.kind]}</span>
    </div>
  )
}

export default function ChatScreen() {
  const {
    messages,
    streaming,
    currentSessionKey,
    connectionState,
    sendMessage,
    setShowDrawer,
    setShowSettings,
  } = useStore()

  const [input, setInput] = useState('')
  const [isSending, setIsSending] = useState(false)
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLTextAreaElement>(null)

  const currentMessages = messages[currentSessionKey] || []
  const currentStreaming = streaming[currentSessionKey]

  // Auto-scroll to bottom on new messages
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [currentMessages, currentStreaming])

  // Focus input on mount
  useEffect(() => {
    inputRef.current?.focus()
  }, [])

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    const trimmed = input.trim()
    if (!trimmed || isSending || connectionState !== 'connected') return

    setIsSending(true)
    setInput('')

    try {
      await sendMessage(trimmed)
    } finally {
      setIsSending(false)
      inputRef.current?.focus()
    }
  }

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSubmit(e)
    }
  }

  return (
    <div className="flex flex-col h-full">
      {/* Top bar */}
      <header className="flex items-center justify-between px-4 py-2.5 border-b border-gray-100 dark:border-gray-800 safe-top bg-white/80 dark:bg-[#0f0f0f]/80 backdrop-blur-lg">
        <button
          onClick={() => setShowDrawer(true)}
          className="touch-target flex items-center justify-center -ml-2 p-2 rounded-xl hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors active:scale-95"
          aria-label="Open sessions"
        >
          <svg className="w-5 h-5 text-gray-600 dark:text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M4 6h16M4 12h16M4 18h16" />
          </svg>
        </button>

        <div className="flex flex-col items-center">
          <h1 className="font-semibold text-[15px] text-gray-900 dark:text-white">
            {currentSessionKey}
          </h1>
          <ConnectionIndicator />
        </div>

        <button
          onClick={() => setShowSettings(true)}
          className="touch-target flex items-center justify-center -mr-2 p-2 rounded-xl hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors active:scale-95"
          aria-label="Settings"
        >
          <svg className="w-5 h-5 text-gray-600 dark:text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.8}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
            <path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
          </svg>
        </button>
      </header>

      {/* Messages */}
      <div className="flex-1 overflow-y-auto px-4 py-4 space-y-4">
        {currentMessages.length === 0 && !currentStreaming?.isStreaming && (
          <div className="flex flex-col items-center justify-center h-full text-gray-500 dark:text-gray-400">
            <svg className="w-16 h-16 mb-4 opacity-50" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={1.5}
                d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z"
              />
            </svg>
            <p className="text-lg font-medium">Start a conversation</p>
            <p className="text-sm">Send a message to begin</p>
          </div>
        )}

        {currentMessages.map((msg, i) => (
          <MessageBubble
            key={i}
            role={msg.role}
            content={msg.content}
          />
        ))}

        {currentStreaming?.isStreaming && currentStreaming.content.length > 0 && (
          <MessageBubble
            role="assistant"
            content={currentStreaming.content}
            isStreaming
          />
        )}

        <div ref={messagesEndRef} />
      </div>

      {/* Agent status */}
      <AgentStatus />

      {/* Input bar */}
      <form
        onSubmit={handleSubmit}
        className="flex items-end gap-2 px-3 py-2.5 border-t border-gray-100 dark:border-gray-800 safe-bottom bg-white/80 dark:bg-[#0f0f0f]/80 backdrop-blur-lg"
      >
        <textarea
          ref={inputRef}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="Message..."
          rows={1}
          className="flex-1 resize-none px-3.5 py-2.5 rounded-[20px] border border-gray-200 dark:border-gray-700 bg-gray-50 dark:bg-[#1a1a1a] text-gray-900 dark:text-white placeholder-gray-400 dark:placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-indigo-500/40 dark:focus:ring-indigo-400/40 focus:border-indigo-300 dark:focus:border-indigo-600 transition-all text-[15px]"
          style={{ maxHeight: '120px' }}
          disabled={connectionState !== 'connected'}
        />

        <button
          type="submit"
          disabled={!input.trim() || isSending || connectionState !== 'connected'}
          className="touch-target flex items-center justify-center p-2.5 rounded-full bg-indigo-600 text-white disabled:opacity-30 disabled:cursor-not-allowed hover:bg-indigo-700 active:bg-indigo-800 active:scale-95 transition-all"
          aria-label="Send message"
        >
          <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2.2}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M12 19l9 2-9-18-9 18 9-2zm0 0v-8" />
          </svg>
        </button>
      </form>
    </div>
  )
}
