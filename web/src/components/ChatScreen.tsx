import { useState, useRef, useEffect, useCallback, type FormEvent } from 'react'
import { useStore } from '../store'
import MessageBubble from './MessageBubble'
import ActionCards from './ActionCards'
import type { ActionCard } from '../lib/actions'
import { haptic } from '../lib/haptic'
import { formatDateSeparator, isDifferentDay } from '../lib/dateSeparator'

function ConnectionBanner() {
  const { connectionState, cacheLoaded, pendingMessages } = useStore()
  const pendingCount = pendingMessages.length

  if (connectionState === 'connected' && pendingCount === 0) return null

  // Connected but flushing
  if (connectionState === 'connected' && pendingCount > 0) {
    return (
      <div className="flex items-center justify-center gap-2 px-4 py-1.5 text-[12px] text-amber-700 dark:text-amber-400 bg-amber-50 dark:bg-amber-900/20 flex-shrink-0">
        <svg className="w-3 h-3 animate-spin" viewBox="0 0 24 24" fill="none">
          <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
          <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
        </svg>
        <span>正在发送 {pendingCount} 条消息...</span>
      </div>
    )
  }

  const config: Record<string, { color: string; bg: string; label: string }> = {
    connecting: {
      color: 'text-amber-700 dark:text-amber-400',
      bg: 'bg-amber-50 dark:bg-amber-900/20',
      label: cacheLoaded
        ? pendingCount > 0
          ? `正在连接... (${pendingCount} 条消息待发送)`
          : '正在连接...'
        : '加载中...',
    },
    disconnected: {
      color: 'text-gray-500 dark:text-gray-400',
      bg: 'bg-gray-50 dark:bg-gray-800',
      label: pendingCount > 0
        ? `未连接 · ${pendingCount} 条消息待发送`
        : '未连接',
    },
    error: {
      color: 'text-red-600 dark:text-red-400',
      bg: 'bg-red-50 dark:bg-red-900/20',
      label: pendingCount > 0
        ? `连接出错 · ${pendingCount} 条待发 · 重试中...`
        : '连接出错，重试中...',
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
  exec: { icon: '💻', label: '正在执行命令...' },
  web_search: { icon: '🔍', label: '正在搜索...' },
  web_fetch: { icon: '🌐', label: '正在读取网页...' },
  browser: { icon: '🌐', label: '正在浏览网页...' },
  read: { icon: '📄', label: '正在读取文件...' },
  write: { icon: '📝', label: '正在写入文件...' },
  edit: { icon: '✏️', label: '正在修改文件...' },
  memory_search: { icon: '🧠', label: '正在回忆...' },
  message: { icon: '💬', label: '正在发送消息...' },
  tts: { icon: '🔊', label: '正在生成语音...' },
}

function AgentStatus() {
  const { agentState, currentSessionKey } = useStore()
  const state = agentState[currentSessionKey]
  if (!state || state.kind === 'done') return null

  // Extract tool name from event data
  const data = state.data as Record<string, unknown> | undefined
  const toolName = (data?.tool || data?.name || '') as string

  let icon = '⚙️'
  let label = '思考中...'

  if (state.kind === 'tool_use') {
    const display = TOOL_DISPLAY[toolName]
    if (display) {
      icon = display.icon
      label = display.label
    } else if (toolName) {
      label = `正在使用 ${toolName}...`
    } else {
      label = '正在处理...'
    }
  } else if (state.kind === 'thinking') {
    icon = '💭'
    label = '思考中...'
  } else if (state.kind === 'error') {
    icon = '⚠️'
    label = '出错了'
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
  const [showScrollBtn, setShowScrollBtn] = useState(false)
  const prevMessageCountRef = useRef(0)

  const currentMessages = messages[currentSessionKey] || []
  const currentStreaming = streaming[currentSessionKey]
  const isTyping = typing[currentSessionKey] || false
  const displayName = getSessionDisplayName(currentSessionKey)
  const generating = isGenerating()

  // Auto-scroll to bottom on new messages
  useEffect(() => {
    if (wasAtBottomRef.current) {
      messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
    }
  }, [currentMessages.length, currentStreaming?.content, isTyping])

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
    const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 60
    wasAtBottomRef.current = atBottom
    setShowScrollBtn(!atBottom)
  }, [])

  const scrollToBottom = useCallback(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
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

  // Handle Action Card selection
  const handleActionSelect = useCallback((action: ActionCard) => {
    if (action.inputMode === 'text' || action.inputMode === 'url') {
      // For text/url actions, pre-fill the textarea with a prompt hint
      setInput('')
      textareaRef.current?.focus()
      // Set a placeholder hint based on the action
      setActiveAction(action)
    } else if (action.inputMode === 'camera') {
      // Try native bridge, fallback to text input
      if (window.LilClaw && 'takePhoto' in window.LilClaw) {
        (window.LilClaw as { takePhoto: () => void }).takePhoto()
      } else {
        setActiveAction(action)
        textareaRef.current?.focus()
      }
    }
  }, [])

  const [activeAction, setActiveAction] = useState<ActionCard | null>(null)

  // Clear active action when message is sent
  const handleSubmit = useCallback((e: FormEvent) => {
    e.preventDefault()
    const trimmed = input.trim()
    if (!trimmed) return
    setInput('')
    if (textareaRef.current) textareaRef.current.style.height = 'auto'

    // Apply action template if active
    let finalMessage = trimmed
    if (activeAction) {
      finalMessage = activeAction.promptTemplate.replace('${input}', trimmed)
      setActiveAction(null)
    }

    sendMessage(finalMessage)
    haptic('light')
  }, [input, sendMessage, activeAction])

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
    <div className="flex flex-col h-full relative">
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
          <div className="flex flex-col items-center justify-center h-full text-gray-400 dark:text-gray-500 px-6">
            <svg className="w-14 h-14 mb-3 opacity-40" viewBox="0 0 100 100" fill="currentColor">
              <ellipse cx="30" cy="28" rx="10" ry="12" />
              <ellipse cx="50" cy="22" rx="10" ry="12" />
              <ellipse cx="70" cy="28" rx="10" ry="12" />
              <ellipse cx="50" cy="55" rx="18" ry="20" />
            </svg>
            <p className="text-lg font-medium text-gray-500 dark:text-gray-400 mb-6">有什么我能帮你的？</p>
            <ActionCards onSelect={handleActionSelect} />
          </div>
        )}

        {currentMessages.map((msg, i) => {
          const isNew = i >= prevMessageCountRef.current
          const isLastAssistant = i === lastAssistantIdx
          const prevRole = i > 0 ? currentMessages[i - 1].role : null
          const isGrouped = prevRole === msg.role
          const prevTimestamp = i > 0 ? currentMessages[i - 1].timestamp : undefined
          const showDateSep = i === 0 || isDifferentDay(prevTimestamp, msg.timestamp)
          return (
            <div key={`${currentSessionKey}-${i}`}>
              {showDateSep && msg.timestamp && (
                <div className="flex items-center justify-center py-3">
                  <span className="text-[12px] text-gray-400 dark:text-gray-500 bg-gray-100 dark:bg-gray-800 px-3 py-0.5 rounded-full">
                    {formatDateSeparator(msg.timestamp)}
                  </span>
                </div>
              )}
              <div className={isGrouped && !showDateSep ? '-mt-1' : ''}>
                <MessageBubble
                  role={msg.role}
                  content={msg.content}
                  timestamp={msg.timestamp}
                  index={i}
                  sessionKey={currentSessionKey}
                  animate={isNew}
                  showRetry={isLastAssistant && !generating}
                  onRetry={retryLastMessage}
                />
              </div>
            </div>
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
              <div className="flex items-center gap-2 py-1 px-0.5">
                <div className="flex items-center gap-1">
                  <span className="w-1.5 h-1.5 rounded-full bg-amber-600/60 dark:bg-amber-500/60 animate-bounce [animation-delay:0ms]" />
                  <span className="w-1.5 h-1.5 rounded-full bg-amber-600/60 dark:bg-amber-500/60 animate-bounce [animation-delay:150ms]" />
                  <span className="w-1.5 h-1.5 rounded-full bg-amber-600/60 dark:bg-amber-500/60 animate-bounce [animation-delay:300ms]" />
                </div>
                <span className="text-[13px] text-gray-400 dark:text-gray-500">正在思考</span>
              </div>
            </div>
          </div>
        )}

        <div ref={messagesEndRef} />
      </div>

      {/* Scroll to bottom FAB */}
      {showScrollBtn && (
        <button
          onClick={scrollToBottom}
          className="absolute bottom-32 right-4 z-10 w-9 h-9 rounded-full bg-white dark:bg-[#2a2218] border border-gray-200 dark:border-gray-700 shadow-lg flex items-center justify-center active:scale-90 transition-all animate-fade-in"
          aria-label="Scroll to bottom"
        >
          <svg className="w-4 h-4 text-gray-500 dark:text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2.5}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M19 14l-7 7m0 0l-7-7m7 7V3" />
          </svg>
        </button>
      )}

      {/* Agent status */}
      <AgentStatus />

      {/* Input bar */}
      <form
        onSubmit={handleSubmit}
        className="flex flex-col border-t border-gray-100 dark:border-gray-800 bg-white dark:bg-[#1a1410] flex-shrink-0"
        style={{ paddingBottom: 'var(--kb-height, 0px)' }}
      >
        {/* Active action indicator */}
        {activeAction && (
          <div className="flex items-center gap-2 px-3 pt-2 pb-0">
            <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800">
              <span className="text-sm">{activeAction.icon}</span>
              <span className="text-[12px] font-medium text-amber-800 dark:text-amber-400">{activeAction.title}</span>
              <button
                type="button"
                onClick={() => setActiveAction(null)}
                className="ml-0.5 text-amber-600 dark:text-amber-500"
              >
                <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2.5}>
                  <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </div>
          </div>
        )}

        <div className="flex items-end gap-2 px-3 py-2.5">
          {/* Attachment button */}
          <button
            type="button"
            onClick={() => {
              // TODO: Open attachment picker bottom sheet
              // For now, native bridge stub
              if (window.LilClaw && 'pickImage' in window.LilClaw) {
                (window.LilClaw as { pickImage: () => void }).pickImage()
              }
            }}
            className="flex items-center justify-center p-2 rounded-full text-gray-400 dark:text-gray-500 active:bg-gray-100 dark:active:bg-gray-800 transition-colors"
            aria-label="Attach"
          >
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M12 4v16m8-8H4" />
            </svg>
          </button>

          <textarea
            ref={textareaRef}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder={activeAction ? activeAction.description : connectionState === 'connected' ? '输入消息...' : '输入消息（连接后自动发送）...'}
            rows={1}
            className="flex-1 resize-none px-3.5 py-2.5 rounded-[20px] border border-gray-200 dark:border-gray-700 bg-gray-50 dark:bg-[#231c14] text-gray-900 dark:text-white placeholder-gray-400 dark:placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-amber-700/40 dark:focus:ring-amber-600/40 focus:border-amber-400 dark:focus:border-amber-700 text-base"
            style={{ maxHeight: '120px', overflow: 'auto' }}
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
              disabled={!input.trim()}
              onMouseDown={(e) => e.preventDefault()}
              className="flex items-center justify-center p-2.5 rounded-full bg-amber-800 text-white disabled:opacity-30 active:bg-amber-950 active:scale-95 transition-all"
              aria-label="Send message"
            >
              <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2.2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M12 19l9 2-9-18-9 18 9-2zm0 0v-8" />
              </svg>
            </button>
          )}
        </div>
      </form>
    </div>
  )
}
