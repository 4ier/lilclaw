/**
 * Format a date for a message separator line
 * Returns: 今天, 昨天, 前天, X月X日, or YYYY年X月X日
 */
export function formatDateSeparator(timestamp: number): string {
  const date = new Date(timestamp)
  const now = new Date()
  
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const msgDay = new Date(date.getFullYear(), date.getMonth(), date.getDate())
  const diffDays = Math.floor((today.getTime() - msgDay.getTime()) / (24 * 60 * 60 * 1000))
  
  if (diffDays === 0) return '今天'
  if (diffDays === 1) return '昨天'
  if (diffDays === 2) return '前天'
  
  if (date.getFullYear() === now.getFullYear()) {
    return `${date.getMonth() + 1}月${date.getDate()}日`
  }
  
  return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日`
}

/**
 * Check if two timestamps are on different days
 */
export function isDifferentDay(t1: number | undefined, t2: number | undefined): boolean {
  if (!t1 || !t2) return false
  const d1 = new Date(t1)
  const d2 = new Date(t2)
  return d1.getFullYear() !== d2.getFullYear() ||
    d1.getMonth() !== d2.getMonth() ||
    d1.getDate() !== d2.getDate()
}
