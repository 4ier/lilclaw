import { useState, useMemo } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import rehypeHighlight from 'rehype-highlight'
import rehypeRaw from 'rehype-raw'
import type { MessageContent } from '../lib/gateway'

interface MessageBubbleProps {
  role: 'user' | 'assistant'
  content: MessageContent[]
  isStreaming?: boolean
}

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false)

  const handleCopy = async () => {
    await navigator.clipboard.writeText(text)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  return (
    <button
      onClick={handleCopy}
      className="absolute top-2 right-2 px-2 py-1 text-[11px] rounded-md bg-white/10 hover:bg-white/20 text-gray-300 transition-all opacity-0 group-hover:opacity-100 touch-target backdrop-blur-sm"
    >
      {copied ? '✓ Copied' : 'Copy'}
    </button>
  )
}

function HtmlSandbox({ html }: { html: string }) {
  const [height, setHeight] = useState(200)

  const srcDoc = useMemo(() => {
    // If it's a full HTML document, use as-is
    if (html.trim().startsWith('<!DOCTYPE') || html.trim().startsWith('<html')) {
      // Inject resize observer script
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
    // Fragment — wrap in a full document
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

  // Listen for resize messages from iframe
  useMemo(() => {
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
        <span className="ml-2 text-[10px] text-gray-400 uppercase tracking-wider">Preview</span>
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

  // Detect HTML code blocks that should be rendered
  const isHtmlRender = language === 'html' && code.includes('<')

  if (isHtmlRender && code.length > 100) {
    return (
      <div className="my-2">
        <HtmlSandbox html={code} />
      </div>
    )
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

export default function MessageBubble({ role, content, isStreaming }: MessageBubbleProps) {
  const isUser = role === 'user'

  const textContent = content
    .filter((c) => c.type === 'text' && c.text)
    .map((c) => c.text)
    .join('\n')

  const images = content.filter((c) => c.type === 'image' && c.url)

  return (
    <div
      className={`flex ${isUser ? 'justify-end' : 'justify-start'} animate-fade-in`}
    >
      <div
        className={`message-bubble ${
          isUser ? 'message-bubble-user' : 'message-bubble-assistant'
        }`}
      >
        {images.map((img, i) => (
          <img
            key={i}
            src={img.url}
            alt="Content"
            className="max-w-full rounded-lg mb-2"
          />
        ))}

        <div className={`prose-chat ${isStreaming ? 'cursor-blink' : ''}`}>
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
        </div>
      </div>
    </div>
  )
}
