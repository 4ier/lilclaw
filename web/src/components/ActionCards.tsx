import { type ActionCard, BUILTIN_ACTIONS } from '../lib/actions'

interface ActionCardsProps {
  onSelect: (action: ActionCard) => void
}

export default function ActionCards({ onSelect }: ActionCardsProps) {
  return (
    <div className="grid grid-cols-3 gap-2.5 max-w-[340px]">
      {BUILTIN_ACTIONS.map((action) => (
        <button
          key={action.id}
          onClick={() => onSelect(action)}
          className={`flex flex-col items-center gap-1.5 p-3 rounded-2xl border transition-all active:scale-95 ${action.color}`}
        >
          <span className="text-2xl">{action.icon}</span>
          <span className="text-[12px] font-medium text-gray-700 dark:text-gray-300 leading-tight">
            {action.title}
          </span>
        </button>
      ))}
    </div>
  )
}
