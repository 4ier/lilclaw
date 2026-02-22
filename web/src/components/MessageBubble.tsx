import { useState, useMemo, useEffect, useRef, useCallback } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import rehypeHighlight from 'rehype-highlight'
import rehypeRaw from 'rehype-raw'
import type { MessageContent } from '../lib/gateway'
import { formatRelativeTime } from '../lib/formatTime'
import { useStore } from '../store'
import ContextMenu, { type ContextMenuItem } from './ContextMenu'

interface MessageBubbleProps {
  role: 'user' | 'assistant'
  content: MessageContent[]
  timestamp?: number
  isStreaming?: boolean
  index: number
  sessionKey: string
  animate?: boolean
  showRetry?: boolean
  onRetry?: () => void
}

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false)

  const handleCopy = async (e: React.MouseEvent) => {
    e.stopPropagation()
    await navigator.clipboard.writeText(text)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  return (
    <button
      onClick={handleCopy}
      className="absolute top-1.5 right-2 px-2 py-1 text-[10px] rounded-md bg-white/10 hover:bg-white/20 active:bg-white/30 text-gray-400 transition-opacity touch-target"
    >
      {copied ? '✓' : '复制'}
    </button>
  )
}

function HtmlSandbox({ html }: { html: string }) {
  const [height, setHeight] = useState(200)

  const srcDoc = useMemo(() => {
    if (html.trim().startsWith('<!DOCTYPE') || html.trim().startsWith('<html')) {
      return html.replace('</body>', `
        <script>
          const ro = new ResizeObserver(() => {
            window.parent.postMessage({ type: 'iframe-resize', height: document.documentElement.scrollHeight }, '*')
          });
          ro.observe(document.body);
          window.parent.postMessage({ type: 'iframe-resize', height: document.documentElement.scrollHeight }, '*')
        </script>
      </body>`)
    }
    return `<!DOCTYPE html>
<html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  * { box-sizing: border-box; margin: 0; }
  body { padding: 12px; font-family: -apple-system, BlinkMacSystemFont, system-ui, sans-serif; font-size: 14px; line-height: 1.5; color: #1a1a1a; }
</style>
</head><body>${html}
<script>
  const ro = new ResizeObserver(() => {
    window.parent.postMessage({ type: 'iframe-resize', height: document.documentElement.scrollHeight }, '*')
  });
  ro.observe(document.body);
  window.parent.postMessage({ type: 'iframe-resize', height: document.documentElement.scrollHeight }, '*')
</script>
</body></html>`
  }, [html])

  useEffect(() => {
    const handler = (e: MessageEvent) => {
      if (e.data?.type === 'iframe-resize' && typeof e.data.height === 'number') {
        setHeight(Math.min(Math.max(e.data.height + 4, 60), 600))
      }
    }
    window.addEventListener('message', handler)
    return () => window.removeEventListener('message', handler)
  }, [])

  return (
    <div className="my-2 rounded-xl overflow-hidden border border-gray-200 dark:border-gray-700 bg-white">
      <div className="flex items-center gap-1.5 px-3 py-1.5 bg-gray-50 border-b border-gray-200 dark:bg-gray-800 dark:border-gray-700">
        <span className="w-2.5 h-2.5 rounded-full bg-red-400/80" />
        <span className="w-2.5 h-2.5 rounded-full bg-yellow-400/80" />
        <span className="w-2.5 h-2.5 rounded-full bg-green-400/80" />
        <span className="ml-2 text-[10px] text-gray-400 uppercase tracking-wider">预览</span>
      </div>
      <iframe
        srcDoc={srcDoc}
        sandbox="allow-scripts"
        style={{ height: `${height}px` }}
        className="w-full bg-white block"
        title="HTML content"
      />
    </div>
  )
}

function extractText(node: React.ReactNode): string {
  if (typeof node === 'string') return node
  if (typeof node === 'number') return String(node)
  if (!node) return ''
  if (Array.isArray(node)) return node.map(extractText).join('')
  if (typeof node === 'object' && 'props' in (node as object)) {
    const el = node as { props?: { children?: React.ReactNode } }
    return extractText(el.props?.children)
  }
  return ''
}

function CodeBlock({ className, children }: { className?: string; children: React.ReactNode }) {
  const code = extractText(children).replace(/\n$/, '')
  const language = (className || '').replace('language-', '').replace('hljs ', '').trim()
  const isHtmlRender = language === 'html' && code.includes('<')

  if (isHtmlRender && code.length > 100) {
    return <div className="my-2"><HtmlSandbox html={code} /></div>
  }

  return (
    <div className="relative group">
      {language && (
        <div className="absolute top-0 left-0 px-2.5 py-1 text-[10px] uppercase tracking-wider text-gray-400 font-medium select-none">
          {language}
        </div>
      )}
      <CopyButton text={code} />
      <pre className={`${className} !mt-0`} style={{ paddingTop: language ? '1.75rem' : undefined }}>
        <code className={className}>{children}</code>
      </pre>
    </div>
  )
}

export default function MessageBubble({
  role,
  content,
  timestamp,
  isStreaming,
  index,
  sessionKey,
  animate,
  showRetry,
  onRetry,
}: MessageBubbleProps) {
  const isUser = role === 'user'
  const [showTimestamp, setShowTimestamp] = useState(false)
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number } | null>(null)
  const [expanded, setExpanded] = useState(false)
  const [copied, setCopied] = useState(false)
  const longPressTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const touchStartRef = useRef<{ x: number; y: number } | null>(null)
  const bubbleRef = useRef<HTMLDivElement>(null)
  const deleteMessage = useStore((s) => s.deleteMessage)

  const textContent = content
    .filter((c) => c.type === 'text' && c.text)
    .map((c) => c.text)
    .join('\n')

  const images = content.filter((c) => c.type === 'image' && c.url)

  // Long-press detection
  const handleTouchStart = useCallback((e: React.TouchEvent) => {
    const touch = e.touches[0]
    touchStartRef.current = { x: touch.clientX, y: touch.clientY }
    longPressTimerRef.current = setTimeout(() => {
      setContextMenu({ x: touch.clientX, y: touch.clientY })
    }, 500)
  }, [])

  const handleTouchMove = useCallback((e: React.TouchEvent) => {
    if (!touchStartRef.current) return
    const touch = e.touches[0]
    const dx = Math.abs(touch.clientX - touchStartRef.current.x)
    const dy = Math.abs(touch.clientY - touchStartRef.current.y)
    if (dx > 10 || dy > 10) {
      if (longPressTimerRef.current) clearTimeout(longPressTimerRef.current)
    }
  }, [])

  const handleTouchEnd = useCallback(() => {
    if (longPressTimerRef.current) clearTimeout(longPressTimerRef.current)
  }, [])

  // Right-click context menu (desktop)
  const handleContextMenu = useCallback((e: React.MouseEvent) => {
    e.preventDefault()
    setContextMenu({ x: e.clientX, y: e.clientY })
  }, [])

  // Tap to toggle timestamp
  const handleTap = useCallback(() => {
    if (!contextMenu) {
      setShowTimestamp((prev) => !prev)
    }
  }, [contextMenu])

  // Build context menu items
  const menuItems: ContextMenuItem[] = useMemo(() => {
    const items: ContextMenuItem[] = [
      {
        label: '复制',
        icon: '📋',
        onClick: () => navigator.clipboard.writeText(textContent),
      },
    ]
    if (!isUser && showRetry && onRetry) {
      items.push({
        label: '重试',
        icon: '↻',
        onClick: onRetry,
      })
    }
    if (index >= 0) {
      items.push({
        label: '删除',
        icon: '🗑',
        onClick: () => deleteMessage(sessionKey, index),
        danger: true,
      })
    }
    return items
  }, [textContent, isUser, showRetry, onRetry, index, sessionKey, deleteMessage])

  return (
    <>
      <div
        className={`flex ${isUser ? 'justify-end' : 'justify-start'} ${animate ? 'animate-message-in' : ''}`}
      >
        <div className="flex flex-col max-w-[88%]">
          <div
            ref={bubbleRef}
            className={`message-bubble ${isUser ? 'message-bubble-user' : 'message-bubble-assistant'}`}
            onClick={handleTap}
            onContextMenu={handleContextMenu}
            onTouchStart={handleTouchStart}
            onTouchMove={handleTouchMove}
            onTouchEnd={handleTouchEnd}
          >
            {images.map((img, i) => (
              <img
                key={i}
                src={img.url}
                alt="Content"
                className="max-w-full rounded-lg mb-2"
              />
            ))}

            <div className={`prose-chat ${isStreaming ? 'cursor-blink' : ''} ${!isUser && !expanded && textContent.length > 500 ? 'max-h-[200px] overflow-hidden relative' : ''}`}>
              <ReactMarkdown
                remarkPlugins={[remarkGfm]}
                rehypePlugins={[rehypeHighlight, rehypeRaw]}
                components={{
                  code({ className, children, ...props }) {
                    const isInline = !className
                    if (isInline) {
                      return <code {...props}>{children}</code>
                    }
                    return <CodeBlock className={className}>{children}</CodeBlock>
                  },
                  table({ children }) {
                    return (
                      <div className="table-wrapper">
                        <table>{children}</table>
                      </div>
                    )
                  },
                }}
              >
                {textContent}
              </ReactMarkdown>
              {!isUser && !expanded && textContent.length > 500 && (
                <div className="absolute bottom-0 left-0 right-0 h-16 pointer-events-none" style={{ background: `linear-gradient(transparent, var(--bubble-bg))` }} />
              )}
            </div>

            {/* Expand button for long messages */}
            {!isUser && !expanded && textContent.length > 500 && (
              <button
                onClick={(e) => { e.stopPropagation(); setExpanded(true) }}
                className="mt-1 text-[13px] text-amber-700 dark:text-amber-500 font-medium"
              >
                展开全文 ▼
              </button>
            )}
            {!isUser && expanded && textContent.length > 500 && (
              <button
                onClick={(e) => { e.stopPropagation(); setExpanded(false) }}
                className="mt-1 text-[13px] text-amber-700 dark:text-amber-500 font-medium"
              >
                收起 ▲
              </button>
            )}
          </div>

          {/* Action row: timestamp + copy + retry for assistant */}
          <div className={`flex items-center gap-3 mt-1 ${isUser ? 'justify-end pr-1' : 'pl-1'}`}>
            {showTimestamp && timestamp && (
              <span className="text-[11px] text-gray-400 dark:text-gray-500">
                {formatRelativeTime(timestamp)}
              </span>
            )}
            {!isUser && !isStreaming && textContent.length > 0 && (
              <button
                onClick={(e) => {
                  e.stopPropagation()
                  navigator.clipboard.writeText(textContent)
                  setCopied(true)
                  setTimeout(() => setCopied(false), 1500)
                }}
                className="text-[12px] text-gray-400 dark:text-gray-500 active:text-amber-700 dark:active:text-amber-500 transition-colors"
              >
                {copied ? '✓ 已复制' : '📋 复制'}
              </button>
            )}
            {showRetry && onRetry && (
              <button
                onClick={onRetry}
                className="flex items-center gap-1 text-[12px] text-gray-400 dark:text-gray-500 active:text-amber-700 dark:active:text-amber-500 transition-colors"
              >
                <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                  <path strokeLinecap="round" strokeLinejoin="round" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
                </svg>
                <span>重试</span>
              </button>
            )}
          </div>
        </div>
      </div>

      {/* Context menu */}
      {contextMenu && (
        <ContextMenu
          items={menuItems}
          x={contextMenu.x}
          y={contextMenu.y}
          onClose={() => setContextMenu(null)}
        />
      )}
    </>
  )
}
