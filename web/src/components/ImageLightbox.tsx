import { useState, useCallback, useRef } from 'react'

interface ImageLightboxProps {
  src: string
  onClose: () => void
}

export default function ImageLightbox({ src, onClose }: ImageLightboxProps) {
  const [scale, setScale] = useState(1)
  const [translate, setTranslate] = useState({ x: 0, y: 0 })
  const lastPinchDist = useRef<number | null>(null)
  const lastPan = useRef<{ x: number; y: number } | null>(null)
  const imgRef = useRef<HTMLImageElement>(null)

  const handleTouchStart = useCallback((e: React.TouchEvent) => {
    if (e.touches.length === 2) {
      const dx = e.touches[0].clientX - e.touches[1].clientX
      const dy = e.touches[0].clientY - e.touches[1].clientY
      lastPinchDist.current = Math.hypot(dx, dy)
    } else if (e.touches.length === 1 && scale > 1) {
      lastPan.current = { x: e.touches[0].clientX, y: e.touches[0].clientY }
    }
  }, [scale])

  const handleTouchMove = useCallback((e: React.TouchEvent) => {
    if (e.touches.length === 2 && lastPinchDist.current !== null) {
      const dx = e.touches[0].clientX - e.touches[1].clientX
      const dy = e.touches[0].clientY - e.touches[1].clientY
      const dist = Math.hypot(dx, dy)
      const ratio = dist / lastPinchDist.current
      setScale(prev => Math.max(1, Math.min(5, prev * ratio)))
      lastPinchDist.current = dist
    } else if (e.touches.length === 1 && lastPan.current && scale > 1) {
      const dx = e.touches[0].clientX - lastPan.current.x
      const dy = e.touches[0].clientY - lastPan.current.y
      setTranslate(prev => ({ x: prev.x + dx, y: prev.y + dy }))
      lastPan.current = { x: e.touches[0].clientX, y: e.touches[0].clientY }
    }
  }, [scale])

  const handleTouchEnd = useCallback(() => {
    lastPinchDist.current = null
    lastPan.current = null
  }, [])

  const handleDoubleTap = useCallback(() => {
    if (scale > 1) {
      setScale(1)
      setTranslate({ x: 0, y: 0 })
    } else {
      setScale(2.5)
    }
  }, [scale])

  // Track double tap
  const lastTap = useRef(0)
  const handleClick = useCallback((e: React.MouseEvent) => {
    e.stopPropagation()
    const now = Date.now()
    if (now - lastTap.current < 300) {
      handleDoubleTap()
    }
    lastTap.current = now
  }, [handleDoubleTap])

  return (
    <div
      className="fixed inset-0 z-[80] flex items-center justify-center bg-black/90"
      onClick={onClose}
    >
      {/* Close button */}
      <button
        onClick={onClose}
        className="absolute top-4 right-4 z-10 p-3 rounded-full bg-black/50 text-white active:bg-black/70 safe-top"
        aria-label="关闭"
      >
        <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
          <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
        </svg>
      </button>

      {/* Image */}
      <img
        ref={imgRef}
        src={src}
        alt="预览"
        className="max-w-full max-h-full object-contain select-none"
        style={{
          transform: `translate(${translate.x}px, ${translate.y}px) scale(${scale})`,
          transition: lastPinchDist.current !== null ? 'none' : 'transform 200ms ease-out',
        }}
        onClick={handleClick}
        onTouchStart={handleTouchStart}
        onTouchMove={handleTouchMove}
        onTouchEnd={handleTouchEnd}
        draggable={false}
      />

      {/* Zoom hint */}
      {scale === 1 && (
        <div className="absolute bottom-8 left-1/2 -translate-x-1/2 text-[13px] text-white/50 safe-bottom">
          双击放大 · 捏合缩放
        </div>
      )}
    </div>
  )
}
