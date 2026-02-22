import { useState, useEffect } from 'react'

const WELCOME_KEY = 'lilclaw-welcomed'

export default function Welcome() {
  const [show, setShow] = useState(false)

  useEffect(() => {
    if (!localStorage.getItem(WELCOME_KEY)) {
      setShow(true)
    }
  }, [])

  if (!show) return null

  const dismiss = () => {
    localStorage.setItem(WELCOME_KEY, '1')
    setShow(false)
  }

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center p-6">
      <div className="absolute inset-0 bg-black/50" onClick={dismiss} />
      <div className="relative w-full max-w-sm bg-white dark:bg-[#1e1812] rounded-3xl shadow-2xl overflow-hidden animate-fade-in">
        {/* Paw icon */}
        <div className="pt-8 pb-4 flex justify-center">
          <div className="text-5xl">🐾</div>
        </div>

        <div className="px-6 pb-2 text-center">
          <h2 className="text-xl font-bold text-gray-900 dark:text-white mb-1">
            欢迎使用小爪
          </h2>
          <p className="text-sm text-gray-500 dark:text-gray-400">
            你的智能生活助手
          </p>
        </div>

        <div className="px-6 py-4 space-y-4">
          <div className="flex items-start gap-3">
            <span className="text-xl flex-shrink-0">💬</span>
            <div>
              <p className="text-[15px] font-medium text-gray-900 dark:text-white">直接打字问</p>
              <p className="text-[13px] text-gray-500 dark:text-gray-400">什么问题都可以问，用日常说话的方式就好</p>
            </div>
          </div>
          <div className="flex items-start gap-3">
            <span className="text-xl flex-shrink-0">🎯</span>
            <div>
              <p className="text-[15px] font-medium text-gray-900 dark:text-white">试试快捷功能</p>
              <p className="text-[13px] text-gray-500 dark:text-gray-400">拍照问问、翻译、查一下……点一下就能用</p>
            </div>
          </div>
          <div className="flex items-start gap-3">
            <span className="text-xl flex-shrink-0">⚙️</span>
            <div>
              <p className="text-[15px] font-medium text-gray-900 dark:text-white">字太小？去设置调</p>
              <p className="text-[13px] text-gray-500 dark:text-gray-400">右上角齿轮 → 字号大小，拖到舒服的大小</p>
            </div>
          </div>
        </div>

        <div className="px-6 pb-6 pt-2">
          <button
            onClick={dismiss}
            className="w-full py-3.5 rounded-2xl bg-amber-700 dark:bg-amber-600 text-white font-semibold text-base active:bg-amber-800 dark:active:bg-amber-700 transition-colors"
          >
            开始使用
          </button>
        </div>
      </div>
    </div>
  )
}
