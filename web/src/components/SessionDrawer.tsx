import { useEffect, useRef, useCallback, useState } from 'react'
import { useStore } from '../store'

function SessionItem({
  session,
  isActive,
  displayName,
  onSwitch,
  onDelete,
}: {
  session: { key: string; label?: string }
  isActive: boolean
  displayName: string
  onSwitch: () => void
  onDelete: () => void
}) {
  const [swipeX, setSwipeX] = useState(0)
  const [showDelete, setShowDelete] = useState(false)
  const touchStartRef = useRef<{ x: number; y: number } | null>(null)
  const swipingRef = useRef(false)

  const handleTouchStart = useCallback((e: React.TouchEvent) => {
    const touch = e.touches[0]
    touchStartRef.current = { x: touch.clientX, y: touch.clientY }
    swipingRef.current = false
  }, [])

  const handleTouchMove = useCallback((e: React.TouchEvent) => {
    if (!touchStartRef.current) return
    const touch = e.touches[0]
    const dx = touch.clientX - touchStartRef.current.x
    const dy = Math.abs(touch.clientY - touchStartRef.current.y)

    // If vertical scroll, don't swipe
    if (dy > 20 && !swipingRef.current) {
      touchStartRef.current = null
      return
    }

    if (dx < -10) swipingRef.current = true

    if (swipingRef.current) {
      setSwipeX(Math.min(0, Math.max(-80, dx)))
    }
  }, [])

  const handleTouchEnd = useCallback(() => {
    if (swipeX < -40) {
      setShowDelete(true)
      setSwipeX(-80)
    } else {
      setShowDelete(false)
      setSwipeX(0)
    }
    touchStartRef.current = null
    swipingRef.current = false
  }, [swipeX])

  const handleDelete = useCallback(() => {
    setSwipeX(0)
    setShowDelete(false)
    onDelete()
  }, [onDelete])

  const handleClick = useCallback(() => {
    if (!swipingRef.current && swipeX === 0) {
      onSwitch()
    }
  }, [onSwitch, swipeX])

  // Reset swipe when drawer closes
  useEffect(() => {
    return () => {
      setSwipeX(0)
      setShowDelete(false)
    }
  }, [])

  return (
    <div className="relative overflow-hidden">
      {/* Delete button behind */}
      {showDelete && (
        <button
          onClick={handleDelete}
          className="absolute right-0 top-0 bottom-0 w-20 flex items-center justify-center bg-red-500 text-white text-sm font-medium"
        >
          Delete
        </button>
      )}

      {/* Session row */}
      <div
        onClick={handleClick}
        onTouchStart={handleTouchStart}
        onTouchMove={handleTouchMove}
        onTouchEnd={handleTouchEnd}
        className={`w-full px-4 py-3 text-left flex items-center gap-3 bg-white dark:bg-[#141414] ${
          isActive
            ? 'bg-amber-50 dark:!bg-amber-500/10'
            : 'active:bg-gray-100 dark:active:bg-white/[0.08]'
        }`}
        style={{
          transform: `translate3d(${swipeX}px, 0, 0)`,
          transition: swipingRef.current ? 'none' : 'transform 200ms ease-out',
        }}
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
      </div>
    </div>
  )
}

export default function SessionDrawer() {
  const {
    showDrawer,
    sessions,
    currentSessionKey,
    switchSession,
    createSession,
    deleteSession,
    setShowDrawer,
    getSessionDisplayName,
  } = useStore()

  const drawerRef = useRef<HTMLDivElement>(null)
  const [search, setSearch] = useState('')
  const close = useCallback(() => {
    setShowDrawer(false)
    setSearch('')
  }, [setShowDrawer])

  useEffect(() => {
    if (!showDrawer) return
    const handleClickOutside = (e: TouchEvent | MouseEvent) => {
      if (drawerRef.current && !drawerRef.current.contains(e.target as Node)) {
        close()
      }
    }
    document.addEventListener('touchend', handleClickOutside, { passive: true })
    document.addEventListener('mousedown', handleClickOutside)
    return () => {
      document.removeEventListener('touchend', handleClickOutside)
      document.removeEventListener('mousedown', handleClickOutside)
    }
  }, [showDrawer, close])

  useEffect(() => {
    if (!showDrawer) return
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') close()
    }
    document.addEventListener('keydown', handleEscape)
    return () => document.removeEventListener('keydown', handleEscape)
  }, [showDrawer, close])

  const displaySessions = sessions.length > 0
    ? sessions
    : [{ key: currentSessionKey }]

  const filteredSessions = search.trim()
    ? displaySessions.filter((s) => {
        const name = getSessionDisplayName(s.key).toLowerCase()
        return name.includes(search.toLowerCase())
      })
    : displaySessions

  return (
    <div
      className="fixed inset-0 z-50 flex"
      style={{
        pointerEvents: showDrawer ? 'auto' : 'none',
        visibility: showDrawer ? 'visible' : 'hidden',
      }}
    >
      <div
        className="absolute inset-0 bg-black/40"
        style={{
          opacity: showDrawer ? 1 : 0,
          transition: 'opacity 180ms ease-out',
        }}
      />

      <div
        ref={drawerRef}
        className="relative w-72 max-w-[85vw] h-full bg-white dark:bg-[#141414] shadow-2xl flex flex-col"
        style={{
          transform: showDrawer ? 'translate3d(0,0,0)' : 'translate3d(-100%,0,0)',
          transition: 'transform 180ms ease-out',
        }}
      >
        <div className="flex items-center justify-between px-4 py-3 border-b border-gray-100 dark:border-gray-800 safe-top">
          <h2 className="text-[17px] font-semibold text-gray-900 dark:text-white">
            Sessions
          </h2>
          <button
            onClick={close}
            className="flex items-center justify-center p-2 -mr-2 rounded-xl active:bg-gray-100 dark:active:bg-gray-800"
            aria-label="Close"
          >
            <svg className="w-5 h-5 text-gray-500" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        {/* Search */}
        {displaySessions.length > 3 && (
          <div className="px-3 py-2 border-b border-gray-100 dark:border-gray-800">
            <input
              type="text"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search sessions..."
              className="w-full px-3 py-1.5 text-[13px] rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-white placeholder-gray-400 dark:placeholder-gray-500 focus:outline-none focus:ring-1 focus:ring-amber-700/40"
            />
          </div>
        )}

        <div className="flex-1 overflow-y-auto py-1">
          {filteredSessions.map((session) => {
            const isActive = session.key === currentSessionKey
            return (
              <SessionItem
                key={session.key}
                session={session}
                isActive={isActive}
                displayName={getSessionDisplayName(session.key)}
                onSwitch={() => { switchSession(session.key); close() }}
                onDelete={() => deleteSession(session.key)}
              />
            )
          })}
        </div>

        <div className="border-t border-gray-100 dark:border-gray-800 p-3 safe-bottom">
          <button
            onClick={() => {
              const key = `chat-${Date.now()}`
              createSession(key)
              close()
            }}
            className="w-full flex items-center justify-center gap-2 py-2.5 rounded-xl border border-dashed border-gray-200 dark:border-gray-700 text-gray-500 dark:text-gray-400 text-sm active:scale-[0.98]"
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
