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
  const srcDoc = useMemo(() => {
    return `
      <!DOCTYPE html>
      <html>
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <style>
            body { margin: 0; padding: 8px; font-family: system-ui, sans-serif; }
          </style>
        </head>
        <body>${html}</body>
      </html>
    `
  }, [html])

  return (
    <iframe
      srcDoc={srcDoc}
      sandbox="allow-scripts"
      className="w-full min-h-[200px] border border-gray-300 dark:border-gray-600 rounded-lg bg-white"
      title="HTML content"
    />
  )
}

function CodeBlock({ className, children }: { className?: string; children: React.ReactNode }) {
  const code = String(children).replace(/\n$/, '')
  const language = className?.replace('language-', '') || ''

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
