/**
 * Relative time formatter — no dependencies.
 * "just now", "2 min ago", "Yesterday 3:14 PM", "Feb 20 3:14 PM"
 */

export function formatRelativeTime(timestamp: number): string {
  const now = Date.now()
  const diff = now - timestamp
  const seconds = Math.floor(diff / 1000)
  const minutes = Math.floor(seconds / 60)
  const hours = Math.floor(minutes / 60)

  if (seconds < 60) return 'just now'
  if (minutes < 60) return `${minutes} min ago`
  if (hours < 24) return `${hours}h ago`

  const date = new Date(timestamp)
  const today = new Date()
  const yesterday = new Date(today)
  yesterday.setDate(yesterday.getDate() - 1)

  const timeStr = date.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })

  if (date.toDateString() === today.toDateString()) return timeStr
  if (date.toDateString() === yesterday.toDateString()) return `Yesterday ${timeStr}`

  const monthDay = date.toLocaleDateString([], { month: 'short', day: 'numeric' })
  return `${monthDay} ${timeStr}`
}
