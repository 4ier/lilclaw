// Action Card definitions — designed for non-tech Chinese users (parents generation)
export interface ActionCard {
  id: string
  icon: string
  title: string
  description: string
  inputMode: 'text' | 'camera' | 'image' | 'file' | 'url' | 'voice'
  promptTemplate: string
  color: string
}

export const BUILTIN_ACTIONS: ActionCard[] = [
  {
    id: 'photo-ask',
    icon: '📸',
    title: '拍照问问',
    description: '拍张照片，问你想知道的',
    inputMode: 'camera',
    promptTemplate: '请看这张图片，${input}',
    color: 'bg-purple-50 dark:bg-purple-900/20 border-purple-200 dark:border-purple-800',
  },
  {
    id: 'help-write',
    icon: '✍️',
    title: '帮我写',
    description: '写通知、祝福语、请假条…',
    inputMode: 'text',
    promptTemplate: '请帮我写：${input}\n\n要求：语言通顺自然，适合中文语境。',
    color: 'bg-blue-50 dark:bg-blue-900/20 border-blue-200 dark:border-blue-800',
  },
  {
    id: 'translate',
    icon: '🌐',
    title: '翻译',
    description: '中英互译，看不懂的拍照也行',
    inputMode: 'text',
    promptTemplate: '请翻译以下内容。如果是中文就翻译成英文，如果是英文就翻译成中文：\n\n${input}',
    color: 'bg-emerald-50 dark:bg-emerald-900/20 border-emerald-200 dark:border-emerald-800',
  },
  {
    id: 'look-up',
    icon: '🔍',
    title: '查一下',
    description: '不懂的事情问一问',
    inputMode: 'text',
    promptTemplate: '${input}\n\n请用简单易懂的中文解释，避免专业术语。',
    color: 'bg-amber-50 dark:bg-amber-900/20 border-amber-200 dark:border-amber-800',
  },
  {
    id: 'health',
    icon: '💊',
    title: '健康问问',
    description: '身体不舒服？先问问看',
    inputMode: 'text',
    promptTemplate: '我想咨询一个健康问题：${input}\n\n请给出通俗易懂的建议，并提醒我严重时应该去医院。',
    color: 'bg-red-50 dark:bg-red-900/20 border-red-200 dark:border-red-800',
  },
  {
    id: 'daily-life',
    icon: '🍳',
    title: '生活助手',
    description: '菜谱、生活窍门、出行建议',
    inputMode: 'text',
    promptTemplate: '${input}\n\n请给出实用的建议，用简单的中文回答。',
    color: 'bg-orange-50 dark:bg-orange-900/20 border-orange-200 dark:border-orange-800',
  },
]
