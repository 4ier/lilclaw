import { useEffect, useRef, useCallback } from 'react'

export interface ContextMenuItem {
  label: string
  icon: string
  onClick: () => void
  danger?: boolean
}

interface ContextMenuProps {
  items: ContextMenuItem[]
  x: number
  y: number
  onClose: () => void
}

export default function ContextMenu({ items, x, y, onClose }: ContextMenuProps) {
  const menuRef = useRef<HTMLDivElement>(null)

  // Adjust position to stay within viewport
  const adjustedPosition = useCallback(() => {
    const menu = menuRef.current
    if (!menu) return { left: x, top: y }

    const rect = menu.getBoundingClientRect()
    const vw = window.innerWidth
    const vh = window.innerHeight

    let left = x
    let top = y

    if (left + rect.width > vw - 8) left = vw - rect.width - 8
    if (left < 8) left = 8
    if (top + rect.height > vh - 8) top = y - rect.height
    if (top < 8) top = 8

    return { left, top }
  }, [x, y])

  useEffect(() => {
    const menu = menuRef.current
    if (menu) {
      const { left, top } = adjustedPosition()
      menu.style.left = `${left}px`
      menu.style.top = `${top}px`
    }
  }, [adjustedPosition])

  // Dismiss on outside tap or scroll
  useEffect(() => {
    const dismiss = (e: Event) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        onClose()
      }
    }
    // Small delay so the triggering event doesn't immediately dismiss
    const timer = setTimeout(() => {
      document.addEventListener('touchstart', dismiss, { passive: true })
      document.addEventListener('mousedown', dismiss)
      document.addEventListener('scroll', onClose, { passive: true, capture: true })
    }, 50)

    return () => {
      clearTimeout(timer)
      document.removeEventListener('touchstart', dismiss)
      document.removeEventListener('mousedown', dismiss)
      document.removeEventListener('scroll', onClose, true)
    }
  }, [onClose])

  return (
    <div className="fixed inset-0 z-[100]" style={{ pointerEvents: 'auto' }}>
      <div
        ref={menuRef}
        className="fixed bg-white dark:bg-[#2a2218] rounded-xl shadow-xl border border-gray-200 dark:border-gray-700 py-1 min-w-[140px] animate-menu-in"
        style={{ left: x, top: y }}
      >
        {items.map((item, i) => (
          <button
            key={i}
            onClick={() => { item.onClick(); onClose() }}
            className={`w-full flex items-center gap-2.5 px-3.5 py-2.5 text-left text-[14px] active:bg-gray-100 dark:active:bg-white/10 ${
              item.danger
                ? 'text-red-500 dark:text-red-400'
                : 'text-gray-800 dark:text-gray-200'
            }`}
          >
            <span className="text-base leading-none">{item.icon}</span>
            <span>{item.label}</span>
          </button>
        ))}
      </div>
    </div>
  )
}
