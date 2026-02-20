import { useEffect } from 'react'
import { useStore } from './store'
import ChatScreen from './components/ChatScreen'
import SessionDrawer from './components/SessionDrawer'
import Settings from './components/Settings'

export default function App() {
  const { showSettings, showDrawer, connect } = useStore()

  useEffect(() => {
    connect()
  }, [connect])

  return (
    <div className="h-dvh flex flex-col overflow-hidden bg-white dark:bg-gray-900">
      <ChatScreen />
      {showDrawer && <SessionDrawer />}
      {showSettings && <Settings />}
    </div>
  )
}
