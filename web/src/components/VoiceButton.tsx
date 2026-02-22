import { useState, useCallback, useEffect } from 'react'
import { showToast } from './Toast'

declare global {
  interface Window {
    __lilclaw_onVoiceText?: (text: string) => void
    __lilclaw_onVoicePartial?: (text: string) => void
    __lilclaw_onVoiceState?: (state: string) => void
    __lilclaw_onVoiceError?: (error: string) => void
  }
}

interface VoiceButtonProps {
  onResult: (text: string) => void
}

export default function VoiceButton({ onResult }: VoiceButtonProps) {
  const [state, setState] = useState<'idle' | 'listening' | 'processing'>('idle')
  const [partial, setPartial] = useState('')

  const hasVoice = typeof window !== 'undefined' &&
    window.LilClaw && 'startVoice' in (window.LilClaw as unknown as Record<string, unknown>)

  useEffect(() => {
    window.__lilclaw_onVoiceText = (text: string) => {
      setState('idle')
      setPartial('')
      if (text.trim()) {
        onResult(text.trim())
      }
    }
    window.__lilclaw_onVoicePartial = (text: string) => {
      setPartial(text)
    }
    window.__lilclaw_onVoiceState = (s: string) => {
      if (s === 'listening') setState('listening')
      else if (s === 'processing') setState('processing')
    }
    window.__lilclaw_onVoiceError = (error: string) => {
      setState('idle')
      setPartial('')
      showToast(error, 'error')
    }
    return () => {
      window.__lilclaw_onVoiceText = undefined
      window.__lilclaw_onVoicePartial = undefined
      window.__lilclaw_onVoiceState = undefined
      window.__lilclaw_onVoiceError = undefined
    }
  }, [onResult])

  const toggle = useCallback(() => {
    const bridge = window.LilClaw as unknown as { startVoice: () => void; stopVoice: () => void } | undefined
    if (!bridge) return

    if (state === 'idle') {
      bridge.startVoice()
      setState('listening')
    } else if (state === 'listening') {
      bridge.stopVoice()
      setState('processing')
    }
  }, [state])

  if (!hasVoice) return null

  return (
    <div className="relative">
      <button
        type="button"
        onClick={toggle}
        className={`flex items-center justify-center p-2 rounded-full transition-all ${
          state === 'listening'
            ? 'bg-red-500 text-white animate-pulse scale-110'
            : state === 'processing'
            ? 'bg-amber-600 text-white'
            : 'text-gray-400 dark:text-gray-500 active:bg-gray-100 dark:active:bg-gray-800'
        }`}
        aria-label={state === 'listening' ? '停止录音' : '语音输入'}
      >
        {state === 'processing' ? (
          <svg className="w-5 h-5 animate-spin" viewBox="0 0 24 24" fill="none">
            <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
            <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
          </svg>
        ) : (
          <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M19 11a7 7 0 01-7 7m0 0a7 7 0 01-7-7m7 7v4m0 0H8m4 0h4M12 15a3 3 0 003-3V5a3 3 0 00-6 0v7a3 3 0 003 3z" />
          </svg>
        )}
      </button>

      {/* Partial text overlay */}
      {state === 'listening' && partial && (
        <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-2 px-3 py-1.5 bg-gray-800 dark:bg-gray-200 text-white dark:text-gray-800 text-[13px] rounded-xl whitespace-nowrap max-w-[200px] truncate shadow-lg">
          {partial}
        </div>
      )}
    </div>
  )
}
