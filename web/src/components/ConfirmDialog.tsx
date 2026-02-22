export default function ConfirmDialog({
  title,
  message,
  confirmLabel = '确定',
  cancelLabel = '取消',
  danger = false,
  onConfirm,
  onCancel,
}: {
  title: string
  message: string
  confirmLabel?: string
  cancelLabel?: string
  danger?: boolean
  onConfirm: () => void
  onCancel: () => void
}) {
  return (
    <div className="fixed inset-0 z-[70] flex items-center justify-center p-6">
      <div className="absolute inset-0 bg-black/50" onClick={onCancel} />
      <div className="relative w-full max-w-xs bg-white dark:bg-[#1e1812] rounded-2xl shadow-2xl overflow-hidden animate-fade-in">
        <div className="p-5 text-center">
          <h3 className="text-[17px] font-semibold text-gray-900 dark:text-white mb-1.5">
            {title}
          </h3>
          <p className="text-[14px] text-gray-500 dark:text-gray-400 leading-relaxed">
            {message}
          </p>
        </div>
        <div className="flex border-t border-gray-100 dark:border-gray-700">
          <button
            onClick={onCancel}
            className="flex-1 py-3.5 text-[15px] font-medium text-gray-500 dark:text-gray-400 active:bg-gray-50 dark:active:bg-gray-800 border-r border-gray-100 dark:border-gray-700 transition-colors"
          >
            {cancelLabel}
          </button>
          <button
            onClick={onConfirm}
            className={`flex-1 py-3.5 text-[15px] font-semibold transition-colors ${
              danger
                ? 'text-red-500 active:bg-red-50 dark:active:bg-red-500/10'
                : 'text-amber-700 dark:text-amber-500 active:bg-amber-50 dark:active:bg-amber-500/10'
            }`}
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  )
}
