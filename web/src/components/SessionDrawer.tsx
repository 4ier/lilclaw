import { useEffect, useRef, useState } from 'react'
import { useStore } from '../store'

export default function SessionDrawer() {
  const {
    sessions,
    currentSessionKey,
    switchSession,
    createSession,
    setShowDrawer,
    getSessionDisplayName,
  } = useStore()

  const drawerRef = useRef<HTMLDivElement>(null)
  const [visible, setVisible] = useState(false)

  // Trigger enter animation on mount (GPU-accelerated translateX)
  useEffect(() => {
    // Force a layout read then set visible for the transition
    requestAnimationFrame(() => {
      requestAnimationFrame(() => setVisible(true))
    })
  }, [])

  const close = () => {
    setVisible(false)
    // Wait for exit animation to finish
    setTimeout(() => setShowDrawer(false), 200)
  }

  // Close on click outside
  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (drawerRef.current && !drawerRef.current.contains(e.target as Node)) {
        close()
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [setShowDrawer])

  // Close on escape
  useEffect(() => {
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') close()
    }
    document.addEventListener('keydown', handleEscape)
    return () => document.removeEventListener('keydown', handleEscape)
  }, [setShowDrawer])

  // If sessions list is empty, show at least current
  const displaySessions = sessions.length > 0
    ? sessions
    : [{ key: currentSessionKey }]

  return (
    <div className="fixed inset-0 z-50 flex">
      {/* Backdrop — GPU composited opacity transition */}
      <div
        className="absolute inset-0 bg-black/40"
        style={{
          opacity: visible ? 1 : 0,
          transition: 'opacity 200ms ease-out',
          willChange: 'opacity',
        }}
      />

      {/* Drawer — GPU composited translateX transition */}
      <div
        ref={drawerRef}
        className="relative w-72 max-w-[85vw] h-full bg-white dark:bg-[#141414] shadow-2xl flex flex-col"
        style={{
          transform: visible ? 'translateX(0)' : 'translateX(-100%)',
          transition: 'transform 200ms ease-out',
          willChange: 'transform',
        }}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-4 py-3 border-b border-gray-100 dark:border-gray-800 safe-top">
          <h2 className="text-[17px] font-semibold text-gray-900 dark:text-white">
            Sessions
          </h2>
          <button
            onClick={close}
            className="flex items-center justify-center p-2 -mr-2 rounded-xl hover:bg-gray-100 dark:hover:bg-gray-800 active:scale-95"
            aria-label="Close"
          >
            <svg className="w-5 h-5 text-gray-500" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        {/* Session list */}
        <div className="flex-1 overflow-y-auto py-1">
          {displaySessions.map((session) => {
            const isActive = session.key === currentSessionKey
            const displayName = getSessionDisplayName(session.key)

            return (
              <button
                key={session.key}
                onClick={() => { switchSession(session.key); close() }}
                className={`w-full px-4 py-3 text-left flex items-center gap-3 ${
                  isActive
                    ? 'bg-amber-50 dark:bg-amber-500/10'
                    : 'hover:bg-gray-50 dark:hover:bg-white/[0.04] active:bg-gray-100 dark:active:bg-white/[0.08]'
                }`}
              >
                <div className={`w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0 text-xs font-semibold ${
                  isActive
                    ? 'bg-amber-100 dark:bg-amber-500/20 text-amber-800 dark:text-amber-500'
                    : 'bg-gray-100 dark:bg-gray-800 text-gray-500 dark:text-gray-400'
                }`}>
                  {displayName.charAt(0).toUpperCase()}
                </div>
                <div className="flex-1 min-w-0">
                  <div className={`text-[14px] truncate ${
                    isActive
                      ? 'text-amber-800 dark:text-amber-500 font-medium'
                      : 'text-gray-900 dark:text-gray-100'
                  }`}>
                    {displayName}
                  </div>
                  {session.label && session.key !== (session.label || session.key) && (
                    <div className="text-[11px] text-gray-400 dark:text-gray-500 truncate">
                      {session.key.replace(/^agent:[^:]+:/, '')}
                    </div>
                  )}
                </div>
                {isActive && (
                  <div className="w-1.5 h-1.5 rounded-full bg-amber-500 flex-shrink-0" />
                )}
              </button>
            )
          })}
        </div>

        {/* New session */}
        <div className="border-t border-gray-100 dark:border-gray-800 p-3 safe-bottom">
          <button
            onClick={() => {
              const key = `chat-${Date.now()}`
              createSession(key)
              close()
            }}
            className="w-full flex items-center justify-center gap-2 py-2.5 rounded-xl border border-dashed border-gray-200 dark:border-gray-700 text-gray-500 dark:text-gray-400 text-sm hover:border-amber-500 hover:text-amber-700 dark:hover:border-amber-600 dark:hover:text-amber-500 active:scale-[0.98]"
          >
            <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M12 4v16m8-8H4" />
            </svg>
            <span>New chat</span>
          </button>
        </div>
      </div>
    </div>
  )
}
