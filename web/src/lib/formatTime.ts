/**
 * 相对时间格式化 — 中文
 * "刚刚", "2分钟前", "昨天 15:14", "2月20日 15:14"
 */

export function formatRelativeTime(timestamp: number): string {
  const now = Date.now()
  const diff = now - timestamp
  const seconds = Math.floor(diff / 1000)
  const minutes = Math.floor(seconds / 60)
  const hours = Math.floor(minutes / 60)

  if (seconds < 60) return '刚刚'
  if (minutes < 60) return `${minutes}分钟前`
  if (hours < 24) return `${hours}小时前`

  const date = new Date(timestamp)
  const today = new Date()
  const yesterday = new Date(today)
  yesterday.setDate(yesterday.getDate() - 1)

  const timeStr = date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })

  if (date.toDateString() === today.toDateString()) return `今天 ${timeStr}`
  if (date.toDateString() === yesterday.toDateString()) return `昨天 ${timeStr}`

  const days = Math.floor(hours / 24)
  if (days < 7) return `${days}天前`

  const monthDay = `${date.getMonth() + 1}月${date.getDate()}日`
  return `${monthDay} ${timeStr}`
}
