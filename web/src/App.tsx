import { useEffect } from 'react'
import { useStore } from './store'
import ChatScreen from './components/ChatScreen'
import SessionDrawer from './components/SessionDrawer'
import Settings from './components/Settings'
import { mockConversation } from './lib/mockData'

const USE_MOCK = new URLSearchParams(window.location.search).has('mock')

export default function App() {
  const { showSettings, connect } = useStore()

  useEffect(() => {
    if (USE_MOCK) {
      useStore.setState((state) => ({
        connectionState: 'connected',
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
      connect()
    }
  }, [connect])

  return (
    <div className="fixed inset-0 flex flex-col overflow-hidden bg-white dark:bg-[#1a1410]">
      <ChatScreen />
      {/* Drawer always mounted — pure CSS show/hide for 60fps animation */}
      <SessionDrawer />
      {showSettings && <Settings />}
    </div>
  )
}
