import { useState } from 'react'
import ChatPanel from './components/ChatPanel'
import ExplorePanel from './components/ExplorePanel'

const TABS = { ask: 'Ask the analyst', explore: 'Explore stats' }

export default function App() {
  const [tab, setTab] = useState('ask')

  return (
    <div className="app">
      <header>
        <h1>🏏 AI Cricket Analytics</h1>
        <nav>
          {Object.entries(TABS).map(([key, label]) => (
            <button key={key} className={tab === key ? 'active' : ''} onClick={() => setTab(key)}>
              {label}
            </button>
          ))}
        </nav>
      </header>
      <main>{tab === 'ask' ? <ChatPanel /> : <ExplorePanel />}</main>
    </div>
  )
}
