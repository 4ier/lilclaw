import { useState, useRef, useEffect, useCallback, type FormEvent } from 'react'
import { useStore } from '../store'
import MessageBubble from './MessageBubble'

function ConnectionBanner() {
  const { connectionState, cacheLoaded } = useStore()

  if (connectionState === 'connected') return null

  const config: Record<string, { color: string; bg: string; label: string }> = {
    connecting: {
      color: 'text-amber-700 dark:text-amber-400',
      bg: 'bg-amber-50 dark:bg-amber-900/20',
      label: cacheLoaded ? 'Connecting to gateway...' : 'Loading...',
    },
    disconnected: {
      color: 'text-gray-500 dark:text-gray-400',
      bg: 'bg-gray-50 dark:bg-gray-800',
      label: 'Gateway offline',
    },
    error: {
      color: 'text-red-600 dark:text-red-400',
      bg: 'bg-red-50 dark:bg-red-900/20',
      label: 'Connection error — retrying...',
    },
  }

  const { color, bg, label } = config[connectionState] || config.disconnected

  return (
    <div className={`flex items-center justify-center gap-2 px-4 py-1.5 text-[12px] ${color} ${bg} flex-shrink-0`}>
      {connectionState === 'connecting' && (
        <svg className="w-3 h-3 animate-spin" viewBox="0 0 24 24" fill="none">
          <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
          <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
        </svg>
      )}
      <span>{label}</span>
    </div>
  )
}

function ConnectionDot() {
  const { connectionState } = useStore()
  if (connectionState === 'connected') {
    return <span className="w-1.5 h-1.5 rounded-full bg-emerald-500" />
  }
  const colors: Record<string, string> = {
    connecting: 'bg-amber-500 animate-pulse',
    disconnected: 'bg-gray-400',
    error: 'bg-red-500',
  }
  return <span className={`w-1.5 h-1.5 rounded-full ${colors[connectionState] || 'bg-gray-400'}`} />
}

const TOOL_DISPLAY: Record<string, { icon: string; label: string }> = {
  exec: { icon: '💻', label: 'Running command...' },
  web_search: { icon: '🔍', label: 'Searching web...' },
  web_fetch: { icon: '🌐', label: 'Fetching page...' },
  browser: { icon: '🌐', label: 'Using browser...' },
  read: { icon: '📄', label: 'Reading file...' },
  write: { icon: '📝', label: 'Writing file...' },
  edit: { icon: '✏️', label: 'Editing file...' },
  memory_search: { icon: '🧠', label: 'Searching memory...' },
  message: { icon: '💬', label: 'Sending message...' },
  tts: { icon: '🔊', label: 'Generating speech...' },
}

function AgentStatus() {
  const { agentState, currentSessionKey } = useStore()
  const state = agentState[currentSessionKey]
  if (!state || state.kind === 'done') return null

  // Extract tool name from event data
  const data = state.data as Record<string, unknown> | undefined
  const toolName = (data?.tool || data?.name || '') as string

  let icon = '⚙️'
  let label = 'Thinking...'

  if (state.kind === 'tool_use') {
    const display = TOOL_DISPLAY[toolName]
    if (display) {
      icon = display.icon
      label = display.label
    } else if (toolName) {
      label = `Using ${toolName}...`
    } else {
      label = 'Using tools...'
    }
  } else if (state.kind === 'thinking') {
    icon = '💭'
    label = 'Thinking...'
  } else if (state.kind === 'error') {
    icon = '⚠️'
    label = 'Error occurred'
  }

  return (
    <div className="flex items-center gap-2 px-4 py-2 text-sm text-gray-600 dark:text-gray-400 bg-gray-50/80 dark:bg-[#231c14]/80 flex-shrink-0 animate-fade-in">
      <span className="text-base animate-pulse">{icon}</span>
      <span>{label}</span>
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
    abortChat,
    retryLastMessage,
    setShowDrawer,
    setShowSettings,
    getSessionDisplayName,
    isGenerating,
  } = useStore()

  const [input, setInput] = useState('')
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const messagesContainerRef = useRef<HTMLDivElement>(null)
  const wasAtBottomRef = useRef(true)
  const prevMessageCountRef = useRef(0)

  const currentMessages = messages[currentSessionKey] || []
  const currentStreaming = streaming[currentSessionKey]
  const isTyping = typing[currentSessionKey] || false
  const displayName = getSessionDisplayName(currentSessionKey)
  const generating = isGenerating()

  // Auto-scroll to bottom on new messages / streaming updates.
  // Uses 'instant' during streaming to avoid jitter from competing smooth scrolls.
  // Only auto-scrolls if user was already near the bottom (not scrolled up to read history).
  useEffect(() => {
    if (!wasAtBottomRef.current) return
    const isStreaming = currentStreaming?.isStreaming
    messagesEndRef.current?.scrollIntoView({ behavior: isStreaming ? 'instant' : 'smooth' })
  }, [currentMessages, currentStreaming, isTyping])

  // Track new messages for animation
  useEffect(() => {
    prevMessageCountRef.current = currentMessages.length
  }, [currentMessages.length])

  // Scroll on container resize (keyboard open/close)
  useEffect(() => {
    const container = messagesContainerRef.current
    if (!container) return
    const ro = new ResizeObserver(() => {
      if (wasAtBottomRef.current) {
        messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
      }
    })
    ro.observe(container)
    return () => ro.disconnect()
  }, [])

  const handleScroll = useCallback(() => {
    const el = messagesContainerRef.current
    if (!el) return
    wasAtBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 60
  }, [])

  // Focus input on mount
  useEffect(() => {
    textareaRef.current?.focus()
  }, [])

  // Auto-grow textarea
  const adjustTextareaHeight = useCallback(() => {
    const el = textareaRef.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${Math.min(el.scrollHeight, 120)}px`
  }, [])

  useEffect(() => {
    adjustTextareaHeight()
  }, [input, adjustTextareaHeight])

  const handleSubmit = useCallback((e: FormEvent) => {
    e.preventDefault()
    const trimmed = input.trim()
    if (!trimmed || connectionState !== 'connected') return
    setInput('')
    // Reset textarea height
    if (textareaRef.current) textareaRef.current.style.height = 'auto'
    sendMessage(trimmed)
  }, [input, connectionState, sendMessage])

  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSubmit(e)
    }
  }, [handleSubmit])

  const handleAbort = useCallback(() => {
    abortChat()
  }, [abortChat])

  // Is this message the last assistant message? (for retry button)
  const lastAssistantIdx = (() => {
    for (let i = currentMessages.length - 1; i >= 0; i--) {
      if (currentMessages[i].role === 'assistant') return i
    }
    return -1
  })()

  return (
    <div className="flex flex-col h-full">
      {/* Top bar */}
      <header className="flex items-center justify-between px-4 py-2.5 border-b border-gray-100 dark:border-gray-800 bg-white dark:bg-[#1a1410] flex-shrink-0">
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
          <div className="flex items-center gap-1 mt-0.5">
            <ConnectionDot />
          </div>
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

      {/* Connection banner */}
      <ConnectionBanner />

      {/* Messages */}
      <div
        ref={messagesContainerRef}
        onScroll={handleScroll}
        className="flex-1 overflow-y-auto px-4 py-4 space-y-3 min-h-0"
      >
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

        {currentMessages.map((msg, i) => {
          const isNew = i >= prevMessageCountRef.current
          const isLastAssistant = i === lastAssistantIdx
          return (
            <MessageBubble
              key={`${currentSessionKey}-${i}`}
              role={msg.role}
              content={msg.content}
              timestamp={msg.timestamp}
              index={i}
              sessionKey={currentSessionKey}
              animate={isNew}
              showRetry={isLastAssistant && !generating}
              onRetry={retryLastMessage}
            />
          )
        })}

        {currentStreaming?.isStreaming && currentStreaming.content.length > 0 && (
          <MessageBubble
            role="assistant"
            content={currentStreaming.content}
            isStreaming
            index={-1}
            sessionKey={currentSessionKey}
          />
        )}

        {isTyping && !currentStreaming?.isStreaming && (
          <div className="flex justify-start animate-fade-in">
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

      {/* Input bar */}
      <form
        onSubmit={handleSubmit}
        className="flex items-end gap-2 px-3 py-2.5 border-t border-gray-100 dark:border-gray-800 bg-white dark:bg-[#1a1410] flex-shrink-0"
        style={{ paddingBottom: 'calc(var(--kb-height, 0px) + 10px)' }}
      >
        <textarea
          ref={textareaRef}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="Message..."
          rows={1}
          className="flex-1 resize-none px-3.5 py-2.5 rounded-[20px] border border-gray-200 dark:border-gray-700 bg-gray-50 dark:bg-[#231c14] text-gray-900 dark:text-white placeholder-gray-400 dark:placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-amber-700/40 dark:focus:ring-amber-600/40 focus:border-amber-400 dark:focus:border-amber-700 text-[15px]"
          style={{ maxHeight: '120px', overflow: 'auto' }}
          disabled={connectionState !== 'connected'}
        />

        {generating ? (
          <button
            type="button"
            onClick={handleAbort}
            className="flex items-center justify-center p-2.5 rounded-full bg-gray-600 dark:bg-gray-500 text-white active:bg-gray-700 active:scale-95 transition-all"
            aria-label="Stop generating"
          >
            <svg className="w-5 h-5" viewBox="0 0 24 24" fill="currentColor">
              <rect x="6" y="6" width="12" height="12" rx="2" />
            </svg>
          </button>
        ) : (
          <button
            type="submit"
            disabled={!input.trim() || connectionState !== 'connected'}
            onTouchStart={(e) => e.preventDefault()}
            onMouseDown={(e) => e.preventDefault()}
            className="flex items-center justify-center p-2.5 rounded-full bg-amber-800 text-white disabled:opacity-30 active:bg-amber-950 active:scale-95 transition-all"
            aria-label="Send message"
          >
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2.2}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M12 19l9 2-9-18-9 18 9-2zm0 0v-8" />
            </svg>
          </button>
        )}
      </form>
    </div>
  )
}
