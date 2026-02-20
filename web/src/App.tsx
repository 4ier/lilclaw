import { useEffect } from 'react'
import { useStore } from './store'
import ChatScreen from './components/ChatScreen'
import SessionDrawer from './components/SessionDrawer'
import Settings from './components/Settings'
import { mockConversation } from './lib/mockData'

const USE_MOCK = new URLSearchParams(window.location.search).has('mock')

export default function App() {
  const { showSettings, showDrawer, connect } = useStore()

  useEffect(() => {
    if (USE_MOCK) {
      // Load mock data for testing rendering
      useStore.setState((state) => ({
        connectionState: 'connected',
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
    <div className="h-dvh flex flex-col overflow-hidden bg-white dark:bg-gray-900">
      <ChatScreen />
      {showDrawer && <SessionDrawer />}
      {showSettings && <Settings />}
    </div>
  )
}
