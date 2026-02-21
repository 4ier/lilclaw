import { useState, useRef, useEffect, useCallback, type FormEvent } from 'react'
import { useStore } from '../store'
import MessageBubble from './MessageBubble'

function ConnectionIndicator() {
  const { connectionState } = useStore()

  if (connectionState === 'connected') {
    return (
      <div className="flex items-center gap-1 text-[11px] text-gray-400 dark:text-gray-500">
        <span className="w-1.5 h-1.5 rounded-full bg-emerald-500" />
      </div>
    )
  }

  const config: Record<string, { color: string; label: string }> = {
    connecting: { color: 'bg-amber-500 animate-pulse', label: 'Connecting...' },
    disconnected: { color: 'bg-gray-400', label: 'Offline' },
    error: { color: 'bg-red-500', label: 'Error' },
  }

  const { color, label } = config[connectionState] || config.disconnected

  return (
    <div className="flex items-center gap-1.5 text-[11px] text-gray-500 dark:text-gray-400">
      <span className={`w-1.5 h-1.5 rounded-full ${color}`} />
      <span>{label}</span>
    </div>
  )
}

function AgentStatus() {
  const { agentState, currentSessionKey } = useStore()
  const state = agentState[currentSessionKey]

  if (!state) return null

  const labels: Record<string, string> = {
    thinking: 'Thinking...',
    tool_use: 'Using tools...',
    done: '',
    error: 'Error occurred',
  }

  if (!labels[state.kind]) return null

  return (
    <div className="flex items-center gap-2 px-4 py-2 text-sm text-gray-600 dark:text-gray-400 bg-gray-50 dark:bg-gray-800">
      <svg className="w-4 h-4 animate-spin text-amber-700 dark:text-amber-500" viewBox="0 0 24 24" fill="none">
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
    typing,
    currentSessionKey,
    connectionState,
    sendMessage,
    setShowDrawer,
    setShowSettings,
    getSessionDisplayName,
  } = useStore()

  const [input, setInput] = useState('')
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLTextAreaElement>(null)

  const currentMessages = messages[currentSessionKey] || []
  const currentStreaming = streaming[currentSessionKey]
  const isTyping = typing[currentSessionKey] || false
  const rawDisplayName = getSessionDisplayName(currentSessionKey)
  const displayName = rawDisplayName === 'main' ? 'LilClaw' : rawDisplayName

  // Auto-scroll to bottom on new messages
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [currentMessages, currentStreaming, isTyping])

  // Scroll to bottom when virtual keyboard opens/closes (viewport resize)
  useEffect(() => {
    const vv = window.visualViewport
    if (!vv) return
    const handleResize = () => {
      setTimeout(() => {
        messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
      }, 100)
    }
    vv.addEventListener('resize', handleResize)
    return () => vv.removeEventListener('resize', handleResize)
  }, [])

  // Focus input on mount
  useEffect(() => {
    inputRef.current?.focus()
  }, [])

  const handleSubmit = useCallback((e: FormEvent) => {
    e.preventDefault()
    const trimmed = input.trim()
    if (!trimmed || connectionState !== 'connected') return

    // Clear input synchronously — do NOT await sendMessage
    // This keeps focus on the textarea and prevents keyboard dismiss
    setInput('')
    sendMessage(trimmed)

    // No focus() call needed — textarea never lost focus
  }, [input, connectionState, sendMessage])

  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSubmit(e)
    }
  }, [handleSubmit])

  return (
    <div className="flex flex-col h-full">
      {/* Top bar — no backdrop-blur for GPU perf on Android */}
      <header className="flex items-center justify-between px-4 py-2.5 border-b border-gray-100 dark:border-gray-800 safe-top bg-white dark:bg-[#1a1410]">
        <button
          onClick={() => setShowDrawer(true)}
          className="flex items-center justify-center -ml-2 p-2 rounded-xl active:bg-gray-100 dark:active:bg-gray-800"
          aria-label="Open sessions"
        >
          <svg className="w-5 h-5 text-gray-600 dark:text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M4 6h16M4 12h16M4 18h16" />
          </svg>
        </button>

        <div className="flex flex-col items-center">
          <h1 className="font-semibold text-[15px] text-gray-900 dark:text-white truncate max-w-[200px]">
            {displayName}
          </h1>
          <ConnectionIndicator />
        </div>

        <button
          onClick={() => setShowSettings(true)}
          className="flex items-center justify-center -mr-2 p-2 rounded-xl active:bg-gray-100 dark:active:bg-gray-800"
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
          <div className="flex flex-col items-center justify-center h-full text-gray-400 dark:text-gray-500">
            <svg className="w-14 h-14 mb-3 opacity-40" viewBox="0 0 100 100" fill="currentColor">
              <ellipse cx="30" cy="28" rx="10" ry="12" />
              <ellipse cx="50" cy="22" rx="10" ry="12" />
              <ellipse cx="70" cy="28" rx="10" ry="12" />
              <ellipse cx="50" cy="55" rx="18" ry="20" />
            </svg>
            <p className="text-base font-medium text-gray-500 dark:text-gray-400">What's on your mind?</p>
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

        {isTyping && !currentStreaming?.isStreaming && (
          <div className="flex justify-start">
            <div className="message-bubble message-bubble-assistant">
              <div className="flex items-center gap-1 py-1 px-0.5">
                <span className="w-2 h-2 rounded-full bg-amber-600/60 dark:bg-amber-500/60 animate-bounce [animation-delay:0ms]" />
                <span className="w-2 h-2 rounded-full bg-amber-600/60 dark:bg-amber-500/60 animate-bounce [animation-delay:150ms]" />
                <span className="w-2 h-2 rounded-full bg-amber-600/60 dark:bg-amber-500/60 animate-bounce [animation-delay:300ms]" />
              </div>
            </div>
          </div>
        )}

        <div ref={messagesEndRef} />
      </div>

      {/* Agent status */}
      <AgentStatus />

      {/* Input bar — no backdrop-blur for GPU perf on Android */}
      <form
        onSubmit={handleSubmit}
        className="flex items-end gap-2 px-3 py-2.5 border-t border-gray-100 dark:border-gray-800 safe-bottom bg-white dark:bg-[#1a1410]"
      >
        <textarea
          ref={inputRef}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          onFocus={() => {
            setTimeout(() => messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' }), 300)
          }}
          placeholder="Message..."
          rows={1}
          className="flex-1 resize-none px-3.5 py-2.5 rounded-[20px] border border-gray-200 dark:border-gray-700 bg-gray-50 dark:bg-[#231c14] text-gray-900 dark:text-white placeholder-gray-400 dark:placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-amber-700/40 dark:focus:ring-amber-600/40 focus:border-amber-400 dark:focus:border-amber-700 text-[15px]"
          style={{ maxHeight: '120px' }}
          disabled={connectionState !== 'connected'}
        />

        <button
          type="submit"
          disabled={!input.trim() || connectionState !== 'connected'}
          // Prevent button from stealing focus from textarea → keeps keyboard open
          onTouchStart={(e) => e.preventDefault()}
          onMouseDown={(e) => e.preventDefault()}
          className="flex items-center justify-center p-2.5 rounded-full bg-amber-800 text-white disabled:opacity-30 active:bg-amber-950 active:scale-95"
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
