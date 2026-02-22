/**
 * Haptic feedback — calls native bridge if available, fallback to vibrate API
 */
export function haptic(type: 'light' | 'medium' | 'heavy' | 'selection' = 'light') {
  try {
    // Kotlin native bridge
    if (window.LilClaw && 'haptic' in (window.LilClaw as unknown as Record<string, unknown>)) {
      (window.LilClaw as unknown as { haptic: (t: string) => void }).haptic(type)
      return
    }
    // Web Vibration API fallback
    if ('vibrate' in navigator) {
      const duration = type === 'heavy' ? 30 : type === 'medium' ? 15 : 5
      navigator.vibrate(duration)
    }
  } catch {
    // Silent fail
  }
}
