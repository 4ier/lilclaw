// Action Card definitions — each encapsulates input mode + prompt template
export interface ActionCard {
  id: string
  icon: string       // emoji or SVG
  title: string
  description: string
  inputMode: 'text' | 'camera' | 'image' | 'file' | 'url' | 'voice'
  promptTemplate: string  // ${input} gets replaced
  color: string      // tailwind color class for accent
}

export const BUILTIN_ACTIONS: ActionCard[] = [
  {
    id: 'photo-ask',
    icon: '📸',
    title: 'Photo Ask',
    description: 'Take a photo and ask about it',
    inputMode: 'camera',
    promptTemplate: '${input}',
    color: 'bg-purple-50 dark:bg-purple-900/20 border-purple-200 dark:border-purple-800',
  },
  {
    id: 'summarize-url',
    icon: '🌐',
    title: 'Summarize',
    description: 'Summarize a webpage or article',
    inputMode: 'url',
    promptTemplate: 'Summarize this webpage concisely: ${input}',
    color: 'bg-blue-50 dark:bg-blue-900/20 border-blue-200 dark:border-blue-800',
  },
  {
    id: 'write-code',
    icon: '💻',
    title: 'Code',
    description: 'Write or fix code',
    inputMode: 'text',
    promptTemplate: '${input}',
    color: 'bg-emerald-50 dark:bg-emerald-900/20 border-emerald-200 dark:border-emerald-800',
  },
  {
    id: 'translate',
    icon: '🔤',
    title: 'Translate',
    description: 'Translate text or images',
    inputMode: 'text',
    promptTemplate: 'Translate the following to English. If already English, translate to Chinese:\n\n${input}',
    color: 'bg-amber-50 dark:bg-amber-900/20 border-amber-200 dark:border-amber-800',
  },
  {
    id: 'explain',
    icon: '💡',
    title: 'Explain',
    description: 'Explain anything simply',
    inputMode: 'text',
    promptTemplate: 'Explain this in simple terms:\n\n${input}',
    color: 'bg-orange-50 dark:bg-orange-900/20 border-orange-200 dark:border-orange-800',
  },
  {
    id: 'debug',
    icon: '🐛',
    title: 'Debug',
    description: 'Help fix errors and bugs',
    inputMode: 'text',
    promptTemplate: 'I\'m getting this error. Help me understand and fix it:\n\n${input}',
    color: 'bg-red-50 dark:bg-red-900/20 border-red-200 dark:border-red-800',
  },
]
