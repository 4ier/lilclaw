import { useState, useEffect, useCallback } from 'react'

interface Toast {
  id: number
  message: string
  type: 'info' | 'error' | 'success'
}

let nextId = 0
const listeners: Array<(t: Toast) => void> = []

export function showToast(message: string, type: Toast['type'] = 'info') {
  const toast: Toast = { id: nextId++, message, type }
  listeners.forEach(fn => fn(toast))
}

export default function ToastContainer() {
  const [toasts, setToasts] = useState<Toast[]>([])

  const addToast = useCallback((t: Toast) => {
    setToasts(prev => [...prev.slice(-2), t])  // Keep max 3
    setTimeout(() => {
      setToasts(prev => prev.filter(x => x.id !== t.id))
    }, 3000)
  }, [])

  useEffect(() => {
    listeners.push(addToast)
    return () => {
      const idx = listeners.indexOf(addToast)
      if (idx >= 0) listeners.splice(idx, 1)
    }
  }, [addToast])

  if (toasts.length === 0) return null

  return (
    <div className="fixed top-16 left-1/2 -translate-x-1/2 z-[90] flex flex-col gap-2 pointer-events-none">
      {toasts.map(t => (
        <div
          key={t.id}
          className={`px-4 py-2.5 rounded-2xl shadow-lg text-[14px] font-medium animate-fade-in pointer-events-auto ${
            t.type === 'error'
              ? 'bg-red-500 text-white'
              : t.type === 'success'
              ? 'bg-emerald-500 text-white'
              : 'bg-gray-800 dark:bg-gray-200 text-white dark:text-gray-800'
          }`}
        >
          {t.message}
        </div>
      ))}
    </div>
  )
}
