import { useEffect } from 'react'
import { useStore } from './store'
import ChatScreen from './components/ChatScreen'
import SessionDrawer from './components/SessionDrawer'
import Settings from './components/Settings'
import { mockConversation } from './lib/mockData'

const USE_MOCK = new URLSearchParams(window.location.search).has('mock')

export default function App() {
  const { showSettings, connect, loadCachedMessages } = useStore()

  useEffect(() => {
    if (USE_MOCK) {
      useStore.setState((state) => ({
        connectionState: 'connected',
        cacheLoaded: true,
        sessions: [
          { key: 'main', label: '聊天 UI 渲染测试' },
          { key: 'coding', label: '写一个 Todo App' },
          { key: 'research' },
        ],
        messages: {
          ...state.messages,
          main: mockConversation,
        },
      }))
    } else {
      // Load cached messages first (instant), then connect to gateway (async)
      loadCachedMessages().then(() => {
        connect()
      })
    }
  }, [connect, loadCachedMessages])

  return (
    <div className="fixed inset-0 flex flex-col overflow-hidden bg-white dark:bg-[#1a1410]">
      <ChatScreen />
      <SessionDrawer />
      {showSettings && <Settings />}
    </div>
  )
}
