import { useState, useEffect, useRef } from 'react'
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

  const [newSessionName, setNewSessionName] = useState('')
  const [showNewInput, setShowNewInput] = useState(false)
  const drawerRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  // Focus input when shown
  useEffect(() => {
    if (showNewInput) {
      inputRef.current?.focus()
    }
  }, [showNewInput])

  // Close on click outside
  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (drawerRef.current && !drawerRef.current.contains(e.target as Node)) {
        setShowDrawer(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [setShowDrawer])

  // Close on escape
  useEffect(() => {
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setShowDrawer(false)
    }
    document.addEventListener('keydown', handleEscape)
    return () => document.removeEventListener('keydown', handleEscape)
  }, [setShowDrawer])

  const handleCreateSession = () => {
    const name = newSessionName.trim()
    if (name && !sessions.find((s) => s.key === name)) {
      createSession(name)
      setNewSessionName('')
      setShowNewInput(false)
    }
  }

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') handleCreateSession()
    else if (e.key === 'Escape') {
      setShowNewInput(false)
      setNewSessionName('')
    }
  }

  // If sessions list is empty (e.g. not connected), show at least current
  const displaySessions = sessions.length > 0
    ? sessions
    : [{ key: currentSessionKey }]

  return (
    <div className="fixed inset-0 z-50 flex">
      {/* Backdrop */}
      <div className="absolute inset-0 bg-black/40 backdrop-blur-sm" />

      {/* Drawer */}
      <div
        ref={drawerRef}
        className="relative w-72 max-w-[85vw] h-full bg-white dark:bg-[#141414] shadow-2xl flex flex-col animate-slide-in"
        style={{ animation: 'slide-in 0.2s ease-out' }}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-4 py-3 border-b border-gray-100 dark:border-gray-800 safe-top">
          <h2 className="text-[17px] font-semibold text-gray-900 dark:text-white">
            Sessions
          </h2>
          <button
            onClick={() => setShowDrawer(false)}
            className="touch-target flex items-center justify-center p-2 -mr-2 rounded-xl hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors active:scale-95"
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
                onClick={() => switchSession(session.key)}
                className={`w-full px-4 py-3 text-left flex items-center gap-3 transition-all touch-target ${
                  isActive
                    ? 'bg-indigo-50 dark:bg-indigo-500/10'
                    : 'hover:bg-gray-50 dark:hover:bg-white/[0.04] active:bg-gray-100 dark:active:bg-white/[0.08]'
                }`}
              >
                <div className={`w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0 text-xs font-semibold ${
                  isActive
                    ? 'bg-indigo-100 dark:bg-indigo-500/20 text-indigo-600 dark:text-indigo-400'
                    : 'bg-gray-100 dark:bg-gray-800 text-gray-500 dark:text-gray-400'
                }`}>
                  {displayName.charAt(0).toUpperCase()}
                </div>
                <div className="flex-1 min-w-0">
                  <div className={`text-[14px] truncate ${
                    isActive
                      ? 'text-indigo-600 dark:text-indigo-400 font-medium'
                      : 'text-gray-900 dark:text-gray-100'
                  }`}>
                    {displayName}
                  </div>
                  {session.label && (
                    <div className="text-[11px] text-gray-400 dark:text-gray-500 truncate">
                      {session.key}
                    </div>
                  )}
                </div>
                {isActive && (
                  <div className="w-1.5 h-1.5 rounded-full bg-indigo-500 flex-shrink-0" />
                )}
              </button>
            )
          })}
        </div>

        {/* New session */}
        <div className="border-t border-gray-100 dark:border-gray-800 p-3 safe-bottom">
          {showNewInput ? (
            <div className="flex gap-2">
              <input
                ref={inputRef}
                type="text"
                value={newSessionName}
                onChange={(e) => setNewSessionName(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder="Session key"
                className="flex-1 px-3 py-2 rounded-xl border border-gray-200 dark:border-gray-700 bg-gray-50 dark:bg-[#1a1a1a] text-gray-900 dark:text-white placeholder-gray-400 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500/40"
              />
              <button
                onClick={handleCreateSession}
                disabled={!newSessionName.trim()}
                className="px-3.5 py-2 rounded-xl bg-indigo-600 text-white text-sm font-medium disabled:opacity-30 hover:bg-indigo-700 active:scale-95 transition-all"
              >
                Add
              </button>
            </div>
          ) : (
            <button
              onClick={() => setShowNewInput(true)}
              className="w-full flex items-center justify-center gap-2 py-2.5 rounded-xl border border-dashed border-gray-200 dark:border-gray-700 text-gray-500 dark:text-gray-400 text-sm hover:border-indigo-400 hover:text-indigo-500 dark:hover:border-indigo-500 dark:hover:text-indigo-400 transition-all active:scale-[0.98] touch-target"
            >
              <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M12 4v16m8-8H4" />
              </svg>
              <span>New session</span>
            </button>
          )}
        </div>
      </div>

      <style>{`
        @keyframes slide-in {
          from { transform: translateX(-100%); }
          to { transform: translateX(0); }
        }
      `}</style>
    </div>
  )
}
