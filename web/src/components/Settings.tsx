import { useEffect, useRef } from 'react'
import { useStore } from '../store'

declare global {
  interface Window {
    LilClaw?: { openSettings: () => void }
    __LILCLAW_VERSION?: string
  }
}

export default function Settings() {
  const { theme, connectionState, setShowSettings, setTheme } = useStore()
  const modalRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const handleClickOutside = (e: MouseEvent | TouchEvent) => {
      if (modalRef.current && !modalRef.current.contains(e.target as Node)) {
        setShowSettings(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    document.addEventListener('touchend', handleClickOutside, { passive: true })
    return () => {
      document.removeEventListener('mousedown', handleClickOutside)
      document.removeEventListener('touchend', handleClickOutside)
    }
  }, [setShowSettings])

  useEffect(() => {
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setShowSettings(false)
    }
    document.addEventListener('keydown', handleEscape)
    return () => document.removeEventListener('keydown', handleEscape)
  }, [setShowSettings])

  const themeOptions = [
    { value: 'system', label: 'System' },
    { value: 'light', label: 'Light' },
    { value: 'dark', label: 'Dark' },
  ] as const

  const appVersion = window.__LILCLAW_VERSION || 'dev'

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/40" />

      <div
        ref={modalRef}
        className="relative w-full max-w-sm bg-white dark:bg-[#1e1812] rounded-2xl shadow-2xl overflow-hidden animate-fade-in"
      >
        {/* Header */}
        <div className="flex items-center justify-between px-4 py-4 border-b border-gray-100 dark:border-gray-800">
          <h2 className="text-lg font-semibold text-gray-900 dark:text-white">
            Settings
          </h2>
          <button
            onClick={() => setShowSettings(false)}
            className="touch-target flex items-center justify-center p-2 -mr-2 rounded-lg active:bg-gray-100 dark:active:bg-gray-800"
            aria-label="Close settings"
          >
            <svg className="w-5 h-5 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        {/* Content */}
        <div className="p-4 space-y-5">
          {/* Theme */}
          <div>
            <label className="block text-sm font-medium text-gray-600 dark:text-gray-400 mb-2">
              Appearance
            </label>
            <div className="flex gap-2">
              {themeOptions.map((opt) => (
                <button
                  key={opt.value}
                  onClick={() => setTheme(opt.value)}
                  className={`flex-1 py-2.5 px-3 rounded-xl text-sm font-medium transition-all ${
                    theme === opt.value
                      ? 'bg-gray-900 dark:bg-white text-white dark:text-gray-900 shadow-lg'
                      : 'bg-gray-100 dark:bg-gray-800 text-gray-600 dark:text-gray-400 active:bg-gray-200 dark:active:bg-gray-700'
                  }`}
                >
                  {opt.label}
                </button>
              ))}
            </div>
          </div>

          {/* AI Provider Settings — native bridge */}
          {window.LilClaw && (
            <button
              onClick={() => {
                setShowSettings(false)
                window.LilClaw?.openSettings()
              }}
              className="w-full flex items-center justify-between py-3 px-4 rounded-xl bg-gray-50 dark:bg-gray-800/50 active:bg-gray-100 dark:active:bg-gray-800 transition-colors"
            >
              <div className="flex items-center gap-3">
                <svg className="w-5 h-5 text-gray-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M9.813 15.904L9 18.75l-.813-2.846a4.5 4.5 0 00-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 003.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 003.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 00-3.09 3.09zM18.259 8.715L18 9.75l-.259-1.035a3.375 3.375 0 00-2.455-2.456L14.25 6l1.036-.259a3.375 3.375 0 002.455-2.456L18 2.25l.259 1.035a3.375 3.375 0 002.455 2.456L21.75 6l-1.036.259a3.375 3.375 0 00-2.455 2.456z" />
                </svg>
                <span className="text-sm font-medium text-gray-700 dark:text-gray-300">AI Provider</span>
              </div>
              <svg className="w-4 h-4 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
              </svg>
            </button>
          )}

          {/* Status */}
          <div className="space-y-2">
            <label className="block text-sm font-medium text-gray-600 dark:text-gray-400">
              Status
            </label>
            <div className="rounded-xl bg-gray-50 dark:bg-gray-800/50 p-3 space-y-2">
              <div className="flex items-center justify-between">
                <span className="text-[13px] text-gray-500 dark:text-gray-400">Gateway</span>
                <div className="flex items-center gap-1.5">
                  <span className={`w-1.5 h-1.5 rounded-full ${
                    connectionState === 'connected' ? 'bg-emerald-500' :
                    connectionState === 'connecting' ? 'bg-amber-500 animate-pulse' :
                    'bg-red-500'
                  }`} />
                  <span className="text-[13px] text-gray-700 dark:text-gray-300 capitalize">{connectionState}</span>
                </div>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-[13px] text-gray-500 dark:text-gray-400">Port</span>
                <span className="text-[13px] text-gray-700 dark:text-gray-300 font-mono">3000</span>
              </div>
            </div>
          </div>
        </div>

        {/* Footer */}
        <div className="px-4 py-3 border-t border-gray-100 dark:border-gray-800 flex items-center justify-between">
          <span className="text-[11px] text-gray-400 dark:text-gray-500 font-mono">
            LilClaw v{appVersion}
          </span>
          <button
            onClick={() => setShowSettings(false)}
            className="py-2 px-4 rounded-xl text-sm font-medium text-gray-500 dark:text-gray-400 active:bg-gray-50 dark:active:bg-gray-800 transition-colors"
          >
            Done
          </button>
        </div>
      </div>
    </div>
  )
}
